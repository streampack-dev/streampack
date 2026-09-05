/* Joseph B. Ottinger (C)2026 */
package dev.streampack.forge.store

import dev.streampack.core.model.SecretRef
import dev.streampack.forge.model.DeliveryMode
import dev.streampack.forge.model.ForgeProject
import dev.streampack.forge.model.ForgeReleaseInfo
import dev.streampack.forge.model.ForgeSubscription
import java.time.Instant

/**
 * Persistence port implemented once per forge module over that module's own tables.
 *
 * The shared services never touch Spring Data types; everything they need to read or write goes
 * through here, which keeps the templates to two type parameters and lets each module keep its own
 * entities, migrations, and cursor columns.
 */
interface ForgeStore<P : ForgeProject, S : ForgeSubscription> {
    /** The project registered under [path], or null when unknown or [path] is malformed. */
    fun findProject(path: String): P?

    fun findProjectById(id: String): P?

    fun listProjects(): List<P>

    fun findActiveProjects(deliveryMode: DeliveryMode): List<P>

    fun createProject(
        path: String,
        token: SecretRef?,
        highestIssueNumber: Int,
        highestChangeRequestNumber: Int,
        polledAt: Instant,
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

    fun findActiveSubscriptions(project: P): List<S>

    fun findActiveSubscriptionsByDestination(destinationUri: String): List<S>
}
