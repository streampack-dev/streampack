/* Joseph B. Ottinger (C)2026 */
package dev.streampack.forge.model

import java.time.Instant

/** An issue or change request as seen from a forge API or webhook payload. */
data class ForgeItem(val number: Int, val title: String, val url: String)

/**
 * An issue or change request that left the open state at [closedAt], as polling sees it (#267).
 * [merged] is true when a change request was merged, not just closed; an issue is never merged.
 */
data class ForgeClosure(val item: ForgeItem, val closedAt: Instant, val merged: Boolean = false)

/** A release as seen from a forge API or webhook payload. */
data class ForgeReleaseInfo(val tag: String, val name: String?, val url: String)
