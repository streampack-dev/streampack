/* Joseph B. Ottinger (C)2026 */
package dev.streampack.irc.service

import dev.streampack.core.model.SecretRef
import dev.streampack.core.service.SecretRefEnvironment
import dev.streampack.core.service.SilentStartupException
import dev.streampack.irc.repository.IrcNetworkRepository
import java.time.Instant
import org.springframework.beans.factory.InitializingBean
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.core.env.Environment
import org.springframework.stereotype.Component

/**
 * Enforces env-backed IRC credentials:
 * 1) Migrates literal DB values to `env://...`
 * 2) Fails startup until required environment variables are present
 */
@Component
@ConditionalOnProperty("streampack.irc.enabled", havingValue = "true")
class IrcSecretRefStartupGuard(
    private val networkRepository: IrcNetworkRepository,
    private val springEnvironment: Environment,
    @Value("\${streampack.security.enforce-external-secrets:true}") private val enforce: Boolean,
) : InitializingBean {
    private val logger = org.slf4j.LoggerFactory.getLogger(javaClass)

    override fun afterPropertiesSet() {
        if (!enforce) return
        enforce { key -> System.getenv(key) ?: springEnvironment.getProperty(key) }
    }

    internal fun enforce(secretLookup: (String) -> String?) {
        val migrationExports = mutableListOf<String>()
        val validationErrors = mutableListOf<String>()

        networkRepository.findByDeletedFalse().forEach { network ->
            val prefix = SecretRefEnvironment.buildKey("IRC", network.name)
            var updated = network
            var changed = false

            val accountResult =
                migrateOrValidate(
                    current = network.saslAccount,
                    envKey = SecretRefEnvironment.buildKey(prefix, "SASL_ACCOUNT"),
                    secretLookup = secretLookup,
                    description = "IRC network '${network.name}' SASL account",
                )
            accountResult.exportLine?.let { migrationExports.add(it) }
            accountResult.error?.let { validationErrors.add(it) }
            if (accountResult.newRef != network.saslAccount) {
                updated =
                    updated.copy(saslAccount = accountResult.newRef, updatedAt = Instant.now())
                changed = true
            }

            val passwordResult =
                migrateOrValidate(
                    current = network.saslPassword,
                    envKey = SecretRefEnvironment.buildKey(prefix, "SASL_PASSWORD"),
                    secretLookup = secretLookup,
                    description = "IRC network '${network.name}' SASL password",
                )
            passwordResult.exportLine?.let { migrationExports.add(it) }
            passwordResult.error?.let { validationErrors.add(it) }
            if (passwordResult.newRef != updated.saslPassword) {
                updated =
                    updated.copy(saslPassword = passwordResult.newRef, updatedAt = Instant.now())
                changed = true
            }

            if (changed) networkRepository.save(updated)
        }

        if (migrationExports.isNotEmpty() && validationErrors.isEmpty()) {
            /* Externalized this start with nothing missing: informational, not a failure */
            migrationExports.forEach { logger.info("Externalized literal credential to {}", it) }
            return
        }
        if (validationErrors.isEmpty()) return

        printFailure(migrationExports, validationErrors)
        throw SilentStartupException(
            "IRC secret externalization required. Add env vars shown above and restart."
        )
    }

    private fun migrateOrValidate(
        current: SecretRef?,
        envKey: String,
        secretLookup: (String) -> String?,
        description: String,
    ): GuardResult {
        if (current == null) return GuardResult(newRef = null)
        if (!current.isEnvRef()) {
            if (current.asStoredValue().isBlank()) return GuardResult(newRef = current)
            /* Never print the value: rewrite to a reference only once the variable exists */
            if (secretLookup(envKey).isNullOrBlank()) {
                return GuardResult(
                    newRef = current,
                    error =
                        "$description is stored as a literal; set $envKey (the value is in the " +
                            "database, or issue a new credential) so it can be externalized",
                )
            }
            return GuardResult(newRef = SecretRef.env(envKey), exportLine = envKey)
        }

        val key = current.envKeyOrNull()
        if (key == null) {
            return GuardResult(
                newRef = current,
                error = "$description has invalid env reference '${current.asStoredValue()}'",
            )
        }
        if (secretLookup(key).isNullOrBlank()) {
            return GuardResult(
                newRef = current,
                error = "$description requires environment variable $key",
            )
        }
        return GuardResult(newRef = current)
    }

    private fun printFailure(migrationExports: List<String>, validationErrors: List<String>) {
        System.err.println("============================================================")
        System.err.println("SECURITY STARTUP CHECK FAILED (IRC secrets)")
        System.err.println("============================================================")
        if (migrationExports.isNotEmpty()) {
            System.err.println("Literal credentials were externalized to these variables:")
            migrationExports.forEach { System.err.println("- $it") }
        }
        if (validationErrors.isNotEmpty()) {
            System.err.println("Missing/invalid environment variables:")
            validationErrors.forEach { System.err.println("- $it") }
        }
        System.err.println("============================================================")
    }

    private data class GuardResult(
        val newRef: SecretRef?,
        val exportLine: String? = null,
        val error: String? = null,
    )
}
