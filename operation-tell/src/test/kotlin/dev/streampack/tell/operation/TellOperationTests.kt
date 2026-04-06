/* Joseph B. Ottinger (C)2026 */
package dev.streampack.tell.operation

import dev.streampack.core.integration.EventGateway
import dev.streampack.core.model.OperationResult
import dev.streampack.core.model.Protocol
import dev.streampack.core.model.Provenance
import dev.streampack.tell.model.TellRequest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.messaging.support.MessageBuilder
import org.springframework.transaction.annotation.Transactional

@SpringBootTest
@Transactional
class TellOperationTests {

    @Autowired lateinit var eventGateway: EventGateway

    @Autowired lateinit var tellOperation: TellOperation

    private fun provenance(
        replyTo: String = "#test",
        serviceId: String = "testnet",
        protocol: Protocol = Protocol.IRC,
    ) = Provenance(protocol = protocol, serviceId = serviceId, replyTo = replyTo)

    private fun addressedMessage(
        text: String,
        replyTo: String = "#test",
        serviceId: String = "testnet",
        nick: String = "testuser",
    ) =
        MessageBuilder.withPayload(text)
            .setHeader(Provenance.HEADER, provenance(replyTo, serviceId))
            .setHeader(Provenance.ADDRESSED, true)
            .setHeader("nick", nick)
            .build()

    @Test
    fun `tell with name resolves to private message on same protocol`() {
        val result = tellOperation.execute(addressedMessage("tell blue go to heck!"))
        assertInstanceOf(OperationResult.Success::class.java, result)
        val success = result as OperationResult.Success
        assertNotNull(success.provenance, "Result should have provenance override")
        assertEquals("blue", success.provenance!!.replyTo)
        assertEquals(Protocol.IRC, success.provenance!!.protocol)
        assertEquals("testnet", success.provenance!!.serviceId)
    }

    @Test
    fun `tell with channel resolves to channel on same protocol`() {
        val result = tellOperation.execute(addressedMessage("tell #java hello there"))
        assertInstanceOf(OperationResult.Success::class.java, result)
        val success = result as OperationResult.Success
        assertNotNull(success.provenance)
        assertEquals("#java", success.provenance!!.replyTo)
    }

    @Test
    fun `tell with full URI uses URI directly`() {
        val result =
            tellOperation.execute(addressedMessage("tell irc://othernet/%23java hello from here"))
        assertInstanceOf(OperationResult.Success::class.java, result)
        val success = result as OperationResult.Success
        assertNotNull(success.provenance)
        assertEquals("othernet", success.provenance!!.serviceId)
        assertTrue(success.provenance!!.replyTo.contains("java"))
    }

    @Test
    fun `tell without message is not handled`() {
        val result = tellOperation.execute(addressedMessage("tell blue"))
        assertEquals(null, result)
    }

    @Test
    fun `tell without target is not handled`() {
        val result = tellOperation.execute(addressedMessage("tell"))
        assertEquals(null, result)
    }

    @Test
    fun `non-tell message is not handled`() {
        val result = tellOperation.execute(addressedMessage("something else"))
        assertEquals(null, result)
    }

    @Test
    fun `tell includes sender attribution in payload`() {
        val result = tellOperation.execute(addressedMessage("tell blue hey there", nick = "alice"))
        assertInstanceOf(OperationResult.Success::class.java, result)
        val success = result as OperationResult.Success
        assertEquals("<alice> hey there", success.payload)
    }

    @Test
    fun `typed TellRequest bypasses translation`() {
        val targetProvenance =
            Provenance(protocol = Protocol.IRC, serviceId = "testnet", replyTo = "bob")
        val typedMessage =
            MessageBuilder.withPayload(TellRequest(targetProvenance, "hello from typed") as Any)
                .setHeader(Provenance.HEADER, provenance())
                .setHeader(Provenance.ADDRESSED, true)
                .setHeader("nick", "alice")
                .build()

        val result = tellOperation.execute(typedMessage)
        assertInstanceOf(OperationResult.Success::class.java, result)
        val success = result as OperationResult.Success
        assertEquals("<alice> hello from typed", success.payload)
        assertEquals("bob", success.provenance!!.replyTo)
    }
}
