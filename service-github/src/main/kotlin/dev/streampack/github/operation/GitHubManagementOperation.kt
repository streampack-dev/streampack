/* Joseph B. Ottinger (C)2026 */
package dev.streampack.github.operation

import dev.streampack.core.model.OperationOutcome
import dev.streampack.core.model.OperationResult
import dev.streampack.forge.model.DeliveryMode
import dev.streampack.forge.service.RemoveProjectOutcome
import dev.streampack.forge.service.SubscriptionOutcome
import dev.streampack.github.service.GitHubSubscriptionService
import dev.streampack.polling.operation.PollingSourceManagementOperation
import org.springframework.stereotype.Component

/** Handles github list/subscribe/unsubscribe/subscriptions/remove commands */
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
                "${repo.fullName()}$status"
            }
        return OperationResult.Success(lines)
    }

    override fun onSubscribe(identifier: String, destinationUri: String): OperationOutcome {
        logger.debug(
            "onSubscribe called: identifier='{}', destination='{}'",
            identifier,
            destinationUri,
        )
        return when (val outcome = subscriptionService.subscribe(identifier, destinationUri)) {
            is SubscriptionOutcome.Subscribed ->
                OperationResult.Success("Subscribed to ${outcome.project.fullName()}")
            is SubscriptionOutcome.AlreadySubscribed ->
                OperationResult.Success("Already subscribed to ${outcome.project.fullName()}")
            is SubscriptionOutcome.ProjectNotFound ->
                OperationResult.Error("No registered repository found for ${outcome.identifier}")
            is SubscriptionOutcome.Unsubscribed,
            is SubscriptionOutcome.NotSubscribed -> OperationResult.Error("Unexpected outcome")
        }
    }

    override fun onUnsubscribe(identifier: String, destinationUri: String): OperationOutcome {
        return when (val outcome = subscriptionService.unsubscribe(identifier, destinationUri)) {
            is SubscriptionOutcome.Unsubscribed ->
                OperationResult.Success("Unsubscribed from ${outcome.project.fullName()}")
            is SubscriptionOutcome.NotSubscribed ->
                OperationResult.Error("Not subscribed to ${outcome.project.fullName()}")
            is SubscriptionOutcome.ProjectNotFound ->
                OperationResult.Error("No registered repository found for ${outcome.identifier}")
            is SubscriptionOutcome.Subscribed,
            is SubscriptionOutcome.AlreadySubscribed -> OperationResult.Error("Unexpected outcome")
        }
    }

    override fun onSubscriptions(destinationUri: String): OperationOutcome {
        val subscriptions = subscriptionService.listSubscriptions(destinationUri)
        if (subscriptions.isEmpty()) {
            return OperationResult.Success("No active subscriptions for this channel")
        }
        val lines = subscriptions.joinToString(", ") { it.repo.fullName() }
        return OperationResult.Success(lines)
    }

    override fun onRemove(identifier: String): OperationOutcome {
        return when (val outcome = subscriptionService.removeRepo(identifier)) {
            is RemoveProjectOutcome.Removed ->
                OperationResult.Success(
                    "Removed ${outcome.project.fullName()} " +
                        "(${outcome.subscriptionsDeactivated} subscriptions deactivated)"
                )
            is RemoveProjectOutcome.ProjectNotFound ->
                OperationResult.Error("No registered repository found for ${outcome.identifier}")
            is RemoveProjectOutcome.AlreadyInactive ->
                OperationResult.Success("${outcome.project.fullName()} is already inactive")
        }
    }
}
