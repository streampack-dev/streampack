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
data class AuthenticationFeatures(
    val otp: Boolean,
    val otpFrom: String,
    val oidc: OidcFeatures?,
    /** One-time-code channels this deployment can deliver on, for the sign-in form */
    val codeChannels: List<CodeChannelFeature> = emptyList(),
)

/** A one-time-code channel and, for chat channels, the registered servers to choose from */
data class CodeChannelFeature(val channel: String, val servers: List<String> = emptyList())

/** Per-provider OIDC availability */
data class OidcFeatures(val google: Boolean, val github: Boolean)
