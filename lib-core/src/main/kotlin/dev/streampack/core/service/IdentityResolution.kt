/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.service

/** Result of an IdentityProvider validating a (serviceId, externalIdentifier) pair */
sealed class IdentityResolution {
    data class Valid(val serviceId: String, val externalIdentifier: String) : IdentityResolution()

    data class Invalid(val reason: String) : IdentityResolution()
}
