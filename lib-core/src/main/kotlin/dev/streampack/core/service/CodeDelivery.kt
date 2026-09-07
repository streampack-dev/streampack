/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.service

import dev.streampack.core.model.CodeChannel
import dev.streampack.core.model.CodeIdentity
import dev.streampack.core.model.ResolvedRecipient

/**
 * Delivers one-time sign-in codes over one channel. Implemented once per channel (email, a chat
 * adapter's direct messages, SMS); the request and verify operations pick the implementation by
 * [channel] and never know how a code travels.
 *
 * [resolve] is the channel's security boundary: it returns null for an identity the channel cannot
 * reach, and the caller then answers exactly as it would for a real one, without delivering. A chat
 * channel can only reach users that exist on the server; a phone channel may restrict itself to
 * numbers already bound to an account.
 */
interface CodeDelivery {
    val channel: CodeChannel

    /** Server names a chat channel is connected to, for the sign-in form; empty otherwise. */
    fun servers(): List<String> = emptyList()

    /** The canonical recipient for [identity], or null when this channel cannot reach it. */
    fun resolve(identity: CodeIdentity): ResolvedRecipient?

    fun deliver(recipient: ResolvedRecipient, code: String)
}
