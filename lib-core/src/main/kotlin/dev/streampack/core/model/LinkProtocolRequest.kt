/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.model

/** Admin request to bind a protocol identity to a user */
data class LinkProtocolRequest(
    val username: String,
    val protocol: Protocol,
    val serviceId: String,
    val externalIdentifier: String,
    val metadata: Map<String, Any> = emptyMap(),
)
