/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.service

import com.sun.net.httpserver.HttpServer
import dev.streampack.core.fetch.FetchProperties
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.zip.GZIPOutputStream
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/** [HttpPageFetcher] gives up on a body that stops arriving, and still reads gzip (#296). */
class HttpPageFetcherStallTests {
    private lateinit var server: HttpServer
    private lateinit var base: String
    private val stalled = CountDownLatch(1)

    @BeforeEach
    fun start() {
        server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.executor = Executors.newCachedThreadPool()
        server.createContext("/slow") { exchange ->
            exchange.sendResponseHeaders(200, 100)
            exchange.responseBody.write(ByteArray(10) { 'x'.code.toByte() })
            exchange.responseBody.flush()
            stalled.await()
            exchange.close()
        }
        server.createContext("/gzip") { exchange ->
            val zipped =
                ByteArrayOutputStream().also { out ->
                    GZIPOutputStream(out).use { it.write("<title>zipped</title>".toByteArray()) }
                }
            exchange.responseHeaders.add("Content-Encoding", "gzip")
            exchange.sendResponseHeaders(200, zipped.size().toLong())
            exchange.responseBody.use { zipped.writeTo(it) }
        }
        server.start()
        base = "http://127.0.0.1:${server.address.port}"
    }

    @AfterEach
    fun stop() {
        stalled.countDown()
        server.stop(0)
    }

    @Test
    fun `a body that stops arriving fails within the read timeout`() {
        val fetcher = HttpPageFetcher(FetchProperties(readTimeout = Duration.ofMillis(300)))
        val started = System.nanoTime()
        val result = fetcher.fetchResult("$base/slow")

        assertThat(result.body).isNull()
        assertThat(result.warnings.single()).startsWith("Failed to fetch URL")
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(3))
    }

    @Test
    fun `a gzip body is unzipped`() {
        assertThat(HttpPageFetcher().fetch("$base/gzip")).isEqualTo("<title>zipped</title>")
    }
}
