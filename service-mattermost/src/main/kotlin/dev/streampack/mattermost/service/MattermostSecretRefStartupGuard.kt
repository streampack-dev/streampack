/* Joseph B. Ottinger (C)2026 */
package dev.streampack.mattermost.service

import dev.streampack.core.model.SecretRef
import dev.streampack.core.service.SecretRefEnvironment
import dev.streampack.core.service.SilentStartupException
import dev.streampack.mattermost.entity.MattermostServer
import dev.streampack.mattermost.repository.MattermostServerRepository
import java.time.Instant
import org.springframework.beans.factory.InitializingBean
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.core.env.Environment
import org.springframework.stereotype.Component

/**
 * Enforces env-backed Mattermost tokens, as the Slack and IRC guards do for theirs:
 * 1) Migrates literal DB values to `env://MATTERMOST_<NAME>_TOKEN`
 * 2) Fails startup until the variables for registered servers are present
 */
@Component
@ConditionalOnProperty("streampack.mattermost.enabled", havingValue = "true")
class MattermostSecretRefStartupGuard(
    private val serverRepository: MattermostServerRepository,
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

        serverRepository.findByDeletedFalse().forEach { server ->
            val envKey = envKeyFor(server)
            val current = server.token
            if (!current.isEnvRef()) {
                if (current.asStoredValue().isBlank()) return@forEach
                /* Never print the value: rewrite to a reference only once the variable exists */
                if (secretLookup(envKey).isNullOrBlank()) {
                    validationErrors.add(
                        "Mattermost server '${server.name}' token is stored as a literal; set " +
                            "$envKey (the value is in the database, or issue a new token) so it " +
                            "can be externalized"
                    )
                } else {
                    serverRepository.save(
                        server.copy(token = SecretRef.env(envKey), updatedAt = Instant.now())
                    )
                    migrationExports.add(envKey)
                }
                return@forEach
            }
            val key = current.envKeyOrNull()
            if (key == null) {
                validationErrors.add(
                    "Mattermost server '${server.name}' token has invalid env reference '${current.asStoredValue()}'"
                )
            } else if (secretLookup(key).isNullOrBlank()) {
                validationErrors.add(
                    "Mattermost server '${server.name}' token requires environment variable $key"
                )
            }
        }

        if (migrationExports.isNotEmpty() && validationErrors.isEmpty()) {
            /* Externalized this start with nothing missing: informational, not a failure */
            migrationExports.forEach { logger.info("Externalized literal credential to {}", it) }
            return
        }
        if (validationErrors.isEmpty()) return

        printFailure(migrationExports, validationErrors)
        throw SilentStartupException(
            "Mattermost token externalization required. Add env vars shown above and restart."
        )
    }

    private fun printFailure(migrationExports: List<String>, validationErrors: List<String>) {
        System.err.println("============================================================")
        System.err.println("SECURITY STARTUP CHECK FAILED (Mattermost secrets)")
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

    companion object {
        /** The environment variable that holds a server's token, e.g. `MATTERMOST_WORK_TOKEN` */
        fun envKeyFor(server: MattermostServer): String =
            SecretRefEnvironment.buildKey("MATTERMOST", server.name, "TOKEN")
    }
}
