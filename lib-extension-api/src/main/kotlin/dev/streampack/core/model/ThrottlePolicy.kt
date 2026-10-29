/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.model

import java.time.Duration

/**
 * Declares a rate limit for an operation. Token bucket semantics: starts with [maxRequests] tokens,
 * replenishes one token every [window]/[maxRequests] interval.
 */
data class ThrottlePolicy(val maxRequests: Int, val window: Duration)
