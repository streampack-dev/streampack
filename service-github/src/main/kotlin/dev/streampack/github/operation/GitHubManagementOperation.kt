/* Joseph B. Ottinger (C)2026 */
package dev.streampack.github.operation

import dev.streampack.core.model.OperationOutcome
import dev.streampack.core.model.OperationResult
import dev.streampack.forge.command.InstanceSelector
import dev.streampack.forge.model.DeliveryMode
import dev.streampack.forge.service.RemoveProjectOutcome
import dev.streampack.forge.service.SubscriptionOutcome
import dev.streampack.github.entity.GitHubInstance
import dev.streampack.github.service.GitHubSubscriptionService
import dev.streampack.polling.operation.PollingSourceManagementOperation
import org.springframework.stereotype.Component

/**
 * Handles github list/subscribe/unsubscribe/subscriptions/remove commands. The identifier may end
 * in `on <host>` to address a repository on an instance other than github.com.
 */
@Component
class GitHubManagementOperation(private val subscriptionService: GitHubSubscriptionService) :
    PollingSourceManagementOperation() {

    override val commandPrefix: String = "github"
    override val priority: Int = 56
    override val operationGroup: String = "github"

    override fun onList(): OperationOutcome {
        val repos = subscriptionService.listRepos()
        if (repos.isEmpty()) {
            return OperationResult.Success("No GitHub repositories registered")
        }
        val lines =
            repos.joinToString("\n") { repo ->
                val status = buildString {
                    if (!repo.active) append(" [inactive]")
                    if (repo.deliveryMode == DeliveryMode.WEBHOOK) append(" [webhook]")
                }
                "${repo.displayName}$status"
            }
        return OperationResult.Success(lines)
    }

    override fun onSubscribe(identifier: String, destinationUri: String): OperationOutcome {
        logger.debug(
            "onSubscribe called: identifier='{}', destination='{}'",
            identifier,
            destinationUri,
        )
        return withInstance(identifier) { instance, path ->
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
    }

    override fun onUnsubscribe(identifier: String, destinationUri: String): OperationOutcome {
        return withInstance(identifier) { instance, path ->
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
    }

    override fun onSubscriptions(destinationUri: String): OperationOutcome {
        val subscriptions = subscriptionService.listSubscriptions(destinationUri)
        if (subscriptions.isEmpty()) {
            return OperationResult.Success("No active subscriptions for this channel")
        }
        val lines = subscriptions.joinToString(", ") { it.repo.displayName }
        return OperationResult.Success(lines)
    }

    override fun onRemove(identifier: String): OperationOutcome {
        return withInstance(identifier) { instance, path ->
            when (val outcome = subscriptionService.removeRepo(instance, path)) {
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
    }

    /** Splits a trailing `on <host>` off [identifier] and resolves the instance it names */
    private fun withInstance(
        identifier: String,
        block: (GitHubInstance, String) -> OperationOutcome,
    ): OperationOutcome {
        val selector = InstanceSelector.parse(identifier)
        val instance =
            subscriptionService.instanceFor(selector.host)
                ?: return GitHubAddOperation.unknownInstance(selector.host)
        return block(instance, selector.identifier)
    }

    private fun notFound(identifier: String, instance: GitHubInstance): OperationResult.Error {
        val where = if (instance.isDefault) "" else " on ${instance.host}"
        return OperationResult.Error("No registered repository found for $identifier$where")
    }
}
