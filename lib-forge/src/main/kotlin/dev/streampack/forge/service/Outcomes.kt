/* Joseph B. Ottinger (C)2026 */
package dev.streampack.forge.service

import dev.streampack.forge.model.ForgeInstance
import dev.streampack.forge.model.ForgeProject
import dev.streampack.forge.subscription.PipelineFilter

sealed interface AddInstanceOutcome<I : ForgeInstance> {
    data class Added<I : ForgeInstance>(val instance: I) : AddInstanceOutcome<I>

    data class AlreadyExists<I : ForgeInstance>(val instance: I) : AddInstanceOutcome<I>

    data class Invalid<I : ForgeInstance>(val host: String, val reason: String) :
        AddInstanceOutcome<I>
}

sealed interface AddProjectOutcome<P : ForgeProject> {
    data class Added<P : ForgeProject>(
        val project: P,
        val issueCount: Int,
        val changeRequestCount: Int,
        val releaseCount: Int,
    ) : AddProjectOutcome<P>

    data class AlreadyExists<P : ForgeProject>(val project: P) : AddProjectOutcome<P>

    data class InvalidIdentifier<P : ForgeProject>(val identifier: String, val reason: String) :
        AddProjectOutcome<P>

    data class ApiFailed<P : ForgeProject>(val identifier: String, val reason: String) :
        AddProjectOutcome<P>
}

sealed interface SubscriptionOutcome<P : ForgeProject> {
    data class Subscribed<P : ForgeProject>(
        val project: P,
        val filters: List<PipelineFilter> = emptyList(),
    ) : SubscriptionOutcome<P>

    /** An active subscription had its pipeline filters replaced. */
    data class FiltersUpdated<P : ForgeProject>(val project: P, val filters: List<PipelineFilter>) :
        SubscriptionOutcome<P>

    data class Unsubscribed<P : ForgeProject>(val project: P) : SubscriptionOutcome<P>

    data class AlreadySubscribed<P : ForgeProject>(val project: P) : SubscriptionOutcome<P>

    data class NotSubscribed<P : ForgeProject>(val project: P) : SubscriptionOutcome<P>

    data class ProjectNotFound<P : ForgeProject>(val identifier: String) : SubscriptionOutcome<P>
}

sealed interface RemoveProjectOutcome<P : ForgeProject> {
    data class Removed<P : ForgeProject>(val project: P, val subscriptionsDeactivated: Int) :
        RemoveProjectOutcome<P>

    data class ProjectNotFound<P : ForgeProject>(val identifier: String) : RemoveProjectOutcome<P>

    data class AlreadyInactive<P : ForgeProject>(val project: P) : RemoveProjectOutcome<P>
}
