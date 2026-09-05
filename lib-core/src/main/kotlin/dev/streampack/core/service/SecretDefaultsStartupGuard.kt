/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.service

import dev.streampack.core.config.StreampackProperties
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.InitializingBean
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

/**
 * Refuses to start when the JWT signing secret is missing or is a well-known placeholder.
 *
 * The HMAC key is derived from `streampack.jwt.secret`. Before this guard, an unset `JWT_SECRET`
 * fell back to a documented placeholder, which made the signing key a public constant and let
 * anyone mint a SUPER_ADMIN token (issue #47).
 * - A placeholder value always fails, whatever `streampack.security.enforce-external-secrets` says.
 * - A blank value fails when enforcement is on. With enforcement off (local development) a blank
 *   secret is allowed and [JwtService] generates an ephemeral key.
 * - A short secret is accepted with a warning.
 */
@Component
class SecretDefaultsStartupGuard(
    private val properties: StreampackProperties,
    @Value("\${streampack.security.enforce-external-secrets:true}") private val enforce: Boolean,
) : InitializingBean {
    private val logger = LoggerFactory.getLogger(SecretDefaultsStartupGuard::class.java)

    override fun afterPropertiesSet() {
        enforce(properties.jwt.secret, enforce)
    }

    internal fun enforce(jwtSecret: String, enforceExternalSecrets: Boolean) {
        val errors = mutableListOf<String>()
        val trimmed = jwtSecret.trim()
        when {
            SecretPlaceholders.isPlaceholder(trimmed) ->
                errors.add(
                    "streampack.jwt.secret (JWT_SECRET) is set to the placeholder '$trimmed'. " +
                        "Anyone with the source can forge tokens with this value."
                )
            trimmed.isBlank() && enforceExternalSecrets ->
                errors.add("streampack.jwt.secret (JWT_SECRET) is required and is not set.")
            trimmed.isNotBlank() && trimmed.length < MIN_RECOMMENDED_LENGTH ->
                logger.warn(
                    "JWT_SECRET is only {} characters; use at least {} random characters.",
                    trimmed.length,
                    MIN_RECOMMENDED_LENGTH,
                )
        }
        if (errors.isEmpty()) return

        System.err.println("============================================================")
        System.err.println("SECURITY STARTUP CHECK FAILED (JWT secret)")
        System.err.println("============================================================")
        errors.forEach { System.err.println("- $it") }
        System.err.println("Set JWT_SECRET to a long random value, for example:")
        System.err.println("  export JWT_SECRET=\"$(openssl rand -base64 48)\"")
        System.err.println("============================================================")
        throw SilentStartupException("JWT secret configuration rejected. See messages above.")
    }

    companion object {
        const val MIN_RECOMMENDED_LENGTH = 32
    }
}
