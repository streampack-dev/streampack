/* Joseph B. Ottinger (C)2026 */
package dev.streampack.github.operation

import dev.streampack.core.config.StreampackProperties
import dev.streampack.core.model.OperationOutcome
import dev.streampack.core.model.OperationResult
import dev.streampack.core.model.Provenance
import dev.streampack.core.model.Role
import dev.streampack.core.parser.ChoiceArgType
import dev.streampack.core.parser.CommandArgSpec
import dev.streampack.core.parser.CommandMatchResult
import dev.streampack.core.parser.CommandPattern
import dev.streampack.core.parser.CommandPatternMatcher
import dev.streampack.core.parser.StringArgType
import dev.streampack.core.service.TranslatingOperation
import dev.streampack.forge.command.InstanceSelector
import dev.streampack.github.config.GitHubProperties
import dev.streampack.github.entity.GitHubInstance
import dev.streampack.github.model.GitHubWebhookEnableRequest
import dev.streampack.github.service.GitHubWebhookAdminService
import dev.streampack.github.service.WebhookEnableOutcome
import dev.streampack.polling.service.EgressNotifier
import org.springframework.messaging.Message
import org.springframework.stereotype.Component

/**
 * Enables webhook delivery for repositories via "github webhook [private] owner/repo [on <host>]"
 */
@Component
class GitHubWebhookOperation(
    private val adminService: GitHubWebhookAdminService,
    private val notifier: EgressNotifier,
    private val properties: StreampackProperties,
    private val gitHubProperties: GitHubProperties,
) : TranslatingOperation<GitHubWebhookEnableRequest>(GitHubWebhookEnableRequest::class) {
    private val ownerRepo = CommandArgSpec("ownerRepo", StringArgType)
    private val on = CommandArgSpec("on", ChoiceArgType(setOf("on")))
    private val host = CommandArgSpec("host", StringArgType)
    private val commandMatcher =
        CommandPatternMatcher(
            listOf(
                CommandPattern(
                    name = "github_webhook_private",
                    literals = listOf("github", "webhook", "private"),
                    args = listOf(ownerRepo, on, host),
                ),
                CommandPattern(
                    name = "github_webhook_private",
                    literals = listOf("github", "webhook", "private"),
                    args = listOf(ownerRepo),
                ),
                CommandPattern(
                    name = "github_webhook",
                    literals = listOf("github", "webhook"),
                    args = listOf(ownerRepo, on, host),
                ),
                CommandPattern(
                    name = "github_webhook",
                    literals = listOf("github", "webhook"),
                    args = listOf(ownerRepo),
                ),
            )
        )

    private val webhookBaseUrl =
        "${(gitHubProperties.webhookBaseUrl ?: properties.baseUrl).trimEnd('/')}/webhooks/github"

    override val priority: Int = 57
    override val addressed: Boolean = true
    override val operationGroup: String = "github"

    override fun translate(payload: String, message: Message<*>): GitHubWebhookEnableRequest? {
        val match = commandMatcher.match(payload) as? CommandMatchResult.Match ?: return null
        return GitHubWebhookEnableRequest(
            ownerRepo = match.captures["ownerRepo"] as String,
            privateMode = match.patternName == "github_webhook_private",
            host = (match.captures["host"] as? String)?.let { InstanceSelector.normalizeHost(it) },
        )
    }

    override fun canHandle(payload: GitHubWebhookEnableRequest, message: Message<*>): Boolean {
        return hasRole(message, Role.ADMIN)
    }

    override fun handle(
        payload: GitHubWebhookEnableRequest,
        message: Message<*>,
    ): OperationOutcome {
        val instance =
            adminService.instanceFor(payload.host)
                ?: return GitHubAddOperation.unknownInstance(payload.host)
        return when (
            val outcome =
                adminService.enableWebhook(instance, payload.ownerRepo, payload.privateMode)
        ) {
            is WebhookEnableOutcome.Enabled -> {
                val name = outcome.repo.displayName
                sendSecret(message, name, outcome.secret)
                val note =
                    if (payload.privateMode) {
                        " Private mode skipped remote repository validation."
                    } else {
                        ""
                    }
                OperationResult.Success(
                    "Webhook enabled for $name. Configure GitHub to POST to ${webhookUrl(instance)} with the provided secret.$note"
                )
            }
            is WebhookEnableOutcome.RepoInactive ->
                OperationResult.Error(
                    "${outcome.ownerRepo} is inactive. Add or reactivate it first."
                )
            is WebhookEnableOutcome.InvalidRepo -> OperationResult.Error(outcome.reason)
            is WebhookEnableOutcome.NotConfigured -> OperationResult.Error(outcome.reason)
            is WebhookEnableOutcome.ApiFailed ->
                OperationResult.Error("Failed to access ${outcome.ownerRepo}: ${outcome.reason}")
        }
    }

    /** github.com keeps the bare route existing hooks already use; other instances get their own */
    private fun webhookUrl(instance: GitHubInstance): String =
        if (instance.isDefault) webhookBaseUrl else "$webhookBaseUrl/${instance.id}"

    private fun sendSecret(message: Message<*>, name: String, secret: String) {
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
            "GitHub webhook secret for $name: $secret - this secret is shown once. Configure it on GitHub now.",
            destination.encode(),
        )
    }
}
