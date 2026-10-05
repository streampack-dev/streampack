/* Joseph B. Ottinger (C)2026 */
package dev.streampack.tell.model

import dev.streampack.core.model.Provenance

/**
 * A request to deliver a message to a target identified by resolved provenance. A [refusal] says
 * why the target can't be delivered to (a malformed address, say); the request is then answered
 * with it, and nothing is sent.
 */
data class TellRequest(
    val targetProvenance: Provenance,
    val message: String,
    val refusal: String? = null,
)
