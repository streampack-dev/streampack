/* Joseph B. Ottinger (C)2026 */
package dev.streampack.github.operation

import dev.streampack.core.config.StreampackProperties
import dev.streampack.core.model.OperationOutcome
import dev.streampack.core.model.OperationResult
import dev.streampack.core.model.Provenance
import dev.streampack.core.model.Role
import dev.streampack.core.parser.CommandArgSpec
import dev.streampack.core.parser.CommandMatchResult
import dev.streampack.core.parser.CommandPattern
import dev.streampack.core.parser.CommandPatternMatcher
import dev.streampack.core.parser.StringArgType
import dev.streampack.core.service.TranslatingOperation
import dev.streampack.github.config.GitHubProperties
import dev.streampack.github.model.GitHubWebhookEnableRequest
import dev.streampack.github.service.GitHubWebhookAdminService
import dev.streampack.github.service.WebhookEnableOutcome
import dev.streampack.polling.service.EgressNotifier
import org.springframework.messaging.Message
import org.springframework.stereotype.Component

/** Enables webhook delivery for repositories via "github webhook owner/repo" */
@Component
class GitHubWebhookOperation(
    private val adminService: GitHubWebhookAdminService,
    private val notifier: EgressNotifier,
    private val properties: StreampackProperties,
    private val gitHubProperties: GitHubProperties,
) : TranslatingOperation<GitHubWebhookEnableRequest>(GitHubWebhookEnableRequest::class) {
    private val commandMatcher =
        CommandPatternMatcher(
            listOf(
                CommandPattern(
                    name = "github_webhook_private",
                    literals = listOf("github", "webhook", "private"),
                    args = listOf(CommandArgSpec("ownerRepo", StringArgType)),
                ),
                CommandPattern(
                    name = "github_webhook",
                    literals = listOf("github", "webhook"),
                    args = listOf(CommandArgSpec("ownerRepo", StringArgType)),
                ),
            )
        )

    private val webhookUrl =
        "${(gitHubProperties.webhookBaseUrl ?: properties.baseUrl).trimEnd('/')}/webhooks/github"

    override val priority: Int = 57
    override val addressed: Boolean = true
    override val operationGroup: String = "github"

    override fun translate(payload: String, message: Message<*>): GitHubWebhookEnableRequest? {
        return when (val match = commandMatcher.match(payload)) {
            is CommandMatchResult.Match ->
                GitHubWebhookEnableRequest(
                    ownerRepo = match.captures["ownerRepo"] as String,
                    privateMode = match.patternName == "github_webhook_private",
                )
            else -> null
        }
    }

    override fun canHandle(payload: GitHubWebhookEnableRequest, message: Message<*>): Boolean {
        return hasRole(message, Role.ADMIN)
    }

    override fun handle(
        payload: GitHubWebhookEnableRequest,
        message: Message<*>,
    ): OperationOutcome {
        return when (
            val outcome = adminService.enableWebhook(payload.ownerRepo, payload.privateMode)
        ) {
            is WebhookEnableOutcome.Enabled -> {
                sendSecret(message, outcome.ownerRepo, outcome.secret)
                val note =
                    if (payload.privateMode) {
                        " Private mode skipped remote repository validation."
                    } else {
                        ""
                    }
                OperationResult.Success(
                    "Webhook enabled for ${outcome.ownerRepo}. Configure GitHub to POST to $webhookUrl with the provided secret.$note"
                )
            }
            is WebhookEnableOutcome.RepoInactive ->
                OperationResult.Error(
                    "${outcome.ownerRepo} is inactive. Add or reactivate it first."
                )
            is WebhookEnableOutcome.InvalidRepo -> OperationResult.Error(outcome.reason)
            is WebhookEnableOutcome.ApiFailed ->
                OperationResult.Error("Failed to access ${outcome.ownerRepo}: ${outcome.reason}")
        }
    }

    private fun sendSecret(message: Message<*>, ownerRepo: String, secret: String) {
        val provenance = message.headers[Provenance.HEADER] as? Provenance ?: return
        val directTarget =
            (message.headers["nick"] as? String) ?: provenance.user?.username ?: provenance.replyTo
        val destination =
            Provenance(
                protocol = provenance.protocol,
                serviceId = provenance.serviceId,
                replyTo = directTarget,
            )
        notifier.send(
            "GitHub webhook secret for $ownerRepo: $secret - this secret is shown once. Configure it on GitHub now.",
            destination.encode(),
        )
    }
}
