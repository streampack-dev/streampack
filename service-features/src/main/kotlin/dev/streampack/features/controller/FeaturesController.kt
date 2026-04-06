/* Joseph B. Ottinger (C)2026 */
package dev.streampack.features.controller

import dev.streampack.core.config.StreampackProperties
import dev.streampack.core.service.Operation
import dev.streampack.core.service.ProtocolAdapter
import dev.streampack.features.model.AuthenticationFeatures
import dev.streampack.features.model.FeaturesResponse
import dev.streampack.features.model.OidcFeatures
import dev.streampack.features.model.VersionInfo
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.info.BuildProperties
import org.springframework.boot.info.GitProperties
import org.springframework.context.ApplicationContext
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.security.oauth2.client.registration.ClientRegistration
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Exposes build identity and classpath capabilities. The response is computed once at construction
 * and cached in memory. No runtime environment details (usernames, channels, keys, paths) are
 * exposed.
 */
@RestController
class FeaturesController(
    private val operations: List<Operation>,
    private val protocolAdapters: List<ProtocolAdapter>,
    private val applicationContext: ApplicationContext,
    @Autowired(required = false) private val buildProperties: BuildProperties?,
    @Autowired(required = false) private val gitProperties: GitProperties?,
    @Autowired(required = false)
    private val clientRegistrationRepository: ClientRegistrationRepository?,
    @Value("\${spring.application.name:}") private val applicationName: String,
    @Value("\${streampack.blog.anonymous-submission:false}")
    private val anonymousSubmission: Boolean,
    @Value("\${streampack.blog.site-name:Nevet}") private val siteName: String,
    private val streampackProperties: StreampackProperties,
) {

    private val cachedResponse: FeaturesResponse = buildResponse()

    @GetMapping("/features", produces = ["application/json"])
    fun getFeatures(): ResponseEntity<FeaturesResponse> =
        ResponseEntity.ok()
            .cacheControl(CacheControl.maxAge(java.time.Duration.ofHours(1)).cachePublic())
            .body(cachedResponse)

    private fun buildResponse(): FeaturesResponse {
        val version = buildVersionInfo()
        val authentication = buildAuthenticationFeatures()
        val operationGroups = operations.mapNotNull { it.operationGroup }.distinct().sorted()
        val adapters =
            buildList {
                    addAll(protocolAdapters.map { it.protocol.name.lowercase() })
                    // MCP is an HTTP JSON-RPC adapter, not a ProtocolAdapter implementation.
                    if (applicationContext.containsBean("mcpController")) add("mcp")
                }
                .distinct()
                .sorted()
        val ai = applicationContext.containsBean("aiService")

        return FeaturesResponse(
            siteName = siteName,
            version = version,
            authentication = authentication,
            operationGroups = operationGroups,
            adapters = adapters,
            ai = ai,
            anonymousSubmission = anonymousSubmission,
        )
    }

    private fun buildVersionInfo(): VersionInfo {
        val name = applicationName.ifBlank { buildProperties?.name ?: "nevet" }
        val version = buildProperties?.version
        val commit = gitProperties?.shortCommitId
        val branch = gitProperties?.branch
        val buildTime =
            (buildProperties?.time ?: gitProperties?.commitTime)?.let {
                BUILD_TIME_FORMAT.format(it)
            }

        return VersionInfo(
            name = name,
            version = version,
            commit = commit,
            branch = branch,
            buildTime = buildTime,
        )
    }

    private fun buildAuthenticationFeatures(): AuthenticationFeatures {
        val otp = operations.any { it::class.java.simpleName.startsWith("Otp") }
        val oidc = detectOidcProviders()

        return AuthenticationFeatures(
            otp = otp,
            otpFrom = streampackProperties.mail.from,
            oidc = oidc,
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun detectOidcProviders(): OidcFeatures? {
        val repo = clientRegistrationRepository ?: return null
        if (repo !is Iterable<*>) return null

        val registrations = (repo as Iterable<ClientRegistration>).toList()
        val registrationIds = registrations.map { it.registrationId.lowercase() }.toSet()

        return OidcFeatures(
            google = "google" in registrationIds,
            github = "github" in registrationIds,
        )
    }

    companion object {
        private val BUILD_TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneId.of("UTC"))
    }
}
