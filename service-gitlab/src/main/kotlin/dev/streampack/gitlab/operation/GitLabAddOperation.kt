/* Joseph B. Ottinger (C)2026 */
package dev.streampack.gitlab.operation

import dev.streampack.core.model.OperationOutcome
import dev.streampack.core.model.OperationResult
import dev.streampack.core.model.RedactionRule
import dev.streampack.core.model.Role
import dev.streampack.core.model.SecretRef
import dev.streampack.core.parser.ChoiceArgType
import dev.streampack.core.parser.CommandArgSpec
import dev.streampack.core.parser.CommandMatchResult
import dev.streampack.core.parser.CommandPattern
import dev.streampack.core.parser.CommandPatternMatcher
import dev.streampack.core.parser.StringArgType
import dev.streampack.core.service.TranslatingOperation
import dev.streampack.forge.command.InstanceSelector
import dev.streampack.forge.service.AddProjectOutcome
import dev.streampack.gitlab.config.ConditionalOnGitLab
import dev.streampack.gitlab.entity.GitLabProject
import dev.streampack.gitlab.model.AddProjectRequest
import dev.streampack.gitlab.service.GitLabSecretRefStartupGuard
import dev.streampack.gitlab.service.GitLabSubscriptionService
import org.springframework.messaging.Message
import org.springframework.stereotype.Component

/** Handles "gitlab add <group/project> [token] [on <host>]" and typed [AddProjectRequest]s */
@Component
@ConditionalOnGitLab
class GitLabAddOperation(private val subscriptionService: GitLabSubscriptionService) :
    TranslatingOperation<AddProjectRequest>(AddProjectRequest::class) {
    private val path = CommandArgSpec("path", StringArgType)
    private val token = CommandArgSpec("token", StringArgType)
    private val on = CommandArgSpec("on", ChoiceArgType(setOf("on")))
    private val host = CommandArgSpec("host", StringArgType)
    private val commandMatcher =
        CommandPatternMatcher(
            listOf(
                    listOf(path, token, on, host),
                    listOf(path, on, host),
                    listOf(path, token),
                    listOf(path),
                )
                .map {
                    CommandPattern(
                        name = "gitlab_add",
                        literals = listOf("gitlab", "add"),
                        args = it,
                    )
                }
        )

    override val priority: Int = 61
    override val addressed: Boolean = true
    override val operationGroup: String = "gitlab"
    override val redactionRules = listOf(RedactionRule("gitlab add", setOf(3)))

    override fun translate(payload: String, message: Message<*>): AddProjectRequest? {
        val match = commandMatcher.match(payload) as? CommandMatchResult.Match ?: return null
        return AddProjectRequest(
            path = match.captures["path"] as String,
            token = (match.captures["token"] as? String)?.ifBlank { null },
            host = (match.captures["host"] as? String)?.let { InstanceSelector.normalizeHost(it) },
        )
    }

    override fun canHandle(payload: AddProjectRequest, message: Message<*>): Boolean =
        hasRole(message, Role.ADMIN)

    override fun handle(payload: AddProjectRequest, message: Message<*>): OperationOutcome {
        val instance =
            subscriptionService.instanceFor(payload.host) ?: return unknownInstance(payload.host)
        return when (
            val outcome = subscriptionService.addProject(instance, payload.path, payload.token)
        ) {
            is AddProjectOutcome.Added ->
                OperationResult.Success(
                    "Watching ${outcome.project.displayName} " +
                        "(${outcome.issueCount} issues, " +
                        "${outcome.changeRequestCount} MRs, " +
                        "${outcome.releaseCount} releases)" +
                        tokenExternalizationNote(payload.token, outcome.project)
                )
            is AddProjectOutcome.AlreadyExists ->
                OperationResult.Success("Already watching ${outcome.project.displayName}")
            is AddProjectOutcome.InvalidIdentifier ->
                OperationResult.Error("Invalid project: ${outcome.reason}")
            is AddProjectOutcome.ApiFailed ->
                OperationResult.Error("Failed to access ${outcome.identifier}: ${outcome.reason}")
        }
    }

    private fun tokenExternalizationNote(token: String?, project: GitLabProject): String {
        if (token.isNullOrBlank() || SecretRef.parse(token.trim()).isEnvRef()) return ""
        val key = GitLabSecretRefStartupGuard.envKeyFor(project)
        return " Token stored; set $key in the environment before the next restart, " +
            "which will externalize it."
    }

    companion object {
        /** The error every `gitlab` command gives for an `on <host>` that is not registered */
        fun unknownInstance(host: String?): OperationResult.Error =
            OperationResult.Error(
                "No GitLab instance registered for $host. Register it with: gitlab instance add <url>"
            )
    }
}
