/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.testing

import dev.streampack.core.integration.TickListener
import dev.streampack.core.model.Declined
import dev.streampack.core.model.OperationOutcome
import dev.streampack.core.model.OperationResult
import dev.streampack.core.model.Protocol
import dev.streampack.core.model.Provenance
import dev.streampack.core.model.Role
import dev.streampack.core.model.UserPrincipal
import dev.streampack.core.service.Operation
import java.time.Instant
import java.util.UUID
import org.springframework.messaging.Message
import org.springframework.messaging.support.MessageBuilder

/**
 * Runs an extension's operations with no server and no database (#294). It holds in-memory versions
 * of the services the server gives an extension; build the operations with them, add them with
 * [use], and [send] them commands.
 *
 * [send] goes through the operations as the server does: in priority order, skipping one that needs
 * to be addressed when the message isn't, and passing on when an operation returns null or
 * [Declined]. The first other outcome is the answer. The server's own policies (throttling, an
 * operation group turned off in a channel, timeouts, fan-out) aren't applied.
 */
class ExtensionHarness {
    val data = InMemoryExtensionDataStore()
    val state = InMemoryProvenanceStateService()
    val transformers = NoTransformers()
    val egress = RecordingChannel()
    var server: FixedServerInfo = FixedServerInfo()

    private val operations = mutableListOf<Operation>()
    private val tickListeners = mutableListOf<TickListener>()

    /** Adds operations, and anything else the server would call, such as a [TickListener]. */
    fun use(vararg components: Any): ExtensionHarness {
        components.forEach {
            if (it is Operation) operations += it
            if (it is TickListener) tickListeners += it
        }
        operations.sortBy { it.priority }
        return this
    }

    /** Sends [text] from [user] in [channel], and returns the answer. */
    fun send(
        text: String,
        user: UserPrincipal = user(),
        channel: Provenance = channel(user),
        addressed: Boolean = true,
    ): OperationOutcome = dispatch(message(text, channel.copy(user = user), addressed))

    /** The text of a [OperationResult.Success] answer to [text], or null for any other answer. */
    fun reply(text: String, user: UserPrincipal = user(), channel: Provenance = channel(user)) =
        (send(text, user, channel) as? OperationResult.Success)?.payload?.toString()

    /** Calls every [TickListener] with [now], as the server's one-second tick does. */
    fun tick(now: Instant) = tickListeners.forEach { it.onTick(now) }

    /** Sends [message] through the operations; for when [send] can't build the message. */
    fun dispatch(message: Message<*>): OperationOutcome {
        val addressed = message.headers[Provenance.ADDRESSED] as? Boolean ?: true
        for (op in operations) {
            if (op.addressed && !addressed) continue
            if (!op.canHandle(message)) continue
            val outcome = op.execute(message) ?: continue
            if (outcome is Declined) continue
            return outcome
        }
        return OperationResult.NotHandled
    }

    companion object {
        /** A user with [role], by default an ordinary signed-in one. */
        fun user(name: String = "user", role: Role = Role.USER) =
            UserPrincipal(id = UUID.randomUUID(), username = name, displayName = name, role = role)

        /** A console channel, the same for every user unless [replyTo] differs. */
        fun channel(user: UserPrincipal? = null, replyTo: String = "local") =
            Provenance(protocol = Protocol.CONSOLE, serviceId = "", replyTo = replyTo, user = user)

        /** A message as an adapter would send it. */
        fun message(text: String, provenance: Provenance, addressed: Boolean = true): Message<*> =
            MessageBuilder.withPayload(text)
                .setHeader(Provenance.HEADER, provenance)
                .setHeader(Provenance.ADDRESSED, addressed)
                .build()
    }
}
