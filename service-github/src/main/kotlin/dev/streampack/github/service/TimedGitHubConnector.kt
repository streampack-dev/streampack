/* Joseph B. Ottinger (C)2026 */
package dev.streampack.github.service

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.SocketTimeoutException
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import org.kohsuke.github.connector.GitHubConnector
import org.kohsuke.github.connector.GitHubConnectorRequest
import org.kohsuke.github.connector.GitHubConnectorResponse

/**
 * A hub4j connector that gives every call a deadline (#219). hub4j's own connectors have none: a
 * response whose body stalls after its headers held the polling thread for good, and with it
 * polling for every project. The JDK's request timeout ends once headers arrive, so the whole
 * response, body included, is read here within [deadline], and the call fails with a
 * [SocketTimeoutException] when it isn't done in time.
 */
class TimedGitHubConnector(connectTimeout: Duration, private val deadline: Duration) :
    GitHubConnector {
    private val client = HttpClient.newBuilder().connectTimeout(connectTimeout).build()

    override fun send(request: GitHubConnectorRequest): GitHubConnectorResponse {
        val builder = HttpRequest.newBuilder(request.url().toURI()).timeout(deadline)
        request.allHeaders().forEach { (name, values) ->
            if (name != null && name.lowercase() !in RESTRICTED) {
                values.forEach { builder.header(name, it) }
            }
        }
        val bytes = if (request.hasBody()) request.body()?.readAllBytes() else null
        val body =
            bytes?.let(HttpRequest.BodyPublishers::ofByteArray)
                ?: HttpRequest.BodyPublishers.noBody()
        builder.method(request.method(), body)

        val future = client.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofByteArray())
        val response =
            try {
                future.get(deadline.toMillis(), TimeUnit.MILLISECONDS)
            } catch (e: TimeoutException) {
                future.cancel(true)
                throw SocketTimeoutException(
                    "${request.method()} ${request.url()} took over $deadline",
                )
            } catch (e: ExecutionException) {
                throw (e.cause as? java.io.IOException) ?: java.io.IOException(e.cause)
            } catch (e: InterruptedException) {
                future.cancel(true)
                Thread.currentThread().interrupt()
                throw java.io.InterruptedIOException("interrupted waiting for ${request.url()}")
            }
        return Response(request, response.statusCode(), response.headers().map(), response.body())
    }

    private class Response(
        request: GitHubConnectorRequest,
        status: Int,
        headers: Map<String, List<String>>,
        private val bytes: ByteArray,
    ) : GitHubConnectorResponse.ByteArrayResponse(request, status, headers) {
        override fun rawBodyStream(): InputStream = ByteArrayInputStream(bytes)
    }

    private companion object {
        /** Headers the JDK client sets itself and refuses from a caller */
        private val RESTRICTED = setOf("connection", "content-length", "expect", "host", "upgrade")
    }
}
