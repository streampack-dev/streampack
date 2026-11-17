/* Joseph B. Ottinger (C)2026 */
package dev.streampack.test

import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress

/**
 * A stub HTTP server for tests, bound to the loopback address on a free port. The caller adds
 * contexts, starts it and stops it.
 *
 * Why not `InetSocketAddress(port)`, which binds every address: `just test` runs modules in
 * parallel JVMs, and macOS lets a second server bind `127.0.0.1` on the same port. Java sends
 * `localhost` to `127.0.0.1` first, so a request can reach another module's server. A server bound
 * to the loopback address itself keeps that address and port to itself.
 *
 * @return an unstarted server; its `address.port` is the port the system chose.
 */
fun loopbackHttpServer(): HttpServer =
    HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
