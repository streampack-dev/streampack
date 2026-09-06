/* Joseph B. Ottinger (C)2026 */
package dev.streampack.forge.store

import dev.streampack.core.model.SecretRef
import dev.streampack.forge.model.DeliveryMode
import dev.streampack.forge.model.ForgeInstance
import dev.streampack.forge.model.ForgeProject
import dev.streampack.forge.model.ForgeProjectRef
import dev.streampack.forge.model.ForgeReleaseInfo
import dev.streampack.forge.model.ForgeSubscription
import dev.streampack.forge.model.PipelineOutcome
import java.time.Instant

/**
 * Persistence port implemented once per forge module over that module's own tables.
 *
 * The shared services never touch Spring Data types; everything they need to read or write goes
 * through here, which lets each module keep its own entities, migrations, and cursor columns.
 */
interface ForgeStore<I : ForgeInstance, P : ForgeProject, S : ForgeSubscription> {
    /** The hosted instance used when a command omits `on <host>`; created when absent. */
    fun defaultInstance(): I

    /** The instance registered under [host] (compared lowercase), or null when unknown. */
    fun findInstanceByHost(host: String): I?

    fun findInstanceById(id: String): I?

    fun listInstances(): List<I>

    fun createInstance(host: String, apiUrl: String, defaultToken: SecretRef?): I

    /**
     * The project [ref] names on [instance], or null when unknown or malformed. A store that keeps
     * the forge's native id matches [ForgeProjectRef.externalId] first and the path second.
     */
    fun findProject(instance: I, ref: ForgeProjectRef): P?

    fun findProjectById(id: String): P?

    fun listProjects(): List<P>

    fun findActiveProjects(deliveryMode: DeliveryMode): List<P>

    /** Active polling-mode projects due at or before [now], oldest due first, at most [limit]. */
    fun findDueProjects(now: Instant, limit: Int): List<P>

    /** Record when [project] is next due and how many polls in a row have failed. */
    fun schedulePoll(project: P, nextPollAt: Instant, pollFailures: Int): P

    fun createProject(
        instance: I,
        ref: ForgeProjectRef,
        token: SecretRef?,
        highestIssueNumber: Int,
        highestChangeRequestNumber: Int,
        polledAt: Instant,
        nextPollAt: Instant,
    ): P

    fun updateCursors(
        project: P,
        highestIssueNumber: Int,
        highestChangeRequestNumber: Int,
        polledAt: Instant,
    ): P

    fun deactivateProject(project: P): P

    /** Which of [tags] are already recorded as known releases for [project]. */
    fun knownReleaseTags(project: P, tags: List<String>): Set<String>

    fun saveRelease(project: P, release: ForgeReleaseInfo)

    fun findSubscription(project: P, destinationUri: String): S?

    fun createSubscription(project: P, destinationUri: String): S

    fun setSubscriptionActive(subscription: S, active: Boolean): S

    /**
     * Replace the stored event tokens; see [dev.streampack.forge.subscription.SubscriptionEvents].
     */
    fun setSubscriptionEvents(subscription: S, events: List<String>): S

    /** The last recorded outcome of a pipeline on [project], or null when never seen. */
    fun pipelineStatus(project: P, pipelineId: String): PipelineOutcome?

    /** Record the outcome just observed for a pipeline on [project]. */
    fun recordPipeline(project: P, pipelineId: String, outcome: PipelineOutcome)

    fun findActiveSubscriptions(project: P): List<S>

    fun findActiveSubscriptionsByDestination(destinationUri: String): List<S>
}
