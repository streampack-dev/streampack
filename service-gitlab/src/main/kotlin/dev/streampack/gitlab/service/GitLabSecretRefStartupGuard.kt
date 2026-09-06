/* Joseph B. Ottinger (C)2026 */
package dev.streampack.gitlab.service

import dev.streampack.core.model.SecretRef
import dev.streampack.core.service.SecretRefEnvironment
import dev.streampack.core.service.SilentStartupException
import dev.streampack.forge.secret.SecretLookup
import dev.streampack.gitlab.config.ConditionalOnGitLab
import dev.streampack.gitlab.entity.GitLabInstance
import dev.streampack.gitlab.entity.GitLabProject
import dev.streampack.gitlab.repository.GitLabInstanceRepository
import dev.streampack.gitlab.repository.GitLabProjectRepository
import org.springframework.beans.factory.InitializingBean
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

/**
 * Enforces env-backed GitLab tokens, as the GitHub guard does for its tables:
 * 1) Migrates literal DB values to `env://` references (see [envKeyFor])
 * 2) Fails startup until the variables for active projects and instances are present
 *
 * Inactive projects and instances are migrated but not required, since they are not polled.
 */
@Component
@ConditionalOnGitLab
class GitLabSecretRefStartupGuard(
    private val projectRepository: GitLabProjectRepository,
    private val instanceRepository: GitLabInstanceRepository,
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
                description = "GitLab instance '${instance.host}' default token",
                active = instance.active,
                lookup = lookup,
                migrationExports = migrationExports,
                validationErrors = validationErrors,
            ) { key ->
                instanceRepository.save(instance.copy(defaultToken = SecretRef.env(key)))
            }
        }

        projectRepository.findAll().forEach { project ->
            check(
                current = project.token ?: return@forEach,
                envKey = envKeyFor(project),
                description = "GitLab project '${project.displayName}' token",
                active = project.active,
                lookup = lookup,
                migrationExports = migrationExports,
                validationErrors = validationErrors,
            ) { key ->
                projectRepository.save(project.copy(token = SecretRef.env(key)))
            }
        }

        if (migrationExports.isEmpty() && validationErrors.isEmpty()) return

        printFailure(migrationExports, validationErrors)
        throw SilentStartupException(
            "GitLab token externalization required. Add env vars shown above and restart."
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
        System.err.println("SECURITY STARTUP CHECK FAILED (GitLab secrets)")
        System.err.println("============================================================")
        if (migrationExports.isNotEmpty()) {
            System.err.println("Literal GitLab tokens were migrated to env:// references.")
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
         * The environment variable that holds a project's token: `GITLAB_<PATH>_TOKEN` on
         * gitlab.com and `GITLAB_<HOST>_<PATH>_TOKEN` elsewhere, with `/` in the path flattened to
         * `_`, e.g. `GITLAB_GROUP_SUB_PROJECT_TOKEN`.
         */
        fun envKeyFor(project: GitLabProject): String =
            if (project.instance.isDefault) {
                SecretRefEnvironment.buildKey("GITLAB", project.fullPath, "TOKEN")
            } else {
                SecretRefEnvironment.buildKey(
                    "GITLAB",
                    project.instance.host,
                    project.fullPath,
                    "TOKEN",
                )
            }

        /** The environment variable that holds an instance's default token */
        fun envKeyFor(instance: GitLabInstance): String =
            SecretRefEnvironment.buildKey("GITLAB_INSTANCE", instance.host, "TOKEN")
    }
}
