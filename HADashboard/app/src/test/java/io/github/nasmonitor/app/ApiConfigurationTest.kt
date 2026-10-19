package io.github.nasmonitor.app

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiConfigurationTest {
    @Test
    fun normalizesSupportedServerOrigins() {
        assertEquals("https://example.com:23245", ApiClient.normalizeBaseUrl("  https://example.com:23245///  "))
        assertEquals("http://192.0.2.12", ApiClient.normalizeBaseUrl("http://192.0.2.12/"))
    }

    @Test
    fun rejectsUnsupportedOrNonOriginAddresses() {
        val invalid = listOf(
            "example.com",
            "ftp://example.com",
            "https://example.com/api",
            "https://user@example.com",
            "https://example.com?debug=1",
            "http://example.com:99999"
        )
        invalid.forEach { url ->
            assertTrue("应拒绝 $url", runCatching { ApiClient.normalizeBaseUrl(url) }.isFailure)
        }
    }

    @Test
    fun healthProbeFallsBackToApiHealth() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        server.start()
        try {
            val baseUrl = server.url("").toString()
            assertEquals(baseUrl.trimEnd('/'), ApiClient.verifyServerReachable(baseUrl))
            assertEquals("/health", server.takeRequest().path)
            assertEquals("/api/health", server.takeRequest().path)
        } finally {
            server.shutdown()
        }
    }
}
