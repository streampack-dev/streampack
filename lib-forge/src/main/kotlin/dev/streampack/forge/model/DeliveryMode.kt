/* Joseph B. Ottinger (C)2026 */
package dev.streampack.forge.model

/** How change notifications reach a project: periodic polling or inbound webhooks. */
enum class DeliveryMode {
    POLLING,
    WEBHOOK,
}
