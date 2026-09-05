/* Joseph B. Ottinger (C)2026 */
package dev.streampack.forge.format

import dev.streampack.forge.ForgeKind
import dev.streampack.forge.model.ForgeEvent

/** The single place notification text is built, so polling and webhooks read identically. */
object ForgeEventFormatter {
    fun format(kind: ForgeKind, projectName: String, event: ForgeEvent): String =
        when (event) {
            is ForgeEvent.IssueOpened ->
                "[$projectName] New issue #${event.item.number}: ${event.item.title} - ${event.item.url}"
            is ForgeEvent.ChangeRequestOpened ->
                "[$projectName] New ${kind.changeRequestNoun} #${event.item.number}: " +
                    "${event.item.title} - ${event.item.url}"
            is ForgeEvent.ReleasePublished ->
                "[$projectName] New release ${event.release.tag} - ${event.release.url}"
            is ForgeEvent.Ping -> {
                val suffix = if (event.note.isNullOrBlank()) "" else " (${event.note})"
                "[$projectName] Webhook ping received - setup verified.$suffix"
            }
        }
}
