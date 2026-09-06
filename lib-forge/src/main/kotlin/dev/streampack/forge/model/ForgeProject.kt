/* Joseph B. Ottinger (C)2026 */
package dev.streampack.forge.model

import dev.streampack.core.model.SecretRef
import java.time.Instant

/**
 * The view of a watched project that the shared forge services need. Each forge module's entity
 * implements this over its own table; the module keeps ownership of persistence.
 */
interface ForgeProject {
    /**
     * Human-readable identifier used in messages: `owner/repo` on the forge's hosted default, and
     * `host owner/repo` elsewhere so one channel can tell two instances apart.
     */
    val displayName: String

    /** The project's path within its instance, e.g. `owner/repo` or `group/sub/project`. */
    val path: String

    val instance: ForgeInstance

    /** API token for this project, if any: a literal or an `env://KEY` reference. */
    val token: SecretRef?

    /** The credential to use for API calls: the project's own token, else the instance default. */
    val effectiveToken: SecretRef?
        get() = token ?: instance.defaultToken

    val highestIssueNumber: Int

    val highestChangeRequestNumber: Int

    /** When the project was last polled; pipelines updated after this are candidates to report. */
    val lastPolledAt: Instant?

    val deliveryMode: DeliveryMode

    /** Encrypted webhook secret, present when [deliveryMode] is [DeliveryMode.WEBHOOK]. */
    val webhookSecret: String?

    val active: Boolean
}

/**
 * A destination subscribed to a project's notifications. [events] are the stored event tokens (see
 * [dev.streampack.forge.subscription.SubscriptionEvents]): the base kinds every subscription
 * receives plus any pipeline filters it opted into.
 */
interface ForgeSubscription {
    val destinationUri: String

    val events: List<String>

    val active: Boolean
}
