/* Joseph B. Ottinger (C)2026 */
package dev.streampack.gitlab.operation

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
import dev.streampack.gitlab.config.ConditionalOnGitLab
import dev.streampack.gitlab.config.GitLabProperties
import dev.streampack.gitlab.entity.GitLabInstance
import dev.streampack.gitlab.model.GitLabWebhookEnableRequest
import dev.streampack.gitlab.service.GitLabWebhookAdminService
import dev.streampack.gitlab.service.GitLabWebhookEnableOutcome
import dev.streampack.polling.service.EgressNotifier
import org.springframework.messaging.Message
import org.springframework.stereotype.Component

/** Enables webhook delivery via "gitlab webhook [private] group/project [on <host>]" */
@Component
@ConditionalOnGitLab
class GitLabWebhookOperation(
    private val adminService: GitLabWebhookAdminService,
    private val notifier: EgressNotifier,
    properties: StreampackProperties,
    gitLabProperties: GitLabProperties,
) : TranslatingOperation<GitLabWebhookEnableRequest>(GitLabWebhookEnableRequest::class) {
    private val path = CommandArgSpec("path", StringArgType)
    private val on = CommandArgSpec("on", ChoiceArgType(setOf("on")))
    private val host = CommandArgSpec("host", StringArgType)
    private val commandMatcher =
        CommandPatternMatcher(
            listOf(
                CommandPattern(
                    "gitlab_webhook_private",
                    listOf("gitlab", "webhook", "private"),
                    listOf(path, on, host),
                ),
                CommandPattern(
                    "gitlab_webhook_private",
                    listOf("gitlab", "webhook", "private"),
                    listOf(path),
                ),
                CommandPattern(
                    "gitlab_webhook",
                    listOf("gitlab", "webhook"),
                    listOf(path, on, host),
                ),
                CommandPattern("gitlab_webhook", listOf("gitlab", "webhook"), listOf(path)),
            )
        )

    private val webhookBaseUrl =
        "${(gitLabProperties.webhookBaseUrl ?: properties.baseUrl).trimEnd('/')}/webhooks/gitlab"

    override val priority: Int = 63
    override val addressed: Boolean = true
    override val operationGroup: String = "gitlab"

    override fun translate(payload: String, message: Message<*>): GitLabWebhookEnableRequest? {
        val match = commandMatcher.match(payload) as? CommandMatchResult.Match ?: return null
        return GitLabWebhookEnableRequest(
            path = match.captures["path"] as String,
            privateMode = match.patternName == "gitlab_webhook_private",
            host = (match.captures["host"] as? String)?.let { InstanceSelector.normalizeHost(it) },
        )
    }

    override fun canHandle(payload: GitLabWebhookEnableRequest, message: Message<*>): Boolean =
        hasRole(message, Role.ADMIN)

    override fun handle(
        payload: GitLabWebhookEnableRequest,
        message: Message<*>,
    ): OperationOutcome {
        val instance =
            adminService.instanceFor(payload.host)
                ?: return GitLabAddOperation.unknownInstance(payload.host)
        return when (
            val outcome = adminService.enableWebhook(instance, payload.path, payload.privateMode)
        ) {
            is GitLabWebhookEnableOutcome.Enabled -> {
                val name = outcome.project.displayName
                sendSecret(message, name, outcome.secret)
                val note =
                    if (payload.privateMode) " Private mode skipped remote project validation."
                    else ""
                OperationResult.Success(
                    "Webhook enabled for $name. Configure GitLab to POST to ${webhookUrl(instance)} " +
                        "with the provided Secret token (sent to you directly) for Issues, " +
                        "Merge request, and Releases events. GitLab sends the token verbatim, " +
                        "so the URL must be HTTPS.$note"
                )
            }
            is GitLabWebhookEnableOutcome.ProjectInactive ->
                OperationResult.Error("${outcome.name} is inactive. Add or reactivate it first.")
            is GitLabWebhookEnableOutcome.InvalidProject -> OperationResult.Error(outcome.reason)
            is GitLabWebhookEnableOutcome.NotConfigured -> OperationResult.Error(outcome.reason)
            is GitLabWebhookEnableOutcome.ApiFailed ->
                OperationResult.Error("Failed to access ${outcome.path}: ${outcome.reason}")
        }
    }

    /** gitlab.com uses the bare route; every other instance gets its own */
    private fun webhookUrl(instance: GitLabInstance): String =
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
            "GitLab webhook secret token for $name: $secret - shown once. Enter it as the Secret token on GitLab now.",
            destination.encode(),
        )
    }
}
