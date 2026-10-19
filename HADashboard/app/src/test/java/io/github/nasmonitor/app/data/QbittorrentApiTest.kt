package io.github.nasmonitor.app.data

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class QbittorrentApiTest {
    private fun serverFor(port: Int, username: String = "") = DownloaderServer(
        id = "qb",
        name = "qBittorrent",
        host = "127.0.0.1",
        port = port,
        username = username,
        protocol = DownloaderProtocol.QBITTORRENT,
        rpcPath = "/api/v2"
    )

    @Test
    fun endpointUsesWebApiPathAndDefaultPort() {
        assertEquals(
            "http://192.0.2.20:8085/api/v2",
            QbittorrentApi.endpoint(DownloaderServer(name = "qb", host = "192.0.2.20", port = 8085, protocol = DownloaderProtocol.QBITTORRENT, rpcPath = "/api/v2"))
        )
        assertEquals(
            "https://qb.example.com/api/v2",
            QbittorrentApi.endpoint(DownloaderServer(name = "qb", host = "https://qb.example.com", port = 8080, protocol = DownloaderProtocol.QBITTORRENT, rpcPath = "/api/v2"))
        )
        assertEquals(
            "http://192.0.2.20:8085",
            QbittorrentApi.displayAddress(DownloaderServer(name = "qb", host = "192.0.2.20", port = 8085))
        )
    }

    @Test
    fun emptyPathFallsBackToApiV2() {
        assertEquals(
            "http://10.0.0.2:8080/api/v2",
            QbittorrentApi.endpoint(DownloaderServer(name = "qb", host = "10.0.0.2", port = 8080, protocol = DownloaderProtocol.QBITTORRENT, rpcPath = ""))
        )
    }

    @Test
    fun unauthenticatedRequestWorksWithWhitelist() = runBlocking {
        val mock = MockWebServer()
        mock.enqueue(MockResponse().setResponseCode(200).setBody("v4.6.7"))
        mock.enqueue(MockResponse().setResponseCode(200).setBody("2.9.3"))
        mock.start()
        try {
            val version = QbittorrentApi.test(serverFor(mock.port), passwordOverride = "")
            assertTrue(version.contains("4.6.7"))
            val request = mock.takeRequest()
            assertEquals("/api/v2/app/version", request.path)
            assertEquals(null, request.getHeader("Authorization"))
            assertEquals("/api/v2/app/webapiVersion", mock.takeRequest().path)
        } finally {
            mock.shutdown()
        }
    }

    @Test
    fun logsInWithFormWhenServerRequiresAuth() = runBlocking {
        val mock = MockWebServer()
        mock.enqueue(MockResponse().setResponseCode(403))
        mock.enqueue(MockResponse().setResponseCode(200).addHeader("Set-Cookie", "SID=abc123; HttpOnly; path=/").setBody("Ok."))
        mock.enqueue(MockResponse().setResponseCode(200).setBody("v4.6.7"))
        mock.enqueue(MockResponse().setResponseCode(200).setBody("2.9.3"))
        mock.start()
        try {
            val version = QbittorrentApi.test(serverFor(mock.port, username = "admin"), passwordOverride = "secret")
            assertTrue(version.contains("4.6.7"))
            val first = mock.takeRequest()
            assertEquals("/api/v2/app/version", first.path)
            val login = mock.takeRequest()
            assertEquals("/api/v2/auth/login", login.path)
            val body = login.body.readUtf8()
            assertTrue(body.contains("username=admin"))
            assertTrue(body.contains("password=secret"))
            val retry = mock.takeRequest()
            assertEquals("SID=abc123", retry.getHeader("Cookie"))
        } finally {
            mock.shutdown()
        }
    }

    @Test
    fun torrentsMapQbittorrentFieldsToCommonSummary() = runBlocking {
        val mock = MockWebServer()
        mock.enqueue(MockResponse().setResponseCode(200).setBody("""
            [{"hash":"abc","name":"Demo","state":"downloading","progress":0.5,"size":1000,"total_size":2000,
              "dlspeed":100,"upspeed":50,"ratio":1.5,"eta":120,"save_path":"/pt","num_seeds":3,"num_leechs":2,
              "added_on":100,"uploaded":500,"downloaded":600,"tags":"MOVIEPILOT, TV"}]
        """.trimIndent()))
        mock.start()
        try {
            val list = QbittorrentApi.torrents(serverFor(mock.port))
            assertEquals(1, list.size)
            val torrent = list.first()
            assertEquals("abc", torrent.id)
            assertEquals("Demo", torrent.name)
            assertEquals(4, torrent.status)
            assertEquals(0.5, torrent.percentDone, 0.0001)
            assertEquals(2000L, torrent.totalSize)
            assertEquals(100L, torrent.rateDownload)
            assertEquals(1.5, torrent.uploadRatio, 0.0001)
            assertEquals(listOf("MOVIEPILOT", "TV"), torrent.labels)
            assertTrue(torrent.isRunning)
        } finally {
            mock.shutdown()
        }
    }

    @Test
    fun pausedAndStalledStatesMapToCommonStatus() = runBlocking {
        val mock = MockWebServer()
        mock.enqueue(MockResponse().setResponseCode(200).setBody("""
            [{"hash":"a","state":"pausedUP"},{"hash":"b","state":"stalledDL"},{"hash":"c","state":"queuedDL"},{"hash":"d","state":"error"}]
        """.trimIndent()))
        mock.start()
        try {
            val list = QbittorrentApi.torrents(serverFor(mock.port))
            assertEquals(0, list.first { it.id == "a" }.status)
            assertEquals("下载中 · 停滞", list.first { it.id == "b" }.statusLabel)
            assertEquals(3, list.first { it.id == "c" }.status)
            assertEquals(1, list.first { it.id == "d" }.error)
        } finally {
            mock.shutdown()
        }
    }

    @Test
    fun pauseAndResumeSendHashes() = runBlocking {
        val mock = MockWebServer()
        repeat(2) { mock.enqueue(MockResponse().setResponseCode(200).setBody("")) }
        mock.start()
        try {
            QbittorrentApi.stop(serverFor(mock.port), listOf("abc", "def"))
            QbittorrentApi.start(serverFor(mock.port), listOf("abc"))
            val pause = mock.takeRequest()
            assertEquals("/api/v2/torrents/pause", pause.path)
            assertTrue(pause.body.readUtf8().contains("hashes=abc%7Cdef"))
            val resume = mock.takeRequest()
            assertEquals("/api/v2/torrents/resume", resume.path)
        } finally {
            mock.shutdown()
        }
    }

    @Test
    fun removeSendsDeleteFilesFlag() = runBlocking {
        val mock = MockWebServer()
        mock.enqueue(MockResponse().setResponseCode(200).setBody(""))
        mock.start()
        try {
            QbittorrentApi.remove(serverFor(mock.port), listOf("abc"), deleteData = true)
            val body = mock.takeRequest().body.readUtf8()
            assertTrue(body.contains("hashes=abc"))
            assertTrue(body.contains("deleteFiles=true"))
        } finally {
            mock.shutdown()
        }
    }

    @Test
    fun addTorrentSendsSavePathAndPaused() = runBlocking {
        val mock = MockWebServer()
        mock.enqueue(MockResponse().setResponseCode(200).setBody("Ok."))
        mock.start()
        try {
            QbittorrentApi.addMagnet(serverFor(mock.port), "magnet:?xt=urn:btih:abc", "/pt", paused = true)
            val request = mock.takeRequest()
            assertEquals("/api/v2/torrents/add", request.path)
            val body = request.body.readUtf8()
            assertTrue(body.contains("urls=magnet"))
            assertTrue(body.contains("paused=true"))
            assertTrue(body.contains("savepath=%2Fpt"))
        } finally {
            mock.shutdown()
        }
    }

    @Test
    fun statsAggregateTransferAndTorrentStates() = runBlocking {
        val mock = MockWebServer()
        mock.enqueue(MockResponse().setResponseCode(200).setBody("""{"dl_info_speed":123,"up_info_speed":45}"""))
        mock.enqueue(MockResponse().setResponseCode(200).setBody("""
            [{"hash":"a","state":"downloading"},{"hash":"b","state":"stalledUP"},{"hash":"c","state":"pausedUP"}]
        """.trimIndent()))
        mock.start()
        try {
            val stats = QbittorrentApi.stats(serverFor(mock.port))
            assertEquals(123L, stats.downloadSpeed)
            assertEquals(45L, stats.uploadSpeed)
            assertEquals(3, stats.torrentCount)
            assertEquals(2, stats.activeTorrentCount)
            assertEquals(1, stats.pausedTorrentCount)
        } finally {
            mock.shutdown()
        }
    }

    @Test
    fun statsReadServerStateAndErrorCounts() = runBlocking {
        val mock = MockWebServer()
        mock.enqueue(MockResponse().setResponseCode(200).setBody("""{"dl_info_speed":10,"up_info_speed":20,"dl_info_data":1024,"up_info_data":2048}"""))
        mock.enqueue(MockResponse().setResponseCode(200).setBody("""
            [{"hash":"a","state":"downloading"},{"hash":"b","state":"error"},{"hash":"c","state":"missingFiles"}]
        """.trimIndent()))
        mock.enqueue(MockResponse().setResponseCode(200).setBody("""
            {"server_state":{"alltime_dl":9000,"alltime_ul":7000,"free_space_on_disk":5000,"queued_io_jobs":3}}
        """.trimIndent()))
        mock.start()
        try {
            val stats = QbittorrentApi.stats(serverFor(mock.port))
            assertEquals(9000L, stats.totalDownloaded)
            assertEquals(7000L, stats.totalUploaded)
            assertEquals(5000L, stats.freeSpace)
            assertEquals(3, stats.queuedIoCount)
            assertEquals(2, stats.errorTorrentCount)
        } finally {
            mock.shutdown()
        }
    }

    @Test
    fun statsFallBackToSessionTotalsWhenAlltimeMissing() = runBlocking {
        val mock = MockWebServer()
        mock.enqueue(MockResponse().setResponseCode(200).setBody("""{"dl_info_speed":10,"up_info_speed":20,"dl_info_data":1024,"up_info_data":2048}"""))
        mock.enqueue(MockResponse().setResponseCode(200).setBody("""[]"""))
        mock.enqueue(MockResponse().setResponseCode(200).setBody("""{"server_state":{"free_space_on_disk":5000}}"""))
        mock.start()
        try {
            val stats = QbittorrentApi.stats(serverFor(mock.port))
            assertEquals(1024L, stats.totalDownloaded)
            assertEquals(2048L, stats.totalUploaded)
        } finally {
            mock.shutdown()
        }
    }

    @Test
    fun statsDegradeWhenMainDataUnavailable() = runBlocking {
        val mock = MockWebServer()
        mock.enqueue(MockResponse().setResponseCode(200).setBody("""{"dl_info_speed":1}"""))
        mock.enqueue(MockResponse().setResponseCode(200).setBody("[]"))
        mock.enqueue(MockResponse().setResponseCode(404))
        mock.start()
        try {
            val stats = QbittorrentApi.stats(serverFor(mock.port))
            assertEquals(-1L, stats.freeSpace)
            assertEquals(-1, stats.queuedIoCount)
        } finally {
            mock.shutdown()
        }
    }

    @Test
    fun rejectsInvalidAddresses() {
        listOf(
            DownloaderServer(name = "", host = "127.0.0.1", protocol = DownloaderProtocol.QBITTORRENT),
            DownloaderServer(name = "qb", host = "", protocol = DownloaderProtocol.QBITTORRENT),
            DownloaderServer(name = "qb", host = "ftp://127.0.0.1", protocol = DownloaderProtocol.QBITTORRENT)
        ).forEach { server ->
            assertTrue("应拒绝 $server", runCatching { QbittorrentApi.validate(server) }.isFailure)
        }
    }

    @Test
    fun dispatchesByProtocol() {
        assertTrue(DownloaderApis.of(DownloaderProtocol.QBITTORRENT) === QbittorrentApi)
        assertTrue(DownloaderApis.of(DownloaderProtocol.TRANSMISSION) === TransmissionClient)
        assertTrue(
            DownloaderApis.of(DownloaderServer(protocol = DownloaderProtocol.QBITTORRENT)) === QbittorrentApi
        )
    }

    @Test
    fun sessionReadsVersionSpeedModeAndByteLimits() = runBlocking {
        val mock = MockWebServer()
        mock.enqueue(MockResponse().setResponseCode(200).setBody("""
            {"save_path":"/pt","dl_limit":100,"up_limit":200,"alt_dl_limit":300,"alt_up_limit":400,
             "scheduler_enabled":false,"use_alt_speed_limits":false}
        """.trimIndent()))
        mock.enqueue(MockResponse().setResponseCode(200).setBody("v4.6.7"))
        mock.enqueue(MockResponse().setResponseCode(200).setBody("1"))
        mock.start()
        try {
            val session = QbittorrentApi.session(serverFor(mock.port))
            assertEquals("v4.6.7", session.version)
            assertTrue(session.altSpeedEnabled)
            assertEquals(300L, session.altSpeedDown)
            assertEquals(400L, session.altSpeedUp)
            assertEquals(100L, session.speedLimitDown)
            assertEquals(200L, session.speedLimitUp)
            assertEquals("/api/v2/app/preferences", mock.takeRequest().path)
            assertEquals("/api/v2/app/version", mock.takeRequest().path)
            assertEquals("/api/v2/transfer/speedLimitsMode", mock.takeRequest().path)
        } finally {
            mock.shutdown()
        }
    }

    @Test
    fun sessionFailsWhenSpeedModeCannotBeRead() = runBlocking {
        val mock = MockWebServer()
        mock.enqueue(MockResponse().setResponseCode(200).setBody("""{"save_path":"/pt"}"""))
        mock.enqueue(MockResponse().setResponseCode(200).setBody("v4.6.7"))
        mock.enqueue(MockResponse().setResponseCode(500).setBody("boom"))
        mock.start()
        try {
            try {
                QbittorrentApi.session(serverFor(mock.port))
                fail("限速模式读取失败时应抛错")
            } catch (failure: DownloaderException) {
                assertTrue(failure.message.orEmpty().contains("HTTP 500"))
            }
        } finally {
            mock.shutdown()
        }
    }

    @Test
    fun torrentDetailMergesPropertiesInfoFilesTrackersAndPeers() = runBlocking {
        val mock = MockWebServer()
        val hash = "0123456789abcdef0123456789abcdef01234567"
        mock.enqueue(MockResponse().setResponseCode(200).setBody("""
            {"name":"Demo","total_size":1000,"save_path":"/pt","share_ratio":2.0,
             "total_uploaded":500,"total_downloaded":600,"created_by":"mktorrent"}
        """.trimIndent()))
        mock.enqueue(MockResponse().setResponseCode(200).setBody("""
            [{"hash":"$hash","name":"Demo","state":"downloading","progress":0.25,"total_size":1000,
              "dlspeed":100,"upspeed":50,"ratio":2.0,"eta":60,"num_seeds":3,"num_leechs":2,
              "added_on":100,"tags":"A, B"}]
        """.trimIndent()))
        mock.enqueue(MockResponse().setResponseCode(200).setBody("""
            [{"name":"Demo/file.bin","size":800,"progress":0.5,"priority":1}]
        """.trimIndent()))
        mock.enqueue(MockResponse().setResponseCode(200).setBody("""
            [{"url":"https://tracker.example/announce","status":2,"num_seeds":5,"num_leeches":6,"num_downloaded":7,"msg":"OK"}]
        """.trimIndent()))
        mock.enqueue(MockResponse().setResponseCode(200).setBody("""
            {"peers":{"1.2.3.4:5000":{"ip":"1.2.3.4","port":5000,"client":"qB","progress":0.5,
              "dl_speed":10,"up_speed":20,"flags":"EI"}}}
        """.trimIndent()))
        mock.start()
        try {
            val detail = QbittorrentApi.torrent(serverFor(mock.port), hash.uppercase())
            requireNotNull(detail)
            assertEquals("Demo", detail.name)
            assertEquals(0.25, detail.percentDone, 0.0001)
            assertEquals(1, detail.files?.size)
            assertEquals(1, detail.trackerStats?.size)
            assertEquals("tracker.example", detail.trackerStats?.first()?.host)
            assertEquals(1, detail.peers?.size)
            assertEquals("1.2.3.4", detail.peers?.first()?.address)
            assertTrue(detail.peers?.first()?.isEncrypted == true)
            assertEquals("/api/v2/torrents/properties?hash=$hash", mock.takeRequest().path)
            assertEquals("/api/v2/torrents/info?hash=$hash&hashes=$hash", mock.takeRequest().path)
            assertEquals("/api/v2/torrents/files?hash=$hash", mock.takeRequest().path)
            assertEquals("/api/v2/torrents/trackers?hash=$hash", mock.takeRequest().path)
            assertEquals("/api/v2/sync/torrentPeers?hash=$hash&rid=0", mock.takeRequest().path)
        } finally {
            mock.shutdown()
        }
    }

    @Test
    fun torrentRejectsMalformedHashWithoutRequest() = runBlocking {
        val mock = MockWebServer()
        mock.start()
        try {
            assertEquals(null, QbittorrentApi.torrent(serverFor(mock.port), "abc&hashes=x"))
            assertEquals(0, mock.requestCount)
        } finally {
            mock.shutdown()
        }
    }

    @Test
    fun setAltSpeedOnlyTogglesWhenModeDiffers() = runBlocking {
        val mock = MockWebServer()
        mock.enqueue(MockResponse().setResponseCode(200).setBody("0"))
        mock.enqueue(MockResponse().setResponseCode(200).setBody("0"))
        mock.enqueue(MockResponse().setResponseCode(200).setBody(""))
        mock.enqueue(MockResponse().setResponseCode(200).setBody("1"))
        mock.start()
        try {
            QbittorrentApi.setAltSpeed(serverFor(mock.port), enabled = false)
            assertEquals("/api/v2/transfer/speedLimitsMode", mock.takeRequest().path)
            assertEquals(1, mock.requestCount)
            QbittorrentApi.setAltSpeed(serverFor(mock.port), enabled = true)
            assertEquals("/api/v2/transfer/speedLimitsMode", mock.takeRequest().path)
            assertEquals("/api/v2/transfer/toggleSpeedLimitsMode", mock.takeRequest().path)
        } finally {
            mock.shutdown()
        }
    }

    @Test
    fun setAltSpeedConfirmsModeAfterToggle() = runBlocking {
        val mock = MockWebServer()
        mock.enqueue(MockResponse().setResponseCode(200).setBody("0"))
        mock.enqueue(MockResponse().setResponseCode(200).setBody(""))
        mock.enqueue(MockResponse().setResponseCode(200).setBody("1"))
        mock.start()
        try {
            QbittorrentApi.setAltSpeed(serverFor(mock.port), enabled = true)
            assertEquals("/api/v2/transfer/speedLimitsMode", mock.takeRequest().path)
            assertEquals("/api/v2/transfer/toggleSpeedLimitsMode", mock.takeRequest().path)
            assertEquals("/api/v2/transfer/speedLimitsMode", mock.takeRequest().path)
        } finally {
            mock.shutdown()
        }
    }

    @Test
    fun setAltSpeedFailsWhenModeNeverChanges() = runBlocking {
        val mock = MockWebServer()
        repeat(3) {
            mock.enqueue(MockResponse().setResponseCode(200).setBody("0"))
            mock.enqueue(MockResponse().setResponseCode(200).setBody(""))
        }
        mock.enqueue(MockResponse().setResponseCode(200).setBody("0"))
        mock.start()
        try {
            try {
                QbittorrentApi.setAltSpeed(serverFor(mock.port), enabled = true)
                fail("模式未切换成功时应抛错")
            } catch (failure: DownloaderException) {
                assertTrue(failure.message.orEmpty().contains("切换未生效"))
            }
        } finally {
            mock.shutdown()
        }
    }

    @Test
    fun speedLimitSendsBytesPerSecond() = runBlocking {
        val mock = MockWebServer()
        mock.enqueue(MockResponse().setResponseCode(200).setBody(""))
        mock.start()
        try {
            QbittorrentApi.setSpeedLimit(serverFor(mock.port), 100, 200, enabled = true)
            val request = mock.takeRequest()
            assertEquals("/api/v2/app/setPreferences", request.path)
            val body = request.body.readUtf8()
            assertTrue(body.contains("dl_limit"))
            assertTrue(body.contains("102400"))
            assertTrue(body.contains("204800"))
        } finally {
            mock.shutdown()
        }
    }

    @Test
    fun setSpeedLimitsWritesAllValuesInOnePreferenceRequestThenConfirmsMode() = runBlocking {
        val mock = MockWebServer()
        mock.enqueue(MockResponse().setResponseCode(200).setBody(""))
        mock.enqueue(MockResponse().setResponseCode(200).setBody("0"))
        mock.enqueue(MockResponse().setResponseCode(200).setBody(""))
        mock.enqueue(MockResponse().setResponseCode(200).setBody("1"))
        mock.start()
        try {
            QbittorrentApi.setSpeedLimits(serverFor(mock.port), true, 300, 120, 80, 40)
            val prefs = mock.takeRequest()
            assertEquals("/api/v2/app/setPreferences", prefs.path)
            val body = prefs.body.readUtf8()
            assertTrue(body.contains("307200"))
            assertTrue(body.contains("122880"))
            assertTrue(body.contains("81920"))
            assertTrue(body.contains("40960"))
            assertEquals("/api/v2/transfer/speedLimitsMode", mock.takeRequest().path)
            assertEquals("/api/v2/transfer/toggleSpeedLimitsMode", mock.takeRequest().path)
            assertEquals("/api/v2/transfer/speedLimitsMode", mock.takeRequest().path)
        } finally {
            mock.shutdown()
        }
    }

    @Test
    fun altSpeedLimitsUseSetPreferencesJson() = runBlocking {
        val mock = MockWebServer()
        mock.enqueue(MockResponse().setResponseCode(200).setBody(""))
        mock.start()
        try {
            QbittorrentApi.setAltSpeedLimits(serverFor(mock.port), 300, 400)
            val request = mock.takeRequest()
            assertEquals("/api/v2/app/setPreferences", request.path)
            val body = request.body.readUtf8()
            assertTrue(body.contains("alt_dl_limit"))
            assertTrue(body.contains("307200"))
            assertTrue(body.contains("409600"))
        } finally {
            mock.shutdown()
        }
    }

    @Test
    fun loginFailBodyReportsCredentialError() = runBlocking {
        val mock = MockWebServer()
        mock.enqueue(MockResponse().setResponseCode(403))
        mock.enqueue(MockResponse().setResponseCode(200).setBody("Fails."))
        mock.start()
        try {
            try {
                QbittorrentApi.test(serverFor(mock.port, username = "admin"), passwordOverride = "wrong")
                fail("应抛出认证错误")
            } catch (failure: DownloaderException) {
                assertTrue(failure.message.orEmpty().contains("用户名或密码错误"))
            }
        } finally {
            mock.shutdown()
        }
    }

}
