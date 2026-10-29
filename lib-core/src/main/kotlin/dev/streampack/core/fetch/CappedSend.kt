/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.fetch

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.nio.ByteBuffer
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.ExecutionException
import java.util.concurrent.Flow
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** A response body read up to a limit; [truncated] when the server sent more than that. */
internal class CappedBody(val bytes: ByteArray, val truncated: Boolean)

/**
 * Sends [request] and reads at most [maxBytes] of the body. The whole exchange, body included, must
 * end within [deadline]: [HttpRequest.timeout] covers only the wait for the headers, so a body that
 * stops arriving would otherwise hold the caller forever (#296).
 *
 * Throws as [HttpClient.send] does, with [HttpTimeoutException] when the deadline passes; the
 * exchange is then cancelled, which closes its connection.
 */
internal fun HttpClient.sendCapped(
    request: HttpRequest,
    maxBytes: Int,
    deadline: Duration,
): HttpResponse<CappedBody> {
    val future = sendAsync(request) { CappingSubscriber(maxBytes) }
    return try {
        future.get(deadline.toMillis(), TimeUnit.MILLISECONDS)
    } catch (e: TimeoutException) {
        future.cancel(true)
        throw HttpTimeoutException("no complete response within $deadline")
    } catch (e: InterruptedException) {
        future.cancel(true)
        throw e
    } catch (e: ExecutionException) {
        throw e.cause as? IOException ?: IOException(e.cause)
    }
}

/** Keeps the first [maxBytes] of a body, and cancels the rest of it. */
private class CappingSubscriber(private val maxBytes: Int) :
    HttpResponse.BodySubscriber<CappedBody> {
    private val result = CompletableFuture<CappedBody>()
    private val out = ByteArrayOutputStream()
    private lateinit var subscription: Flow.Subscription

    override fun getBody(): CompletionStage<CappedBody> = result

    override fun onSubscribe(subscription: Flow.Subscription) {
        this.subscription = subscription
        subscription.request(1)
    }

    override fun onNext(item: List<ByteBuffer>) {
        for (buffer in item) {
            val kept = ByteArray(minOf(maxBytes - out.size(), buffer.remaining()))
            buffer.get(kept)
            out.write(kept)
            if (buffer.hasRemaining()) {
                subscription.cancel()
                result.complete(CappedBody(out.toByteArray(), truncated = true))
                return
            }
        }
        subscription.request(1)
    }

    override fun onError(throwable: Throwable) {
        result.completeExceptionally(throwable)
    }

    override fun onComplete() {
        result.complete(CappedBody(out.toByteArray(), truncated = false))
    }
}
