/* Joseph B. Ottinger (C)2026 */
package dev.streampack.specs.service

import com.sun.net.httpserver.HttpServer
import dev.streampack.specs.config.CveProperties
import java.net.InetSocketAddress
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Stands in for CVE.org, OSV.dev and Maven Central, answering with canned responses: [routes] maps
 * a path (`/api/cve/CVE-2021-44228`, `/v1/query`, `/solrsearch/select`) to what it answers, and
 * every request is kept in [requests]. A path with no route is a 404.
 */
class CveStubServer {
    data class Request(val method: String, val path: String, val query: String?, val body: String)

    data class Reply(
        val status: Int,
        val body: String = "",
        val delay: Duration = Duration.ZERO,
        val headers: Map<String, String> = emptyMap(),
    )

    val routes = mutableMapOf<String, (Request) -> Reply>()
    val requests = CopyOnWriteArrayList<Request>()

    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)

    init {
        server.createContext("/") { exchange ->
            val request =
                Request(
                    exchange.requestMethod,
                    exchange.requestURI.rawPath,
                    exchange.requestURI.query,
                    exchange.requestBody.readAllBytes().decodeToString(),
                )
            requests += request
            val reply = routes[request.path]?.invoke(request) ?: Reply(404, "{}")
            if (!reply.delay.isZero) Thread.sleep(reply.delay.toMillis())
            val bytes = reply.body.toByteArray()
            try {
                exchange.responseHeaders.add("Content-Type", "application/json")
                reply.headers.forEach { (name, value) -> exchange.responseHeaders.add(name, value) }
                exchange.sendResponseHeaders(
                    reply.status,
                    if (bytes.isEmpty()) -1 else bytes.size.toLong(),
                )
                if (bytes.isNotEmpty()) exchange.responseBody.use { it.write(bytes) }
            } catch (_: java.io.IOException) {
                // The client gave up (a timeout test); nothing to answer.
            } finally {
                exchange.close()
            }
        }
        server.executor = java.util.concurrent.Executors.newCachedThreadPool()
        server.start()
    }

    val baseUrl: String
        get() = "http://127.0.0.1:${server.address.port}"

    /** Properties pointing every upstream here. */
    fun properties(requestTimeout: Duration = Duration.ofSeconds(5)) =
        CveProperties(
            cveUrl = "$baseUrl/api/cve",
            osvUrl = "$baseUrl/v1/query",
            mavenSearchUrl = "$baseUrl/solrsearch/select",
            requestTimeout = requestTimeout,
            mavenSearchTimeout = requestTimeout,
        )

    /** Maven Central searches asked. */
    fun mavenSearches(): List<Request> = requests.filter { it.path == MAVEN }

    fun reset() {
        routes.clear()
        requests.clear()
    }

    fun stop() = server.stop(0)

    /** OSV queries, as their package name, ecosystem and version. */
    fun osvQueries(): List<String> = requests.filter { it.path == OSV }.map { it.body }

    companion object {
        const val OSV = "/v1/query"
        const val MAVEN = "/solrsearch/select"

        fun cvePath(id: String) = "/api/cve/$id"

        /** A canned upstream response from `src/test/resources/cve`. */
        fun fixture(name: String): String =
            requireNotNull(CveStubServer::class.java.getResource("/cve/$name")) { name }.readText()

        /** Maven Central's answer listing [coordinates], each with three versions. */
        fun mavenSearch(vararg coordinates: String): String {
            val docs =
                coordinates.joinToString(",") {
                    val (g, a) = it.split(':')
                    """{"id":"$g:$a","g":"$g","a":"$a","latestVersion":"1.0","versionCount":3}"""
                }
            return """{"responseHeader":{"status":0},"response":{"numFound":${coordinates.size},""" +
                """"start":0,"docs":[$docs]}}"""
        }

        const val NO_VULNS = "{}"
    }
}
