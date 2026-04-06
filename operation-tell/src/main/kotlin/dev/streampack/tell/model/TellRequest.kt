/* Joseph B. Ottinger (C)2026 */
package dev.streampack.tell.model

import dev.streampack.core.model.Provenance

/** A request to deliver a message to a target identified by resolved provenance */
data class TellRequest(val targetProvenance: Provenance, val message: String)
