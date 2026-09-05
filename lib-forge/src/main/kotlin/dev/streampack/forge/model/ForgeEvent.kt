/* Joseph B. Ottinger (C)2026 */
package dev.streampack.forge.model

/**
 * A forge-neutral change event. Both polling and webhook ingestion produce these, so formatting and
 * fan-out are written once.
 */
sealed interface ForgeEvent {
    data class IssueOpened(val item: ForgeItem) : ForgeEvent

    data class ChangeRequestOpened(val item: ForgeItem) : ForgeEvent

    data class ReleasePublished(val release: ForgeReleaseInfo) : ForgeEvent

    /** A webhook handshake or test delivery; [note] is any forge-supplied text. */
    data class Ping(val note: String?) : ForgeEvent
}
