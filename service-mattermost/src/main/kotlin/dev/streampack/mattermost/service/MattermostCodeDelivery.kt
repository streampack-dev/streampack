/* Joseph B. Ottinger (C)2026 */
package dev.streampack.mattermost.service

import dev.streampack.core.model.CodeChannel
import dev.streampack.core.model.CodeIdentity
import dev.streampack.core.model.Protocol
import dev.streampack.core.model.ResolvedRecipient
import dev.streampack.core.service.CodeDelivery
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component

/**
 * One-time sign-in codes as a Mattermost direct message from the bot (#53).
 *
 * Open by design: the bot can only reach users who exist on a connected server, so a first verify
 * may create the account. A username the server does not know, or a server that is not connected,
 * resolves to nothing and the caller answers as if it had.
 */
@Component
@ConditionalOnProperty("streampack.mattermost.enabled", havingValue = "true")
class MattermostCodeDelivery(
    private val adapterFor: (String) -> MattermostAdapter?,
    private val connectedServers: () -> List<String>,
) : CodeDelivery {

    @Autowired
    constructor(
        connectionManager: MattermostConnectionManager
    ) : this(
        { name -> connectionManager.getAdapter(name) },
        { connectionManager.connectedServerNames() },
    )

    /** Test constructor: a lookup and the single server it answers for */
    constructor(adapterFor: (String) -> MattermostAdapter?) : this(adapterFor, { listOf("work") })

    override val channel = CodeChannel.MATTERMOST

    override fun servers(): List<String> = connectedServers()

    override fun resolve(identity: CodeIdentity): ResolvedRecipient? {
        val server = identity.server ?: return null
        val adapter = adapterFor(server) ?: return null
        // Mattermost usernames are always lowercase; accept "@Alice" and "Alice" alike.
        val username = identity.address.trim().removePrefix("@").lowercase()
        if (username.isBlank()) return null
        val user = adapter.lookupUser(username) ?: return null
        if (user.id.isBlank()) return null
        return ResolvedRecipient(
            key = "$server/${user.id}",
            protocol = Protocol.MATTERMOST,
            serviceId = server,
            externalIdentifier = user.id,
            username = user.username,
            displayName = user.displayName,
        )
    }

    override fun deliver(recipient: ResolvedRecipient, code: String) {
        val server = recipient.serviceId ?: return
        val userId = recipient.externalIdentifier ?: return
        val adapter = adapterFor(server) ?: return
        adapter.sendDirectMessage(
            userId,
            "Your Streampack sign-in code is $code. It expires shortly.",
        )
    }
}
