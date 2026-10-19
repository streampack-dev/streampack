/* Joseph B. Ottinger (C)2026 */
package dev.streampack.tell.operation

import dev.streampack.core.model.OperationResult
import dev.streampack.core.model.Protocol
import dev.streampack.core.model.Provenance
import dev.streampack.core.service.TellTargetResolution
import dev.streampack.core.service.TellTargetResolver
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.messaging.support.MessageBuilder

/** A protocol's [TellTargetResolver] reads the target before anything is sent (#262). */
class TellTargetResolutionTests {
    private val asked = CopyOnWriteArrayList<Provenance>()

    /** Mattermost's stand-in: `@alice` is a DM channel, `foo bar` a channel, the rest unknown. */
    private val mattermost =
        object : TellTargetResolver {
            override val protocol = Protocol.MATTERMOST

            override fun resolve(target: Provenance): TellTargetResolution {
                asked += target
                return when (target.replyTo) {
                    "@alice" -> TellTargetResolution.Resolved(target.copy(replyTo = "dm-alice"))
                    "foo bar" -> TellTargetResolution.Resolved(target.copy(replyTo = "ch-foobar"))
                    else -> TellTargetResolution.Refused("No channel '${target.replyTo}' on fn")
                }
            }
        }

    private val tell = TellOperation(listOf(mattermost))

    private fun from(protocol: Protocol, serviceId: String, text: String) =
        MessageBuilder.withPayload(text)
            .setHeader(Provenance.HEADER, Provenance(protocol, serviceId, replyTo = "here"))
            .setHeader(Provenance.ADDRESSED, true)
            .setHeader("nick", "joe")
            .build()

    private fun target(text: String, protocol: Protocol = Protocol.MATTERMOST): Provenance {
        val result = tell.execute(from(protocol, "fn", text))
        return assertInstanceOf(OperationResult.Success::class.java, result).provenance!!
    }

    @Test
    fun `a short target on the protocol is resolved, from inside the server`() {
        assertEquals("dm-alice", target("tell @alice hi").replyTo)
        assertEquals("ch-foobar", target("tell \"foo bar\" hi").replyTo)
        assertEquals("fn", asked.first().serviceId)
    }

    @Test
    fun `a full address is resolved too, from anywhere`() {
        assertEquals("dm-alice", target("tell mattermost://fn/@alice hi", Protocol.IRC).replyTo)
        assertEquals(
            "ch-foobar",
            target("tell \"mattermost://fn/foo bar\" hi", Protocol.IRC).replyTo,
        )
    }

    @Test
    fun `a refusal goes back to the sender, and nothing is sent`() {
        val result = tell.execute(from(Protocol.MATTERMOST, "fn", "tell nowhere hi"))
        val error = assertInstanceOf(OperationResult.Error::class.java, result)
        assertTrue(error.message.contains("No channel 'nowhere'"), error.message)
    }

    @Test
    fun `a protocol with no resolver gets the target as typed`() {
        assertEquals("@alice", target("tell irc://libera/@alice hi").replyTo)
        assertTrue(asked.isEmpty())
    }
}
