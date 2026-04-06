/* Joseph B. Ottinger (C)2026 */
package dev.streampack.rss.model

/** Typed request to register a new RSS/Atom feed for consumption */
data class AddFeedRequest(val url: String)
