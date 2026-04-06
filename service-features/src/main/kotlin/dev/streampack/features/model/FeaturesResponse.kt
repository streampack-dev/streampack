/* Joseph B. Ottinger (C)2026 */
package dev.streampack.features.model

/** Top-level response for GET /features describing backend capabilities */
data class FeaturesResponse(
    val siteName: String,
    val version: VersionInfo,
    val authentication: AuthenticationFeatures,
    val operationGroups: List<String>,
    val adapters: List<String>,
    val ai: Boolean,
    val anonymousSubmission: Boolean,
)

/** Build identity from BuildProperties and GitProperties */
data class VersionInfo(
    val name: String,
    val version: String?,
    val commit: String?,
    val branch: String?,
    val buildTime: String?,
)

/** Authentication method availability */
data class AuthenticationFeatures(val otp: Boolean, val otpFrom: String, val oidc: OidcFeatures?)

/** Per-provider OIDC availability */
data class OidcFeatures(val google: Boolean, val github: Boolean)
