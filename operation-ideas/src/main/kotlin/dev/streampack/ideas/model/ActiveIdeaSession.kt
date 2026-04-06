/* Joseph B. Ottinger (C)2026 */
package dev.streampack.ideas.model

import java.time.Instant

/** In-memory tracker for idea session inactivity timeout */
data class ActiveIdeaSession(val provenanceUri: String, var lastActivityAt: Instant)
