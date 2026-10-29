/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.service

import com.sun.net.httpserver.HttpServer
import dev.streampack.core.fetch.FetchProperties
import java.net.InetAddress
import java.net.InetSocketAddress
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class HttpPageFetcherTests {
    private lateinit var server: HttpServer
    private var agent: String? = null

    @BeforeEach
    fun start() {
        server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/") { exchange ->
            agent = exchange.requestHeaders.getFirst("User-Agent")
            val body = "<title>t</title>".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
    }

    @AfterEach
    fun stop() {
        server.stop(0)
    }

    private fun url() = "http://127.0.0.1:${server.address.port}/"

    @Test
    fun `sends the configured page user agent (#292)`() {
        HttpPageFetcher(FetchProperties(pageUserAgent = "browser/2.0")).fetch(url())
        assertEquals("browser/2.0", agent)
    }

    @Test
    fun `by default sends a current Chrome's (#292)`() {
        HttpPageFetcher().fetch(url())
        assertTrue(agent!!.contains("Chrome/157.0.0.0"), agent)
    }
}
