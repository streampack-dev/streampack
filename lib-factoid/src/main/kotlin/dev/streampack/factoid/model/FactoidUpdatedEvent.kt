/* Joseph B. Ottinger (C)2026 */
package dev.streampack.factoid.model

/** Cross-module event published when a factoid selector is created or updated. */
data class FactoidUpdatedEvent(val selector: String)
