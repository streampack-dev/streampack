/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.service

import dev.streampack.core.model.Protocol
import dev.streampack.core.model.Provenance

/**
 * A protocol module's way of saying something privately to the person who sent a message: an IRC
 * private message to their nick, a DM on Slack, Discord or Mattermost. It never posts to the
 * channel the message came from.
 *
 * [senderId] is the protocol's own name for the sender, as the adapter put it in the message's
 * [Provenance.SENDER_ID] header (a nick on IRC, a user id elsewhere). A protocol with no notifier
 * simply can't be told anything privately.
 */
interface SenderNotifier {
    val protocol: Protocol

    /** Sends [text] to [senderId] on [provenance]'s network or server; false if it couldn't */
    fun notifySender(provenance: Provenance, senderId: String, text: String): Boolean
}
