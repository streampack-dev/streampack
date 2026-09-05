/* Joseph B. Ottinger (C)2026 */
package dev.streampack.github.service

import dev.streampack.core.model.SecretRef
import dev.streampack.core.service.SecretRefEnvironment
import dev.streampack.core.service.SilentStartupException
import dev.streampack.forge.secret.SecretLookup
import dev.streampack.github.entity.GitHubRepo
import dev.streampack.github.repository.GitHubRepoRepository
import org.springframework.beans.factory.InitializingBean
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

/**
 * Enforces env-backed GitHub repository tokens, as the IRC and Slack guards do for theirs:
 * 1) Migrates literal DB values to `env://GITHUB_<OWNER>_<REPO>_TOKEN`
 * 2) Fails startup until the variables for active repositories are present
 *
 * Inactive repositories are migrated but not required, since they are not polled.
 */
@Component
class GitHubSecretRefStartupGuard(
    private val repoRepository: GitHubRepoRepository,
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

        repoRepository.findAll().forEach { repo ->
            val current = repo.token ?: return@forEach
            val envKey = envKeyFor(repo)
            val description = "GitHub repository '${repo.fullName()}' token"

            if (!current.isEnvRef()) {
                val literal = current.asStoredValue()
                if (literal.isBlank()) return@forEach
                migrationExports.add("export $envKey=${SecretRefEnvironment.shellQuote(literal)}")
                repoRepository.save(repo.copy(token = SecretRef.env(envKey)))
                return@forEach
            }

            val key = current.envKeyOrNull()
            if (key == null) {
                validationErrors.add(
                    "$description has invalid env reference '${current.asStoredValue()}'"
                )
            } else if (repo.active && lookup(key).isNullOrBlank()) {
                validationErrors.add("$description requires environment variable $key")
            }
        }

        if (migrationExports.isEmpty() && validationErrors.isEmpty()) return

        printFailure(migrationExports, validationErrors)
        throw SilentStartupException(
            "GitHub token externalization required. Add env vars shown above and restart."
        )
    }

    private fun printFailure(migrationExports: List<String>, validationErrors: List<String>) {
        System.err.println("============================================================")
        System.err.println("SECURITY STARTUP CHECK FAILED (GitHub secrets)")
        System.err.println("============================================================")
        if (migrationExports.isNotEmpty()) {
            System.err.println("Literal repository tokens were migrated to env:// references.")
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
         * The environment variable that holds a repository's token, e.g. `GITHUB_OWNER_REPO_TOKEN`
         */
        fun envKeyFor(repo: GitHubRepo): String =
            SecretRefEnvironment.buildKey("GITHUB", repo.owner, repo.name, "TOKEN")
    }
}
