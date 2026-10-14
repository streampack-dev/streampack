/* Joseph B. Ottinger (C)2026 */
package dev.streampack.dictionary

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.Executors

/**
 * A local HTTP server answering each path with a status and body, or not at all. Requests are
 * handled on a pool, so a path left hanging doesn't hold up the others.
 */
class StubServer : AutoCloseable {
    private val pool = Executors.newCachedThreadPool()
    private val server =
        HttpServer.create(InetSocketAddress(0), 0).apply {
            executor = pool
            start()
        }
    val url = "http://localhost:${server.address.port}"
    private val paths = mutableSetOf<String>()

    fun answer(path: String, status: Int, body: String = "") {
        forget(path)
        paths += path
        server.createContext(path) { exchange ->
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
            if (bytes.isNotEmpty()) exchange.responseBody.use { it.write(bytes) }
            exchange.close()
        }
    }

    /** Takes the request and never answers it, as dictionaryapi.dev's origin did (#202) */
    fun hang(path: String) {
        forget(path)
        paths += path
        server.createContext(path) { Thread.sleep(10_000) }
    }

    fun forget(path: String) {
        if (paths.remove(path)) server.removeContext(path)
    }

    fun clear() = paths.toList().forEach(::forget)

    override fun close() {
        server.stop(0)
        pool.shutdownNow()
    }
}
