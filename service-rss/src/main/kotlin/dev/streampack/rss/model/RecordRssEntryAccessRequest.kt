/* Joseph B. Ottinger (C)2026 */
package dev.streampack.rss.model

import java.util.UUID

/** Internal request to record UI-driven usage of a stored RSS entry. */
data class RecordRssEntryAccessRequest(val id: UUID)
