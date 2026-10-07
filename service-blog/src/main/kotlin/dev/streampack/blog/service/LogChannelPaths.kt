/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.service

import dev.streampack.core.model.Protocol
import dev.streampack.core.model.Provenance
import dev.streampack.core.service.ChannelNameProvider
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import org.springframework.stereotype.Component

/**
 * Readable addresses for channel logs (#147): `irc/libera/primate` for `irc://libera/%23primate`.
 *
 * A path is `{protocol}/{service}/{name}`. An IRC name is matched as written, then with `#`, then
 * with `##` (Libera has `##` channels), so `primate` is `#primate` when there is one; the path for
 * `##primate` beside a `#primate` names its hashes. Other protocols' channels go by their stored
 * names, or by their ids where a name isn't known or is shared (every Mattermost team has a
 * `town-square`).
 *
 * Paths are only ever resolved among the channels the caller may browse, so a path can't reveal a
 * channel that's hidden from them.
 */
@Component
class LogChannelPaths(providers: List<ChannelNameProvider>) {
    private val providers = providers.associateBy { it.protocol }

    /** The channel's name: an IRC channel as written (`#primate`), others without a `#` */
    fun name(provenance: Provenance): String? =
        when (provenance.protocol) {
            Protocol.IRC -> provenance.replyTo
            // A guild channel is "<id>/<guild>[/<category>]/#<channel>"
            Protocol.DISCORD ->
                provenance.replyTo.split("/").drop(1).lastOrNull()?.removePrefix("#")?.takeIf {
                    it.isNotBlank()
                }
            else -> providers[provenance.protocol]?.name(provenance)
        }

    /** The channel among [channels] that [protocol]/[service]/[name] addresses, if any */
    fun resolve(
        protocol: String,
        service: String,
        name: String,
        channels: Collection<String>,
    ): String? {
        val candidates = channels.mapNotNull { uri ->
            runCatching { Provenance.decode(uri) }
                .getOrNull()
                ?.takeIf {
                    it.protocol.name.equals(protocol, ignoreCase = true) && it.serviceId == service
                }
                ?.let { uri to it }
        }
        if (candidates.isEmpty()) return null
        if (protocol.equals(Protocol.IRC.name, ignoreCase = true)) {
            for (tried in listOf(name, "#$name", "##$name")) {
                candidates
                    .firstOrNull { (_, p) -> p.replyTo.equals(tried, ignoreCase = true) }
                    ?.let {
                        return it.first
                    }
            }
            return null
        }
        val byName = candidates.filter { (_, p) ->
            name(p)?.equals(name, ignoreCase = true) == true
        }
        if (byName.size == 1) return byName.single().first
        // A shared or unknown name: the channel's own id
        return candidates.firstOrNull { (_, p) -> channelId(p) == name }?.first
    }

    /**
     * The path for [uri] among [channels]: its shortest name that resolves back to it, or its id;
     * null for an address that isn't a channel.
     */
    fun path(uri: String, channels: Collection<String>): String? {
        val provenance = runCatching { Provenance.decode(uri) }.getOrNull() ?: return null
        val service = provenance.serviceId ?: return null
        val protocol = provenance.protocol.name.lowercase()
        val named = name(provenance)
        val tries =
            if (provenance.protocol == Protocol.IRC) {
                val bare = provenance.replyTo.trimStart('#')
                listOf(bare, provenance.replyTo)
            } else {
                listOfNotNull(named, channelId(provenance))
            }
        val segment =
            tries.firstOrNull { it.isNotBlank() && resolve(protocol, service, it, channels) == uri }
                ?: return null
        return "$protocol/${encode(service)}/${encode(segment)}"
    }

    /** The id part of a channel's address (Discord's carries display labels after it) */
    private fun channelId(provenance: Provenance): String =
        if (provenance.protocol == Protocol.DISCORD) provenance.replyTo.substringBefore("/")
        else provenance.replyTo

    private fun encode(segment: String): String =
        URLEncoder.encode(segment, StandardCharsets.UTF_8).replace("+", "%20")
}
