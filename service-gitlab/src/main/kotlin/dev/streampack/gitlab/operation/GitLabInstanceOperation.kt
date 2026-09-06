/* Joseph B. Ottinger (C)2026 */
package dev.streampack.gitlab.operation

import dev.streampack.core.model.OperationOutcome
import dev.streampack.core.model.OperationResult
import dev.streampack.core.model.RedactionRule
import dev.streampack.core.model.Role
import dev.streampack.core.model.SecretRef
import dev.streampack.core.parser.CommandArgSpec
import dev.streampack.core.parser.CommandMatchResult
import dev.streampack.core.parser.CommandPattern
import dev.streampack.core.parser.CommandPatternMatcher
import dev.streampack.core.parser.StringArgType
import dev.streampack.core.service.TranslatingOperation
import dev.streampack.forge.service.AddInstanceOutcome
import dev.streampack.gitlab.config.ConditionalOnGitLab
import dev.streampack.gitlab.entity.GitLabInstance
import dev.streampack.gitlab.model.GitLabInstanceRequest
import dev.streampack.gitlab.service.GitLabSecretRefStartupGuard
import dev.streampack.gitlab.service.GitLabSubscriptionService
import org.springframework.messaging.Message
import org.springframework.stereotype.Component

/** Handles `gitlab instance add <url> [token]` (admin) and `gitlab instance list` (anyone) */
@Component
@ConditionalOnGitLab
class GitLabInstanceOperation(private val subscriptionService: GitLabSubscriptionService) :
    TranslatingOperation<GitLabInstanceRequest>(GitLabInstanceRequest::class) {
    private val commandMatcher =
        CommandPatternMatcher(
            listOf(
                CommandPattern(
                    name = "gitlab_instance_add",
                    literals = listOf("gitlab", "instance", "add"),
                    args =
                        listOf(
                            CommandArgSpec("url", StringArgType),
                            CommandArgSpec("token", StringArgType),
                        ),
                ),
                CommandPattern(
                    name = "gitlab_instance_add",
                    literals = listOf("gitlab", "instance", "add"),
                    args = listOf(CommandArgSpec("url", StringArgType)),
                ),
                CommandPattern(
                    name = "gitlab_instance_list",
                    literals = listOf("gitlab", "instance", "list"),
                ),
            )
        )

    override val priority: Int = 60
    override val addressed: Boolean = true
    override val operationGroup: String = "gitlab"
    override val redactionRules = listOf(RedactionRule("gitlab instance add", setOf(4)))

    override fun translate(payload: String, message: Message<*>): GitLabInstanceRequest? {
        val match = commandMatcher.match(payload) as? CommandMatchResult.Match ?: return null
        return when (match.patternName) {
            "gitlab_instance_add" ->
                GitLabInstanceRequest.Add(
                    url = match.captures["url"] as String,
                    token = (match.captures["token"] as? String)?.ifBlank { null },
                )
            "gitlab_instance_list" -> GitLabInstanceRequest.List
            else -> null
        }
    }

    override fun canHandle(payload: GitLabInstanceRequest, message: Message<*>): Boolean =
        when (payload) {
            is GitLabInstanceRequest.Add -> hasRole(message, Role.ADMIN)
            is GitLabInstanceRequest.List -> true
        }

    override fun handle(payload: GitLabInstanceRequest, message: Message<*>): OperationOutcome =
        when (payload) {
            is GitLabInstanceRequest.Add -> add(payload)
            is GitLabInstanceRequest.List -> list()
        }

    private fun add(request: GitLabInstanceRequest.Add): OperationOutcome =
        when (val outcome = subscriptionService.addInstance(request.url, request.token)) {
            is AddInstanceOutcome.Added ->
                OperationResult.Success(
                    "Added GitLab instance ${outcome.instance.host} (${outcome.instance.apiUrl})." +
                        " Register projects with: gitlab add group/project on ${outcome.instance.host}" +
                        tokenExternalizationNote(request.token, outcome.instance)
                )
            is AddInstanceOutcome.AlreadyExists ->
                OperationResult.Success(
                    "Already registered: ${outcome.instance.host} (${outcome.instance.apiUrl})"
                )
            is AddInstanceOutcome.Invalid ->
                OperationResult.Error("Invalid instance ${outcome.host}: ${outcome.reason}")
        }

    private fun list(): OperationOutcome {
        val lines =
            subscriptionService.listInstances().joinToString("\n") { instance ->
                buildString {
                    append(instance.host).append(" -> ").append(instance.apiUrl)
                    if (instance.isDefault) append(" [default]")
                    if (instance.defaultToken != null) append(" [token]")
                    if (!instance.active) append(" [inactive]")
                }
            }
        return OperationResult.Success(lines)
    }

    private fun tokenExternalizationNote(token: String?, instance: GitLabInstance): String {
        if (token.isNullOrBlank() || SecretRef.parse(token.trim()).isEnvRef()) return ""
        val key = GitLabSecretRefStartupGuard.envKeyFor(instance)
        return " Token stored; set $key in the environment before the next restart, " +
            "which will externalize it."
    }
}
