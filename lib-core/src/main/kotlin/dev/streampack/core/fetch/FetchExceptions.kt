/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.fetch

import java.net.URI

/** A fetch that didn't happen or didn't finish. */
sealed class FetchException(val uri: URI, message: String, cause: Throwable? = null) :
    RuntimeException("$uri: $message", cause)

/** The address may not be fetched: not http(s), or not a public address. Not worth retrying. */
class FetchRefused(uri: URI, reason: String) : FetchException(uri, "refused: $reason")

/**
 * The fetch failed on the way: no such host, a timeout, a dropped connection, too many redirects.
 */
class FetchFailed(uri: URI, reason: String, cause: Throwable? = null) :
    FetchException(uri, reason, cause)
