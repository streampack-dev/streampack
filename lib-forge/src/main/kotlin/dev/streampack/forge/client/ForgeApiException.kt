/* Joseph B. Ottinger (C)2026 */
package dev.streampack.forge.client

/**
 * A forge API call failed for a reason other than "not found": transport error, rate limit, server
 * error. Fetch methods raise it instead of returning empty results so the due-batch poller can back
 * the project off; lookups still answer null for a missing project.
 */
class ForgeApiException(message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)
