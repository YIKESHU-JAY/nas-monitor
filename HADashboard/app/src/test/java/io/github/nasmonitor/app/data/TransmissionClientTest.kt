package io.github.nasmonitor.app.data

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class TransmissionClientTest {
    private fun serverFor(port: Int) = DownloaderServer(
        id = "test",
        name = "Test",
        host = "127.0.0.1",
        port = port,
        username = "user",
        rpcPath = "/transmission/rpc"
    )

    @Test
    fun endpointSupportsHostWithoutSchemeAndHttps() {
        assertEquals(
            "http://192.0.2.21:9091/transmission/rpc",
            TransmissionClient.endpoint(DownloaderServer(name = "n", host = "192.0.2.21", port = 9091))
        )
        assertEquals(
            "https://nas.example.com:443/transmission/rpc",
            TransmissionClient.endpoint(DownloaderServer(name = "n", host = "nas.example.com", port = 443, useHttps = true))
        )
        assertEquals(
            "http://10.0.0.2:9091/custom/rpc",
            TransmissionClient.endpoint(DownloaderServer(name = "n", host = "10.0.0.2", port = 9091, rpcPath = "custom/rpc"))
        )
    }

    @Test
    fun explicitSchemeKeepsItsOwnDefaultPort() {
        assertEquals(
            "https://nas.example.com/transmission/rpc",
            TransmissionClient.endpoint(DownloaderServer(name = "n", host = "https://nas.example.com", port = 9091, useHttps = false))
        )
        assertEquals(
            "http://nas.example.com/transmission/rpc",
            TransmissionClient.endpoint(DownloaderServer(name = "n", host = "http://nas.example.com", port = 9091, useHttps = true))
        )
        assertEquals(
            "https://nas.example.com:8443/transmission/rpc",
            TransmissionClient.endpoint(DownloaderServer(name = "n", host = "https://nas.example.com:8443", port = 9091))
        )
        assertEquals(
            "https://nas.example.com",
            TransmissionClient.displayAddress(DownloaderServer(name = "n", host = "https://nas.example.com", port = 9091))
        )
        assertEquals(
            "http://192.0.2.21:9091",
            TransmissionClient.displayAddress(DownloaderServer(name = "n", host = "192.0.2.21", port = 9091))
        )
    }

    @Test
    fun statusLabelsDistinguishQueueKinds() {
        assertEquals("等待校验", torrentStatusLabel(1, false))
        assertEquals("校验中", torrentStatusLabel(2, false))
        assertEquals("排队下载", torrentStatusLabel(3, false))
        assertEquals("下载中", torrentStatusLabel(4, false))
        assertEquals("下载中 · 停滞", torrentStatusLabel(4, true))
        assertEquals("排队做种", torrentStatusLabel(5, false))
        assertEquals("做种中", torrentStatusLabel(6, false))
        assertEquals("做种中 · 无连接", torrentStatusLabel(6, true))
        assertEquals("已暂停", torrentStatusLabel(0, false))
    }

    @Test
    fun rejectsInvalidServerAddresses() {
        listOf(
            DownloaderServer(name = "", host = "127.0.0.1"),
            DownloaderServer(name = "n", host = ""),
            DownloaderServer(name = "n", host = "127.0.0.1", port = 0),
            DownloaderServer(name = "n", host = "ftp://127.0.0.1")
        ).forEach { server ->
            assertTrue("应拒绝 $server", runCatching { TransmissionClient.validate(server) }.isFailure)
        }
    }

    @Test
    fun retriesOnceWithSessionIdAfter409() = runBlocking {
        val mock = MockWebServer()
        mock.enqueue(
            MockResponse().setResponseCode(409)
                .addHeader("X-Transmission-Session-Id", "session-abc")
        )
        mock.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"result":"success","arguments":{"version":"4.0.5","rpc-version":17,"download-dir":"/downloads"}}""")
        )
        mock.start()
        try {
            val session = TransmissionClient.session(serverFor(mock.port))
            assertEquals("4.0.5", session.version)
            assertEquals(17, session.rpcVersion)
            val first = mock.takeRequest()
            val second = mock.takeRequest()
            assertEquals(null, first.getHeader("X-Transmission-Session-Id"))
            assertEquals("session-abc", second.getHeader("X-Transmission-Session-Id"))
            assertTrue(first.getHeader("Authorization")!!.startsWith("Basic "))
        } finally {
            mock.shutdown()
        }
    }

    @Test
    fun mapsAuthenticationFailureToFriendlyMessage() = runBlocking {
        val mock = MockWebServer()
        mock.enqueue(MockResponse().setResponseCode(401))
        mock.start()
        try {
            TransmissionClient.session(serverFor(mock.port))
            fail("应抛出认证异常")
        } catch (error: TransmissionException) {
            assertEquals("用户名或密码错误", error.message)
        } finally {
            mock.shutdown()
        }
    }

    @Test
    fun statsReadCumulativeAndErrorCounts() = runBlocking {
        val mock = MockWebServer()
        mock.enqueue(MockResponse().setResponseCode(409).addHeader("X-Transmission-Session-Id", "s1"))
        mock.enqueue(MockResponse().setResponseCode(200).setBody("""
            {"result":"success","arguments":{
              "downloadSpeed":11,"uploadSpeed":22,"activeTorrentCount":2,"pausedTorrentCount":1,"torrentCount":5,
              "cumulative-stats":{"downloadedBytes":1000,"uploadedBytes":2000}}}
        """.trimIndent()))
        mock.enqueue(MockResponse().setResponseCode(200).setBody("""
            {"result":"success","arguments":{"torrents":[{"error":0},{"error":2},{"error":0}]}}
        """.trimIndent()))
        mock.enqueue(MockResponse().setResponseCode(200).setBody("""
            {"result":"success","arguments":{"download-dir":"/downloads"}}
        """.trimIndent()))
        mock.enqueue(MockResponse().setResponseCode(200).setBody("""
            {"result":"success","arguments":{"path":"/downloads","size-bytes":7777}}
        """.trimIndent()))
        mock.start()
        try {
            val stats = TransmissionClient.stats(serverFor(mock.port))
            assertEquals(1000L, stats.totalDownloaded)
            assertEquals(2000L, stats.totalUploaded)
            assertEquals(7777L, stats.freeSpace)
            assertEquals(1, stats.errorTorrentCount)
        } finally {
            mock.shutdown()
        }
    }

    @Test
    fun addMagnetSendsExpectedArguments() = runBlocking {
        val mock = MockWebServer()
        mock.enqueue(MockResponse().setResponseCode(200).setBody("""{"result":"success","arguments":{}}"""))
        mock.start()
        try {
            TransmissionClient.addMagnet(serverFor(mock.port), "magnet:?xt=urn:btih:abc", "/downloads", true)
            val body = mock.takeRequest().body.readUtf8()
            assertTrue(body.contains("\"method\":\"torrent-add\""))
            assertTrue(body.contains("\"filename\":\"magnet:?xt=urn:btih:abc\""))
            assertTrue(body.contains("\"paused\":true"))
            assertTrue(body.contains("\"download-dir\":\"/downloads\""))
        } finally {
            mock.shutdown()
        }
    }

    @Test
    fun removeIncludesDeleteLocalDataFlag() = runBlocking {
        val mock = MockWebServer()
        mock.enqueue(MockResponse().setResponseCode(200).setBody("""{"result":"success","arguments":{}}"""))
        mock.start()
        try {
            TransmissionClient.remove(serverFor(mock.port), listOf("7"), true)
            val body = mock.takeRequest().body.readUtf8()
            assertTrue(body.contains("\"method\":\"torrent-remove\""))
            assertTrue(body.contains("\"delete-local-data\":true"))
            assertTrue(body.contains("\"ids\":[7]"))
        } finally {
            mock.shutdown()
        }
    }

    @Test
    fun altSpeedLimitsWriteSessionSetFields() = runBlocking {
        val mock = MockWebServer()
        mock.enqueue(MockResponse().setResponseCode(200).setBody("""{"result":"success","arguments":{}}"""))
        mock.start()
        try {
            TransmissionClient.setAltSpeedLimits(serverFor(mock.port), 300, 400)
            val body = mock.takeRequest().body.readUtf8()
            assertTrue(body.contains("\"method\":\"session-set\""))
            assertTrue(body.contains("\"alt-speed-down\":300"))
            assertTrue(body.contains("\"alt-speed-up\":400"))
        } finally {
            mock.shutdown()
        }
    }

    @Test
    fun setSpeedLimitsWritesRegularAlternativeAndModeInOneSessionSet() = runBlocking {
        val mock = MockWebServer()
        mock.enqueue(MockResponse().setResponseCode(200).setBody("""{"result":"success","arguments":{}}"""))
        mock.start()
        try {
            TransmissionClient.setSpeedLimits(serverFor(mock.port), altMode = true, downKbps = 310, upKbps = 130, altDownKbps = 90, altUpKbps = 50)
            val request = mock.takeRequest()
            val body = request.body.readUtf8()
            assertTrue(body.contains("\"method\":\"session-set\""))
            assertTrue(body.contains("\"speed-limit-down\":310"))
            assertTrue(body.contains("\"speed-limit-up\":130"))
            assertTrue(body.contains("\"speed-limit-down-enabled\":true"))
            assertTrue(body.contains("\"speed-limit-up-enabled\":true"))
            assertTrue(body.contains("\"alt-speed-down\":90"))
            assertTrue(body.contains("\"alt-speed-up\":50"))
            assertTrue(body.contains("\"alt-speed-enabled\":true"))
            assertEquals(1, mock.requestCount)
        } finally {
            mock.shutdown()
        }
    }

    @Test
    fun formatsBytesSpeedAndEta() {
        assertEquals("1.00 KB", TransmissionClient.formatBytes(1024))
        assertEquals("1.50 MB", TransmissionClient.formatBytes(1024L * 1024L * 3 / 2))
        assertEquals("1.00 KB/s", TransmissionClient.formatSpeed(1024))
        assertEquals("—", TransmissionClient.formatEta(-1))
        assertEquals("45s", TransmissionClient.formatEta(45))
        assertEquals("2h 5m", TransmissionClient.formatEta(7500))
    }
}
