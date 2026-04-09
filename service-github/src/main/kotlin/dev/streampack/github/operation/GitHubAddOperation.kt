/* Joseph B. Ottinger (C)2026 */
package dev.streampack.github.operation

import dev.streampack.core.model.OperationOutcome
import dev.streampack.core.model.OperationResult
import dev.streampack.core.model.RedactionRule
import dev.streampack.core.model.Role
import dev.streampack.core.parser.CommandArgSpec
import dev.streampack.core.parser.CommandMatchResult
import dev.streampack.core.parser.CommandPattern
import dev.streampack.core.parser.CommandPatternMatcher
import dev.streampack.core.parser.StringArgType
import dev.streampack.core.service.TranslatingOperation
import dev.streampack.github.model.AddRepoOutcome
import dev.streampack.github.model.AddRepoRequest
import dev.streampack.github.service.GitHubSubscriptionService
import org.springframework.messaging.Message
import org.springframework.stereotype.Component

/** Handles the "github add <owner/repo> [token]" text command and typed AddRepoRequest payloads */
@Component
class GitHubAddOperation(private val subscriptionService: GitHubSubscriptionService) :
    TranslatingOperation<AddRepoRequest>(AddRepoRequest::class) {
    private val commandMatcher =
        CommandPatternMatcher(
            listOf(
                CommandPattern(
                    name = "github_add",
                    literals = listOf("github", "add"),
                    args =
                        listOf(
                            CommandArgSpec("ownerRepo", StringArgType),
                            CommandArgSpec("token", StringArgType),
                        ),
                ),
                CommandPattern(
                    name = "github_add",
                    literals = listOf("github", "add"),
                    args = listOf(CommandArgSpec("ownerRepo", StringArgType)),
                ),
            )
        )

    override val priority: Int = 55
    override val addressed: Boolean = true
    override val operationGroup: String = "github"
    override val redactionRules = listOf(RedactionRule("github add", setOf(3)))

    override fun translate(payload: String, message: Message<*>): AddRepoRequest? {
        return when (val match = commandMatcher.match(payload)) {
            is CommandMatchResult.Match -> {
                val ownerRepo = match.captures["ownerRepo"] as String
                val token = match.captures["token"] as? String
                AddRepoRequest(ownerRepo = ownerRepo, token = token?.ifBlank { null })
            }
            else -> null
        }
    }

    override fun canHandle(payload: AddRepoRequest, message: Message<*>): Boolean {
        return hasRole(message, Role.ADMIN)
    }

    override fun handle(payload: AddRepoRequest, message: Message<*>): OperationOutcome {
        return when (val outcome = subscriptionService.addRepo(payload.ownerRepo, payload.token)) {
            is AddRepoOutcome.Added ->
                OperationResult.Success(
                    "Watching ${outcome.repo.fullName()} " +
                        "(${outcome.issueCount} issues, " +
                        "${outcome.prCount} PRs, " +
                        "${outcome.releaseCount} releases)"
                )
            is AddRepoOutcome.AlreadyExists ->
                OperationResult.Success("Already watching ${outcome.repo.fullName()}")
            is AddRepoOutcome.InvalidRepo ->
                OperationResult.Error("Invalid repository: ${outcome.reason}")
            is AddRepoOutcome.ApiFailed ->
                OperationResult.Error("Failed to access ${outcome.ownerRepo}: ${outcome.reason}")
        }
    }
}
