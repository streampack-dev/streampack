/* Joseph B. Ottinger (C)2026 */
package dev.streampack.github.operation

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
import dev.streampack.github.entity.GitHubRepo
import dev.streampack.github.model.AddRepoRequest
import dev.streampack.github.service.GitHubSecretRefStartupGuard
import dev.streampack.github.service.GitHubSubscriptionService
import org.springframework.messaging.Message
import org.springframework.stereotype.Component

/**
 * Handles the "github add <owner/repo> [token] [on <host>]" text command and typed [AddRepoRequest]
 * payloads
 */
@Component
class GitHubAddOperation(private val subscriptionService: GitHubSubscriptionService) :
    TranslatingOperation<AddRepoRequest>(AddRepoRequest::class) {
    private val ownerRepo = CommandArgSpec("ownerRepo", StringArgType)
    private val token = CommandArgSpec("token", StringArgType)
    private val on = CommandArgSpec("on", ChoiceArgType(setOf("on")))
    private val host = CommandArgSpec("host", StringArgType)
    private val commandMatcher =
        CommandPatternMatcher(
            listOf(
                    listOf(ownerRepo, token, on, host),
                    listOf(ownerRepo, on, host),
                    listOf(ownerRepo, token),
                    listOf(ownerRepo),
                )
                .map {
                    CommandPattern(
                        name = "github_add",
                        literals = listOf("github", "add"),
                        args = it,
                    )
                }
        )

    override val priority: Int = 55
    override val addressed: Boolean = true
    override val operationGroup: String = "github"
    override val redactionRules = listOf(RedactionRule("github add", setOf(3)))

    override fun translate(payload: String, message: Message<*>): AddRepoRequest? {
        val match = commandMatcher.match(payload) as? CommandMatchResult.Match ?: return null
        return AddRepoRequest(
            ownerRepo = match.captures["ownerRepo"] as String,
            token = (match.captures["token"] as? String)?.ifBlank { null },
            host = (match.captures["host"] as? String)?.let { InstanceSelector.normalizeHost(it) },
        )
    }

    override fun canHandle(payload: AddRepoRequest, message: Message<*>): Boolean {
        return hasRole(message, Role.ADMIN)
    }

    override fun handle(payload: AddRepoRequest, message: Message<*>): OperationOutcome {
        val instance =
            subscriptionService.instanceFor(payload.host) ?: return unknownInstance(payload.host)
        return when (
            val outcome = subscriptionService.addRepo(instance, payload.ownerRepo, payload.token)
        ) {
            is AddProjectOutcome.Added ->
                OperationResult.Success(
                    "Watching ${outcome.project.displayName} " +
                        "(${outcome.issueCount} issues, " +
                        "${outcome.changeRequestCount} PRs, " +
                        "${outcome.releaseCount} releases)" +
                        tokenExternalizationNote(payload.token, outcome.project)
                )
            is AddProjectOutcome.AlreadyExists ->
                OperationResult.Success("Already watching ${outcome.project.displayName}")
            is AddProjectOutcome.InvalidIdentifier ->
                OperationResult.Error("Invalid repository: ${outcome.reason}")
            is AddProjectOutcome.ApiFailed ->
                OperationResult.Error("Failed to access ${outcome.identifier}: ${outcome.reason}")
        }
    }

    /**
     * A literal token is stored as given and externalized to an environment variable on the next
     * restart; tell the admin which variable to set so that restart succeeds.
     */
    private fun tokenExternalizationNote(token: String?, repo: GitHubRepo): String {
        if (token.isNullOrBlank() || SecretRef.parse(token.trim()).isEnvRef()) return ""
        val key = GitHubSecretRefStartupGuard.envKeyFor(repo)
        return " Token stored; set $key in the environment before the next restart, " +
            "which will externalize it."
    }

    companion object {
        /** The error every `github` command gives for an `on <host>` that is not registered */
        fun unknownInstance(host: String?): OperationResult.Error =
            OperationResult.Error(
                "No GitHub instance registered for $host. Register it with: github instance add <url>"
            )
    }
}
