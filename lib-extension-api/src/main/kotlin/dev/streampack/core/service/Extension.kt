/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.service

/**
 * An extension's description of itself (#294), as a bean in its jar. The server checks it at
 * startup: when the server is older than [requiresServer], it stops and names the extension.
 * Optional; an extension without one loads on any server.
 */
interface Extension {
    /** The extension's name, as it keys its data in [ExtensionDataStore]. */
    val name: String

    /** The oldest server version this extension runs on, such as `1.16.11`, or null for any. */
    val requiresServer: String?
        get() = null
}
