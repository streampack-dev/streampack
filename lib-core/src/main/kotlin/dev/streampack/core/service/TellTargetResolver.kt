/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.service

import dev.streampack.core.model.Protocol
import dev.streampack.core.model.Provenance

/**
 * A protocol module's reading of a `tell` target on its own protocol (#262).
 *
 * The target is an address. Its [Provenance.replyTo] is the text that the sender typed, and that
 * text can be a name, not the id that the protocol posts to: on Mattermost, a user `@alice` or a
 * channel `town-square`. The resolver gives the address that egress can deliver to, or the reason
 * that there is none.
 *
 * A protocol with no resolver gets the text as typed. IRC needs no resolver, because a nick or a
 * channel name is the address that IRC posts to.
 */
interface TellTargetResolver {
    /** The protocol whose targets this resolver reads */
    val protocol: Protocol

    /** Reads [target] and gives the address to deliver to, or the reason that there is none */
    fun resolve(target: Provenance): TellTargetResolution
}

/** The result of a [TellTargetResolver] reading a `tell` target */
sealed class TellTargetResolution {
    /** The target can get the message at [provenance] */
    data class Resolved(val provenance: Provenance) : TellTargetResolution()

    /** The target can't get the message; [reason] tells the sender why, in plain words */
    data class Refused(val reason: String) : TellTargetResolution()
}
