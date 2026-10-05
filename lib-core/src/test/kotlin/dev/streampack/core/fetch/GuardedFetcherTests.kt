/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.fetch

import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** Fetching only public addresses, every redirect checked, the body capped (#128). */
class GuardedFetcherTests {
    private lateinit var server: HttpServer
    private lateinit var base: String

    /** Loopback allowed, as the test server is on it; everything else as in production. */
    private val local = FetchProperties(allowLoopback = true)

    @BeforeEach
    fun start() {
        server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.start()
        base = "http://127.0.0.1:${server.address.port}"
    }

    @AfterEach
    fun stop() {
        server.stop(0)
    }

    private fun respond(
        path: String,
        status: Int,
        body: String = "",
        vararg headers: Pair<String, String>,
    ) {
        server.createContext(path) { exchange ->
            headers.forEach { (k, v) -> exchange.responseHeaders.add(k, v) }
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
            if (bytes.isNotEmpty()) exchange.responseBody.use { it.write(bytes) }
            exchange.close()
        }
    }

    @Test
    fun `a public page is fetched, with its status, headers and body`() {
        respond(
            "/page",
            200,
            "<p>hello</p>",
            "Link" to "<https://e.example/wm>; rel=\"webmention\"",
        )

        val response = GuardedFetcher(local).get("$base/page")

        assertEquals(200, response.status)
        assertEquals("<p>hello</p>", response.body)
        assertEquals("<https://e.example/wm>; rel=\"webmention\"", response.header("Link"))
        assertFalse(response.truncated)
    }

    @Test
    fun `redirects are followed, and the final address reported`() {
        respond("/old", 301, "", "Location" to "/new")
        respond("/new", 200, "moved here")

        val response = GuardedFetcher(local).get("$base/old")

        assertEquals("moved here", response.body)
        assertEquals("/new", response.finalUri.path)
    }

    @Test
    fun `a redirect to a private or metadata address is refused`() {
        respond("/to-private", 302, "", "Location" to "http://10.0.0.1/admin")
        respond("/to-metadata", 302, "", "Location" to "http://169.254.169.254/latest/meta-data/")

        assertThrows<FetchRefused> { GuardedFetcher(local).get("$base/to-private") }
        assertThrows<FetchRefused> { GuardedFetcher(local).get("$base/to-metadata") }
    }

    @Test
    fun `loopback, private, link-local and unspecified addresses are refused by default`() {
        val fetcher = GuardedFetcher(FetchProperties())

        listOf(
                "http://127.0.0.1/",
                "http://localhost/",
                "http://[::1]/",
                "http://10.1.2.3/",
                "http://172.16.0.1/",
                "http://192.168.1.1/",
                "http://100.64.0.1/",
                "http://[fd00::1]/",
                "http://169.254.169.254/",
                "http://[fe80::1]/",
                "http://0.0.0.0/",
                "http://224.0.0.1/",
            )
            .forEach { url -> assertThrows<FetchRefused>(url) { fetcher.get(url) } }
    }

    @Test
    fun `a name that resolves to a private address is refused`() {
        val guard =
            AddressGuard(FetchProperties()) { listOf(InetAddress.getByName("192.168.0.10")) }

        assertThrows<FetchRefused> {
            GuardedFetcher(FetchProperties(), guard).get("http://intranet.example/")
        }
    }

    @Test
    fun `only http and https are fetched`() {
        val fetcher = GuardedFetcher(local)

        assertThrows<FetchRefused> { fetcher.get("file:///etc/passwd") }
        assertThrows<FetchRefused> { fetcher.get("ftp://example.com/") }
    }

    @Test
    fun `private addresses are allowed only when configured`() {
        val guard = AddressGuard(FetchProperties(allowPrivate = true))

        assertTrue(guard.allowed(InetAddress.getByName("10.0.0.1")))
        assertFalse(guard.allowed(InetAddress.getByName("127.0.0.1")))
        // Link-local, the metadata address among them, never is.
        assertFalse(guard.allowed(InetAddress.getByName("169.254.169.254")))
        guard.check(URI("http://10.0.0.1/"))
    }

    @Test
    fun `no more than the cap of a body is read`() {
        respond("/big", 200, "x".repeat(5000))

        val response = GuardedFetcher(local.copy(maxBytes = 1000)).get("$base/big")

        assertEquals(1000, response.body.length)
        assertTrue(response.truncated)
    }

    @Test
    fun `too many redirects fail`() {
        respond("/loop", 302, "", "Location" to "/loop")

        assertThrows<FetchFailed> { GuardedFetcher(local).get("$base/loop") }
    }

    @Test
    fun `a post sends its body and returns the response without following a redirect`() {
        server.createContext("/endpoint") { exchange ->
            val sent = exchange.requestBody.readAllBytes().decodeToString()
            val type = exchange.requestHeaders.getFirst("Content-Type")
            val reply = "$type|$sent".toByteArray()
            exchange.sendResponseHeaders(202, reply.size.toLong())
            exchange.responseBody.use { it.write(reply) }
        }

        val response =
            GuardedFetcher(local)
                .post("$base/endpoint", "application/x-www-form-urlencoded", "source=a&target=b")

        assertEquals(202, response.status)
        assertEquals("application/x-www-form-urlencoded|source=a&target=b", response.body)
    }

    @Test
    fun `a body is decoded in the charset it names`() {
        server.createContext("/latin") { exchange ->
            val bytes = "café".toByteArray(Charsets.ISO_8859_1)
            exchange.responseHeaders.add("Content-Type", "text/html; charset=ISO-8859-1")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }

        assertEquals("café", GuardedFetcher(local).get("$base/latin").body)
    }
}
