/* Joseph B. Ottinger (C)2026 */
package dev.streampack.forge.model

import dev.streampack.core.model.SecretRef

/**
 * The view of a watched project that the shared forge services need. Each forge module's entity
 * implements this over its own table; the module keeps ownership of persistence.
 */
interface ForgeProject {
    /** Human-readable identifier used in messages, e.g. `owner/repo` or `group/sub/project`. */
    val displayName: String

    /** API token for this project, if any: a literal or an `env://KEY` reference. */
    val token: SecretRef?

    val highestIssueNumber: Int

    val highestChangeRequestNumber: Int

    val deliveryMode: DeliveryMode

    /** Encrypted webhook secret, present when [deliveryMode] is [DeliveryMode.WEBHOOK]. */
    val webhookSecret: String?

    val active: Boolean
}

/** A destination subscribed to a project's notifications. */
interface ForgeSubscription {
    val destinationUri: String

    val active: Boolean
}
