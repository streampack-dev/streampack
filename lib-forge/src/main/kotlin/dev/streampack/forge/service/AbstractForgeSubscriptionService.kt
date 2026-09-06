/* Joseph B. Ottinger (C)2026 */
package dev.streampack.forge.service

import dev.streampack.core.model.SecretRef
import dev.streampack.forge.ForgeKind
import dev.streampack.forge.client.ForgeClient
import dev.streampack.forge.command.InstanceSelector
import dev.streampack.forge.model.ForgeInstance
import dev.streampack.forge.model.ForgeProject
import dev.streampack.forge.model.ForgeSubscription
import dev.streampack.forge.secret.ForgeTokenResolver
import dev.streampack.forge.secret.SecretLookup
import dev.streampack.forge.store.ForgeStore
import java.time.Instant
import org.slf4j.LoggerFactory
import org.springframework.transaction.annotation.Transactional

/**
 * Registration, subscription, and removal of watched projects and instances, written once over
 * [ForgeStore].
 *
 * Subclasses supply the forge, its client and store, and the rule for what a valid project
 * identifier looks like. Methods are open so Spring can proxy the concrete subclass bean.
 */
@Transactional
abstract class AbstractForgeSubscriptionService<
    I : ForgeInstance,
    P : ForgeProject,
    S : ForgeSubscription,
>(
    protected val kind: ForgeKind,
    protected val store: ForgeStore<I, P, S>,
    protected val client: ForgeClient,
    protected val secretLookup: SecretLookup,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    /** A reason when [identifier] is not a valid project path for this forge, else null. */
    protected abstract fun invalidIdentifierReason(identifier: String): String?

    /**
     * The instance a command addresses: the one registered under [host], or the hosted default when
     * [host] is null. Null when [host] names no registered instance.
     */
    open fun instanceFor(host: String?): I? =
        if (host == null) store.defaultInstance()
        else store.findInstanceByHost(InstanceSelector.normalizeHost(host))

    /**
     * Register an instance. [token] is its default credential, stored as given (literal or
     * `env://KEY`); an env reference must already resolve.
     */
    open fun addInstance(host: String, apiUrl: String, token: String?): AddInstanceOutcome<I> {
        val normalizedHost = InstanceSelector.normalizeHost(host)
        if (normalizedHost.isBlank()) {
            return AddInstanceOutcome.Invalid(host, "Instance host must not be blank")
        }
        store.findInstanceByHost(normalizedHost)?.let {
            return AddInstanceOutcome.AlreadyExists(it)
        }
        val tokenRef = parseToken(token)
        checkTokenReference(tokenRef)?.let {
            return AddInstanceOutcome.Invalid(normalizedHost, it)
        }
        val instance = store.createInstance(normalizedHost, apiUrl, tokenRef)
        logger.info("Added {} instance {} at {}", kind.displayName, normalizedHost, apiUrl)
        return AddInstanceOutcome.Added(instance)
    }

    open fun listInstances(): List<I> = store.listInstances()

    /**
     * Register a project for watching on [instance], seeding cursors from its current state.
     *
     * [token] may be a literal or an `env://KEY` reference; it is stored as given and resolved for
     * the API calls made here. Without one, the instance default credential is used.
     */
    open fun addProject(instance: I, identifier: String, token: String?): AddProjectOutcome<P> {
        invalidIdentifierReason(identifier)?.let {
            return AddProjectOutcome.InvalidIdentifier(identifier, it)
        }
        val existing = store.findProject(instance, identifier)
        if (existing != null) {
            return AddProjectOutcome.AlreadyExists(existing)
        }
        val tokenRef = parseToken(token)
        checkTokenReference(tokenRef)?.let { reason ->
            return if (tokenRef?.isEnvRef() == true && tokenRef.envKeyOrNull() == null) {
                AddProjectOutcome.InvalidIdentifier(identifier, reason)
            } else {
                AddProjectOutcome.ApiFailed(identifier, reason)
            }
        }
        val resolvedToken =
            ForgeTokenResolver.resolve(tokenRef ?: instance.defaultToken, secretLookup)

        return try {
            if (!client.validateProject(instance, identifier, resolvedToken)) {
                return AddProjectOutcome.ApiFailed(
                    identifier,
                    "Repository not found or not accessible",
                )
            }

            val issues = client.fetchIssuesSince(instance, identifier, resolvedToken, 0)
            val changeRequests =
                client.fetchChangeRequestsSince(instance, identifier, resolvedToken, 0)
            val releases = client.fetchReleases(instance, identifier, resolvedToken)

            val project =
                store.createProject(
                    instance = instance,
                    path = identifier,
                    token = tokenRef,
                    highestIssueNumber = issues.maxOfOrNull { it.number } ?: 0,
                    highestChangeRequestNumber = changeRequests.maxOfOrNull { it.number } ?: 0,
                    polledAt = Instant.now(),
                )
            releases.forEach { store.saveRelease(project, it) }

            logger.info(
                "Added {} project {} ({} issues, {} {}s, {} releases)",
                kind.displayName,
                project.displayName,
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
    open fun subscribe(
        instance: I,
        identifier: String,
        destinationUri: String,
    ): SubscriptionOutcome<P> {
        val project =
            store.findProject(instance, identifier)
                ?: return SubscriptionOutcome.ProjectNotFound(identifier)
        val existing = store.findSubscription(project, destinationUri)
        if (existing != null && existing.active) {
            return SubscriptionOutcome.AlreadySubscribed(project)
        }
        if (existing != null) {
            store.setSubscriptionActive(existing, true)
        } else {
            store.createSubscription(project, destinationUri)
        }
        logger.info("Subscribed {} to {}", destinationUri, project.displayName)
        return SubscriptionOutcome.Subscribed(project)
    }

    /** Unsubscribe a destination from a project. */
    open fun unsubscribe(
        instance: I,
        identifier: String,
        destinationUri: String,
    ): SubscriptionOutcome<P> {
        val project =
            store.findProject(instance, identifier)
                ?: return SubscriptionOutcome.ProjectNotFound(identifier)
        val existing = store.findSubscription(project, destinationUri)
        if (existing == null || !existing.active) {
            return SubscriptionOutcome.NotSubscribed(project)
        }
        store.setSubscriptionActive(existing, false)
        logger.info("Unsubscribed {} from {}", destinationUri, project.displayName)
        return SubscriptionOutcome.Unsubscribed(project)
    }

    /** Deactivate a project and all its subscriptions. */
    open fun removeProject(instance: I, identifier: String): RemoveProjectOutcome<P> {
        val project =
            store.findProject(instance, identifier)
                ?: return RemoveProjectOutcome.ProjectNotFound(identifier)
        if (!project.active) {
            return RemoveProjectOutcome.AlreadyInactive(project)
        }
        val activeSubscriptions = store.findActiveSubscriptions(project)
        activeSubscriptions.forEach { store.setSubscriptionActive(it, false) }
        store.deactivateProject(project)
        logger.info(
            "Removed {} project {} and deactivated {} subscriptions",
            kind.displayName,
            project.displayName,
            activeSubscriptions.size,
        )
        return RemoveProjectOutcome.Removed(project, activeSubscriptions.size)
    }

    /** List all registered projects. */
    open fun listProjects(): List<P> = store.listProjects()

    /** List active subscriptions for a destination. */
    open fun listSubscriptions(destinationUri: String): List<S> =
        store.findActiveSubscriptionsByDestination(destinationUri)

    private fun parseToken(token: String?): SecretRef? =
        token?.trim()?.ifBlank { null }?.let { SecretRef.parse(it) }

    /** A reason an `env://` token cannot be used now, else null. Literals always pass. */
    private fun checkTokenReference(tokenRef: SecretRef?): String? {
        if (tokenRef == null || !tokenRef.isEnvRef()) return null
        val envKey =
            tokenRef.envKeyOrNull()
                ?: return "Token reference '${tokenRef.asStoredValue()}' is not a valid env://KEY"
        if (ForgeTokenResolver.resolve(tokenRef, secretLookup) == null) {
            return "Token references environment variable $envKey, which is not set"
        }
        return null
    }
}
