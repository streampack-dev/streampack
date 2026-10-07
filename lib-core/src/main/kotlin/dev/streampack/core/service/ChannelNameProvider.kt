/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.service

import dev.streampack.core.model.Protocol
import dev.streampack.core.model.Provenance

/**
 * A protocol module's name for one of its channels, for readable addresses such as
 * `/logs/slack/jvm-news/java`: the channel's name where its provenance carries only an id. IRC and
 * Discord provenances carry their names already, and need no provider.
 */
interface ChannelNameProvider {
    val protocol: Protocol

    /** The channel's name, without any leading `#`, or null if this module doesn't know it */
    fun name(provenance: Provenance): String?
}
