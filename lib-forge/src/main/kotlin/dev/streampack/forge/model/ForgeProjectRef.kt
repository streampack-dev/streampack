/* Joseph B. Ottinger (C)2026 */
package dev.streampack.forge.model

/**
 * How a forge identifies a project: the [path] users type and messages show, plus the forge's own
 * [externalId] when it has one (GitLab's numeric project id). Stores that keep the id match webhook
 * deliveries on it first, so a renamed or moved project still routes; the path is the fallback.
 */
data class ForgeProjectRef(val path: String, val externalId: String? = null)
