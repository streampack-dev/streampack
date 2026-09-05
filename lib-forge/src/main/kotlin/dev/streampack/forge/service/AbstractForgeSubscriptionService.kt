/* Joseph B. Ottinger (C)2026 */
package dev.streampack.forge.service

import dev.streampack.forge.ForgeKind
import dev.streampack.forge.client.ForgeClient
import dev.streampack.forge.model.ForgeProject
import dev.streampack.forge.model.ForgeSubscription
import dev.streampack.forge.store.ForgeStore
import java.time.Instant
import org.slf4j.LoggerFactory
import org.springframework.transaction.annotation.Transactional

/**
 * Registration, subscription, and removal of watched projects, written once over [ForgeStore].
 *
 * Subclasses supply the forge, its client and store, and the rule for what a valid project
 * identifier looks like. Methods are open so Spring can proxy the concrete subclass bean.
 */
@Transactional
abstract class AbstractForgeSubscriptionService<P : ForgeProject, S : ForgeSubscription>(
    protected val kind: ForgeKind,
    protected val store: ForgeStore<P, S>,
    protected val client: ForgeClient,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    /** A reason when [identifier] is not a valid project path for this forge, else null. */
    protected abstract fun invalidIdentifierReason(identifier: String): String?

    /** Register a project for watching, seeding cursors from its current state. */
    open fun addProject(identifier: String, token: String?): AddProjectOutcome<P> {
        invalidIdentifierReason(identifier)?.let {
            return AddProjectOutcome.InvalidIdentifier(identifier, it)
        }
        val existing = store.findProject(identifier)
        if (existing != null) {
            return AddProjectOutcome.AlreadyExists(existing)
        }

        return try {
            if (!client.validateProject(identifier, token)) {
                return AddProjectOutcome.ApiFailed(
                    identifier,
                    "Repository not found or not accessible",
                )
            }

            val issues = client.fetchIssuesSince(identifier, token, 0)
            val changeRequests = client.fetchChangeRequestsSince(identifier, token, 0)
            val releases = client.fetchReleases(identifier, token)

            val project =
                store.createProject(
                    path = identifier,
                    token = token,
                    highestIssueNumber = issues.maxOfOrNull { it.number } ?: 0,
                    highestChangeRequestNumber = changeRequests.maxOfOrNull { it.number } ?: 0,
                    polledAt = Instant.now(),
                )
            releases.forEach { store.saveRelease(project, it) }

            logger.info(
                "Added {} project {} ({} issues, {} {}s, {} releases)",
                kind.displayName,
                identifier,
                issues.size,
                changeRequests.size,
                kind.changeRequestNoun,
                releases.size,
            )
            AddProjectOutcome.Added(project, issues.size, changeRequests.size, releases.size)
        } catch (e: Exception) {
            logger.warn("Failed to add {} project {}: {}", kind.displayName, identifier, e.message)
            AddProjectOutcome.ApiFailed(identifier, e.message ?: "Unknown error")
        }
    }

    /** Subscribe a destination to a project's notifications. */
    open fun subscribe(identifier: String, destinationUri: String): SubscriptionOutcome<P> {
        val project =
            store.findProject(identifier) ?: return SubscriptionOutcome.ProjectNotFound(identifier)
        val existing = store.findSubscription(project, destinationUri)
        if (existing != null && existing.active) {
            return SubscriptionOutcome.AlreadySubscribed(project)
        }
        if (existing != null) {
            store.setSubscriptionActive(existing, true)
        } else {
            store.createSubscription(project, destinationUri)
        }
        logger.info("Subscribed {} to {}", destinationUri, identifier)
        return SubscriptionOutcome.Subscribed(project)
    }

    /** Unsubscribe a destination from a project. */
    open fun unsubscribe(identifier: String, destinationUri: String): SubscriptionOutcome<P> {
        val project =
            store.findProject(identifier) ?: return SubscriptionOutcome.ProjectNotFound(identifier)
        val existing = store.findSubscription(project, destinationUri)
        if (existing == null || !existing.active) {
            return SubscriptionOutcome.NotSubscribed(project)
        }
        store.setSubscriptionActive(existing, false)
        logger.info("Unsubscribed {} from {}", destinationUri, identifier)
        return SubscriptionOutcome.Unsubscribed(project)
    }

    /** Deactivate a project and all its subscriptions. */
    open fun removeProject(identifier: String): RemoveProjectOutcome<P> {
        val project =
            store.findProject(identifier) ?: return RemoveProjectOutcome.ProjectNotFound(identifier)
        if (!project.active) {
            return RemoveProjectOutcome.AlreadyInactive(project)
        }
        val activeSubscriptions = store.findActiveSubscriptions(project)
        activeSubscriptions.forEach { store.setSubscriptionActive(it, false) }
        store.deactivateProject(project)
        logger.info(
            "Removed {} project {} and deactivated {} subscriptions",
            kind.displayName,
            identifier,
            activeSubscriptions.size,
        )
        return RemoveProjectOutcome.Removed(project, activeSubscriptions.size)
    }

    /** List all registered projects. */
    open fun listProjects(): List<P> = store.listProjects()

    /** List active subscriptions for a destination. */
    open fun listSubscriptions(destinationUri: String): List<S> =
        store.findActiveSubscriptionsByDestination(destinationUri)
}
