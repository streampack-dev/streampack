/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.model

/**
 * Who sent a message, in the terms `link user` binds: the service (an IRC network, a Slack
 * workspace, a Discord guild, a Mattermost server) and the identifier on it (`ident@host`, a user
 * id). It is what the adapter looked the sender up by, so `whoami` can say how to link them.
 */
data class ServiceIdentity(val serviceId: String, val externalIdentifier: String)
