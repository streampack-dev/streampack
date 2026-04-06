/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.service

import dev.streampack.core.model.Protocol

/** Describes how a protocol adapter identifies users, enabling admin help and discovery */
data class IdentityDescription(
    val protocol: Protocol,
    val serviceIdLabel: String,
    val externalIdLabel: String,
    val availableServices: List<String>,
)
