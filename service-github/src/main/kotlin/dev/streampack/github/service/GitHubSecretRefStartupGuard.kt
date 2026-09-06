/* Joseph B. Ottinger (C)2026 */
package dev.streampack.github.service

import dev.streampack.core.model.SecretRef
import dev.streampack.core.service.SecretRefEnvironment
import dev.streampack.core.service.SilentStartupException
import dev.streampack.forge.secret.SecretLookup
import dev.streampack.github.entity.GitHubInstance
import dev.streampack.github.entity.GitHubRepo
import dev.streampack.github.repository.GitHubInstanceRepository
import dev.streampack.github.repository.GitHubRepoRepository
import org.springframework.beans.factory.InitializingBean
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

/**
 * Enforces env-backed GitHub tokens, as the IRC and Slack guards do for theirs:
 * 1) Migrates literal DB values to `env://` references (see [envKeyFor])
 * 2) Fails startup until the variables for active repositories and instances are present
 *
 * Inactive repositories and instances are migrated but not required, since they are not polled.
 */
@Component
class GitHubSecretRefStartupGuard(
    private val repoRepository: GitHubRepoRepository,
    private val instanceRepository: GitHubInstanceRepository,
    private val secretLookup: SecretLookup,
    @Value("\${streampack.security.enforce-external-secrets:true}") private val enforce: Boolean,
) : InitializingBean {
    override fun afterPropertiesSet() {
        if (!enforce) return
        enforce { key -> secretLookup.lookup(key) }
    }

    internal fun enforce(lookup: (String) -> String?) {
        val migrationExports = mutableListOf<String>()
        val validationErrors = mutableListOf<String>()

        instanceRepository.findAll().forEach { instance ->
            check(
                current = instance.defaultToken ?: return@forEach,
                envKey = envKeyFor(instance),
                description = "GitHub instance '${instance.host}' default token",
                active = instance.active,
                lookup = lookup,
                migrationExports = migrationExports,
                validationErrors = validationErrors,
            ) { key ->
                instanceRepository.save(instance.copy(defaultToken = SecretRef.env(key)))
            }
        }

        repoRepository.findAll().forEach { repo ->
            check(
                current = repo.token ?: return@forEach,
                envKey = envKeyFor(repo),
                description = "GitHub repository '${repo.displayName}' token",
                active = repo.active,
                lookup = lookup,
                migrationExports = migrationExports,
                validationErrors = validationErrors,
            ) { key ->
                repoRepository.save(repo.copy(token = SecretRef.env(key)))
            }
        }

        if (migrationExports.isEmpty() && validationErrors.isEmpty()) return

        printFailure(migrationExports, validationErrors)
        throw SilentStartupException(
            "GitHub token externalization required. Add env vars shown above and restart."
        )
    }

    private fun check(
        current: SecretRef,
        envKey: String,
        description: String,
        active: Boolean,
        lookup: (String) -> String?,
        migrationExports: MutableList<String>,
        validationErrors: MutableList<String>,
        externalize: (String) -> Unit,
    ) {
        if (!current.isEnvRef()) {
            val literal = current.asStoredValue()
            if (literal.isBlank()) return
            migrationExports.add("export $envKey=${SecretRefEnvironment.shellQuote(literal)}")
            externalize(envKey)
            return
        }

        val key = current.envKeyOrNull()
        if (key == null) {
            validationErrors.add(
                "$description has invalid env reference '${current.asStoredValue()}'"
            )
        } else if (active && lookup(key).isNullOrBlank()) {
            validationErrors.add("$description requires environment variable $key")
        }
    }

    private fun printFailure(migrationExports: List<String>, validationErrors: List<String>) {
        System.err.println("============================================================")
        System.err.println("SECURITY STARTUP CHECK FAILED (GitHub secrets)")
        System.err.println("============================================================")
        if (migrationExports.isNotEmpty()) {
            System.err.println("Literal GitHub tokens were migrated to env:// references.")
            System.err.println("Add the following to your environment before restart:")
            migrationExports.forEach { System.err.println(it) }
        }
        if (validationErrors.isNotEmpty()) {
            System.err.println("Missing/invalid environment variables:")
            validationErrors.forEach { System.err.println("- $it") }
        }
        System.err.println("============================================================")
    }

    companion object {
        /**
         * The environment variable that holds a repository's token: `GITHUB_<OWNER>_<REPO>_TOKEN`
         * on github.com, so variables set before instances existed keep working, and
         * `GITHUB_<HOST>_<OWNER>_<REPO>_TOKEN` on any other instance.
         */
        fun envKeyFor(repo: GitHubRepo): String =
            if (repo.instance.isDefault) {
                SecretRefEnvironment.buildKey("GITHUB", repo.owner, repo.name, "TOKEN")
            } else {
                SecretRefEnvironment.buildKey(
                    "GITHUB",
                    repo.instance.host,
                    repo.owner,
                    repo.name,
                    "TOKEN",
                )
            }

        /** The environment variable that holds an instance's default token */
        fun envKeyFor(instance: GitHubInstance): String =
            SecretRefEnvironment.buildKey("GITHUB_INSTANCE", instance.host, "TOKEN")
    }
}
