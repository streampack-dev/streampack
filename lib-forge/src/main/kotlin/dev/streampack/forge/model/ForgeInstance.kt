/* Joseph B. Ottinger (C)2026 */
package dev.streampack.forge.model

import dev.streampack.core.model.SecretRef

/**
 * A forge installation a project belongs to: the hosted service (`github.com`, `gitlab.com`) or a
 * self-hosted server. Each forge module persists its own instances; the shared services only need
 * the host users type in `on <host>`, the API base URL, and the default credential.
 */
interface ForgeInstance {
    /** What users type after `on`, e.g. `ghe.example.com`. Unique per forge, lowercase. */
    val host: String

    /**
     * Base URL for API calls, e.g. `https://api.github.com` or `https://ghe.example.com/api/v3`.
     */
    val apiUrl: String

    /** Credential used for projects on this instance that have no token of their own. */
    val defaultToken: SecretRef?

    val active: Boolean
}
