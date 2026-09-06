/* Joseph B. Ottinger (C)2026 */
package dev.streampack.gitlab.operation

import dev.streampack.core.model.OperationOutcome
import dev.streampack.core.model.OperationResult
import dev.streampack.forge.command.InstanceSelector
import dev.streampack.forge.model.DeliveryMode
import dev.streampack.forge.service.RemoveProjectOutcome
import dev.streampack.forge.service.SubscriptionOutcome
import dev.streampack.gitlab.config.ConditionalOnGitLab
import dev.streampack.gitlab.entity.GitLabInstance
import dev.streampack.gitlab.service.GitLabSubscriptionService
import dev.streampack.polling.operation.PollingSourceManagementOperation
import org.springframework.stereotype.Component

/**
 * Handles gitlab list/subscribe/unsubscribe/subscriptions/remove commands. The identifier may end
 * in `on <host>` to address a project on an instance other than gitlab.com.
 */
@Component
@ConditionalOnGitLab
class GitLabManagementOperation(private val subscriptionService: GitLabSubscriptionService) :
    PollingSourceManagementOperation() {

    override val commandPrefix: String = "gitlab"
    override val priority: Int = 62
    override val operationGroup: String = "gitlab"

    override fun onList(): OperationOutcome {
        val projects = subscriptionService.listProjects()
        if (projects.isEmpty()) {
            return OperationResult.Success("No GitLab projects registered")
        }
        val lines =
            projects.joinToString("\n") { project ->
                val status = buildString {
                    if (!project.active) append(" [inactive]")
                    if (project.deliveryMode == DeliveryMode.WEBHOOK) append(" [webhook]")
                }
                "${project.displayName}$status"
            }
        return OperationResult.Success(lines)
    }

    override fun onSubscribe(identifier: String, destinationUri: String): OperationOutcome =
        withInstance(identifier) { instance, path ->
            when (val outcome = subscriptionService.subscribe(instance, path, destinationUri)) {
                is SubscriptionOutcome.Subscribed ->
                    OperationResult.Success("Subscribed to ${outcome.project.displayName}")
                is SubscriptionOutcome.AlreadySubscribed ->
                    OperationResult.Success("Already subscribed to ${outcome.project.displayName}")
                is SubscriptionOutcome.ProjectNotFound -> notFound(outcome.identifier, instance)
                is SubscriptionOutcome.Unsubscribed,
                is SubscriptionOutcome.NotSubscribed -> OperationResult.Error("Unexpected outcome")
            }
        }

    override fun onUnsubscribe(identifier: String, destinationUri: String): OperationOutcome =
        withInstance(identifier) { instance, path ->
            when (val outcome = subscriptionService.unsubscribe(instance, path, destinationUri)) {
                is SubscriptionOutcome.Unsubscribed ->
                    OperationResult.Success("Unsubscribed from ${outcome.project.displayName}")
                is SubscriptionOutcome.NotSubscribed ->
                    OperationResult.Error("Not subscribed to ${outcome.project.displayName}")
                is SubscriptionOutcome.ProjectNotFound -> notFound(outcome.identifier, instance)
                is SubscriptionOutcome.Subscribed,
                is SubscriptionOutcome.AlreadySubscribed ->
                    OperationResult.Error("Unexpected outcome")
            }
        }

    override fun onSubscriptions(destinationUri: String): OperationOutcome {
        val subscriptions = subscriptionService.listSubscriptions(destinationUri)
        if (subscriptions.isEmpty()) {
            return OperationResult.Success("No active subscriptions for this channel")
        }
        return OperationResult.Success(subscriptions.joinToString(", ") { it.project.displayName })
    }

    override fun onRemove(identifier: String): OperationOutcome =
        withInstance(identifier) { instance, path ->
            when (val outcome = subscriptionService.removeProject(instance, path)) {
                is RemoveProjectOutcome.Removed ->
                    OperationResult.Success(
                        "Removed ${outcome.project.displayName} " +
                            "(${outcome.subscriptionsDeactivated} subscriptions deactivated)"
                    )
                is RemoveProjectOutcome.ProjectNotFound -> notFound(outcome.identifier, instance)
                is RemoveProjectOutcome.AlreadyInactive ->
                    OperationResult.Success("${outcome.project.displayName} is already inactive")
            }
        }

    /** Splits a trailing `on <host>` off [identifier] and resolves the instance it names */
    private fun withInstance(
        identifier: String,
        block: (GitLabInstance, String) -> OperationOutcome,
    ): OperationOutcome {
        val selector = InstanceSelector.parse(identifier)
        val instance =
            subscriptionService.instanceFor(selector.host)
                ?: return GitLabAddOperation.unknownInstance(selector.host)
        return block(instance, selector.identifier)
    }

    private fun notFound(identifier: String, instance: GitLabInstance): OperationResult.Error {
        val where = if (instance.isDefault) "" else " on ${instance.host}"
        return OperationResult.Error("No registered project found for $identifier$where")
    }
}
