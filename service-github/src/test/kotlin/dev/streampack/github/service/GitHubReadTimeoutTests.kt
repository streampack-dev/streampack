/* Joseph B. Ottinger (C)2026 */
package dev.streampack.github.service

import com.sun.net.httpserver.HttpServer
import dev.streampack.forge.client.ForgeApiException
import dev.streampack.github.config.GitHubProperties
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * A GitHub response that stalls after its headers ends the call within the read timeout (#219),
 * rather than holding the polling thread, and polling for every project, for good.
 */
class GitHubReadTimeoutTests {
    private val pool = Executors.newCachedThreadPool()
    private val server =
        HttpServer.create(InetSocketAddress(0), 0).apply {
            executor = pool
            start()
        }
    private val apiUrl = "http://localhost:${server.address.port}"
    private val client =
        GitHubApiClient(GitHubProperties(connectTimeoutSeconds = 1, readTimeoutSeconds = 1))

    @AfterEach
    fun tearDown() {
        server.stop(0)
        pool.shutdownNow()
    }

    /** Headers and the start of a body, then nothing: what hung the polling test */
    private fun stall(path: String, status: Int) {
        server.createContext(path) { exchange ->
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(status, 1000)
            exchange.responseBody.write("""{"message": """".toByteArray())
            exchange.responseBody.flush()
            Thread.sleep(30_000)
        }
    }

    private fun <T> withinSeconds(limit: Long, block: () -> T): T {
        val started = System.nanoTime()
        val result = block()
        val seconds = (System.nanoTime() - started) / 1_000_000_000.0
        assertTrue(seconds < limit, "took $seconds s")
        return result
    }

    @Test
    fun `a stalled body on an error answer fails within the timeout`() {
        stall("/repos/owner/repo", 500)
        assertFalse(withinSeconds(5) { client.validateRepo(apiUrl, "owner", "repo", null) })
    }

    @Test
    fun `a stalled body on a success answer fails within the timeout`() {
        stall("/repos/owner/repo", 200)
        assertThrows<ForgeApiException> {
            withinSeconds(5) { client.fetchIssues(apiUrl, "owner", "repo", null, 5) }
        }
    }

    @Test
    fun `an answer in time still reads as before`() {
        server.createContext("/repos/owner/repo") { exchange ->
            val json =
                """{"id": 1, "name": "repo", "full_name": "owner/repo", "default_branch": "trunk"}"""
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, json.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(json.toByteArray()) }
        }
        assertEquals("trunk", client.fetchDefaultBranch(apiUrl, "owner", "repo", null))
    }

    @Test
    fun `the Actions calls, through RestClient, also end within the timeout`() {
        stall("/repos/owner/repo/actions/runs", 200)
        runCatching {
            withinSeconds(5) {
                client.fetchWorkflowRuns(apiUrl, "owner", "repo", null, java.time.Instant.now())
            }
        }
            .onFailure { if (it is AssertionError) throw it }
    }
}
