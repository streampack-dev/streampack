/* Joseph B. Ottinger (C)2026 */
package dev.streampack.github.operation

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
import dev.streampack.github.entity.GitHubInstance
import dev.streampack.github.model.GitHubInstanceRequest
import dev.streampack.github.service.GitHubSecretRefStartupGuard
import dev.streampack.github.service.GitHubSubscriptionService
import org.springframework.messaging.Message
import org.springframework.stereotype.Component

/**
 * Handles `github instance add <url> [token]` (admin) and `github instance list` (anyone), for
 * GitHub Enterprise Server and other GitHub-compatible installations.
 */
@Component
class GitHubInstanceOperation(private val subscriptionService: GitHubSubscriptionService) :
    TranslatingOperation<GitHubInstanceRequest>(GitHubInstanceRequest::class) {
    private val commandMatcher =
        CommandPatternMatcher(
            listOf(
                CommandPattern(
                    name = "github_instance_add",
                    literals = listOf("github", "instance", "add"),
                    args =
                        listOf(
                            CommandArgSpec("url", StringArgType),
                            CommandArgSpec("token", StringArgType),
                        ),
                ),
                CommandPattern(
                    name = "github_instance_add",
                    literals = listOf("github", "instance", "add"),
                    args = listOf(CommandArgSpec("url", StringArgType)),
                ),
                CommandPattern(
                    name = "github_instance_list",
                    literals = listOf("github", "instance", "list"),
                ),
            )
        )

    override val priority: Int = 54
    override val addressed: Boolean = true
    override val operationGroup: String = "github"
    override val redactionRules = listOf(RedactionRule("github instance add", setOf(4)))

    override fun translate(payload: String, message: Message<*>): GitHubInstanceRequest? {
        val match = commandMatcher.match(payload) as? CommandMatchResult.Match ?: return null
        return when (match.patternName) {
            "github_instance_add" ->
                GitHubInstanceRequest.Add(
                    url = match.captures["url"] as String,
                    token = (match.captures["token"] as? String)?.ifBlank { null },
                )
            "github_instance_list" -> GitHubInstanceRequest.List
            else -> null
        }
    }

    override fun canHandle(payload: GitHubInstanceRequest, message: Message<*>): Boolean =
        when (payload) {
            is GitHubInstanceRequest.Add -> hasRole(message, Role.ADMIN)
            is GitHubInstanceRequest.List -> true
        }

    override fun handle(payload: GitHubInstanceRequest, message: Message<*>): OperationOutcome =
        when (payload) {
            is GitHubInstanceRequest.Add -> add(payload)
            is GitHubInstanceRequest.List -> list()
        }

    private fun add(request: GitHubInstanceRequest.Add): OperationOutcome =
        when (val outcome = subscriptionService.addInstance(request.url, request.token)) {
            is AddInstanceOutcome.Added ->
                OperationResult.Success(
                    "Added GitHub instance ${outcome.instance.host} (${outcome.instance.apiUrl})." +
                        " Register repositories with: github add owner/repo on ${outcome.instance.host}" +
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

    /**
     * A literal token is stored as given and externalized to an environment variable on the next
     * restart; tell the admin which variable to set so that restart succeeds.
     */
    private fun tokenExternalizationNote(token: String?, instance: GitHubInstance): String {
        if (token.isNullOrBlank() || SecretRef.parse(token.trim()).isEnvRef()) return ""
        val key = GitHubSecretRefStartupGuard.envKeyFor(instance)
        return " Token stored; set $key in the environment before the next restart, " +
            "which will externalize it."
    }
}
