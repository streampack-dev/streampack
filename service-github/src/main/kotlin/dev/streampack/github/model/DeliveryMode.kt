/* Joseph B. Ottinger (C)2026 */
package dev.streampack.github.model

/** Indicates how a GitHub repository delivers events into Nevet */
enum class DeliveryMode {
    POLLING,
    WEBHOOK,
}
