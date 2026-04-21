/* Joseph B. Ottinger (C)2026 */
package dev.streampack.bridge.operation

import dev.streampack.bridge.service.BridgeService
import dev.streampack.core.integration.EventGateway
import dev.streampack.core.model.OperationResult
import dev.streampack.core.model.Protocol
import dev.streampack.core.model.Provenance
import dev.streampack.core.model.Role
import dev.streampack.core.model.UserPrincipal
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.integration.channel.PublishSubscribeChannel
import org.springframework.messaging.support.MessageBuilder
import org.springframework.transaction.annotation.Transactional

@SpringBootTest
@Transactional
class BridgeOperationTests {

    @Autowired lateinit var eventGateway: EventGateway

    @Autowired lateinit var bridgeService: BridgeService

    @Autowired @Qualifier("egressChannel") lateinit var egressChannel: PublishSubscribeChannel

    private val adminUser =
        UserPrincipal(
            id = UUID.randomUUID(),
            username = "admin",
            displayName = "Admin",
            role = Role.ADMIN,
        )

    private val regularUser =
        UserPrincipal(
            id = UUID.randomUUID(),
            username = "user",
            displayName = "User",
            role = Role.USER,
        )

    private fun provenance(
        replyTo: String = "#test",
        serviceId: String = "testnet",
        user: UserPrincipal? = null,
    ) = Provenance(protocol = Protocol.IRC, serviceId = serviceId, replyTo = replyTo, user = user)

    private fun addressedMessage(
        text: String,
        replyTo: String = "#test",
        serviceId: String = "testnet",
        user: UserPrincipal? = null,
        nick: String? = null,
    ) =
        MessageBuilder.withPayload(text)
            .setHeader(Provenance.HEADER, provenance(replyTo, serviceId, user))
            .setHeader(Provenance.ADDRESSED, true)
            .apply { if (nick != null) setHeader("nick", nick) }
            .build()

    @Test
    fun `bridge help returns command list`() {
        val result = eventGateway.process(addressedMessage("bridge", user = adminUser))
        assertInstanceOf(OperationResult.Success::class.java, result)
        assertTrue((result as OperationResult.Success).payload.toString().contains("bridge copy"))
    }

    @Test
    fun `bridge copy requires admin role`() {
        val result =
            eventGateway.process(
                addressedMessage("bridge copy irc://a/%23one irc://b/%23two", user = regularUser)
            )
        assertInstanceOf(OperationResult.Error::class.java, result)
        assertTrue((result as OperationResult.Error).message.contains("ADMIN"))
    }

    @Test
    fun `bridge copy creates one-way bridge`() {
        val result =
            eventGateway.process(
                addressedMessage("bridge copy irc://a/%23one irc://b/%23two", user = adminUser)
            )
        assertInstanceOf(OperationResult.Success::class.java, result)
        val payload = (result as OperationResult.Success).payload.toString()
        assertTrue(payload.contains("Bridge established"))

        val targets = bridgeService.getCopyTargets("irc://a/%23one")
        assertEquals(1, targets.size)
        assertEquals("irc://b/%23two", targets[0])

        // Reverse direction is not established
        val reverseTargets = bridgeService.getCopyTargets("irc://b/%23two")
        assertTrue(reverseTargets.isEmpty())
    }

    @Test
    fun `bridge copy normalizes Discord display labels to stable channel identity`() {
        val source = "discord://1256590312177012806/222222222222222222/server/rcompat/%23general"
        val target = "irc://libera/%23java"

        bridgeService.copy(source, target)

        assertEquals(
            listOf(target),
            bridgeService.getCopyTargets("discord://1256590312177012806/222222222222222222"),
        )
        assertEquals(
            listOf(target),
            bridgeService.getCopyTargets(
                "discord://1256590312177012806/222222222222222222/renamed/other/%23changed"
            ),
        )
        assertTrue(
            bridgeService.getCopyTargets("discord://1256590312177012806/%23general").isEmpty()
        )
    }

    @Test
    fun `bridge copy reverse direction creates bidirectional mirror`() {
        bridgeService.copy("irc://a/%23one", "irc://b/%23two")

        val result =
            eventGateway.process(
                addressedMessage("bridge copy irc://b/%23two irc://a/%23one", user = adminUser)
            )
        assertInstanceOf(OperationResult.Success::class.java, result)

        val forwardTargets = bridgeService.getCopyTargets("irc://a/%23one")
        assertEquals(listOf("irc://b/%23two"), forwardTargets)

        val reverseTargets = bridgeService.getCopyTargets("irc://b/%23two")
        assertEquals(listOf("irc://a/%23one"), reverseTargets)
    }

    @Test
    fun `bridge copy rejects third party joining existing pair`() {
        bridgeService.copy("irc://a/%23one", "irc://b/%23two")

        val result =
            eventGateway.process(
                addressedMessage("bridge copy irc://c/%23three irc://b/%23two", user = adminUser)
            )
        assertInstanceOf(OperationResult.Error::class.java, result)
        val message = (result as OperationResult.Error).message
        assertTrue(message.contains("already paired"))
    }

    @Test
    fun `bridge copy rejects self-bridge`() {
        val result =
            eventGateway.process(
                addressedMessage("bridge copy irc://a/%23one irc://a/%23one", user = adminUser)
            )
        assertInstanceOf(OperationResult.Error::class.java, result)
        assertTrue((result as OperationResult.Error).message.contains("itself"))
    }

    @Test
    fun `bridge remove removes one direction`() {
        bridgeService.copy("irc://a/%23one", "irc://b/%23two")
        bridgeService.copy("irc://b/%23two", "irc://a/%23one")

        val result =
            eventGateway.process(
                addressedMessage("bridge remove irc://a/%23one irc://b/%23two", user = adminUser)
            )
        assertInstanceOf(OperationResult.Success::class.java, result)

        // Forward direction removed, reverse still active
        assertTrue(bridgeService.getCopyTargets("irc://a/%23one").isEmpty())
        assertEquals(listOf("irc://a/%23one"), bridgeService.getCopyTargets("irc://b/%23two"))
    }

    @Test
    fun `bridge remove both directions dissolves pair`() {
        bridgeService.copy("irc://a/%23one", "irc://b/%23two")

        val result =
            eventGateway.process(
                addressedMessage("bridge remove irc://a/%23one irc://b/%23two", user = adminUser)
            )
        assertInstanceOf(OperationResult.Success::class.java, result)

        // Pair is dissolved, both URIs are now free
        assertTrue(bridgeService.findPairFor("irc://a/%23one") == null)
        assertTrue(bridgeService.findPairFor("irc://b/%23two") == null)
    }

    @Test
    fun `bridge list shows all pairs`() {
        bridgeService.copy("irc://a/%23one", "irc://b/%23two")

        val result = eventGateway.process(addressedMessage("bridge list", user = adminUser))
        assertInstanceOf(OperationResult.Success::class.java, result)
        val payload = (result as OperationResult.Success).payload.toString()
        assertTrue(payload.contains("Bridge pairs:"))
        assertTrue(payload.contains("irc://a/%23one"))
        assertTrue(payload.contains("irc://b/%23two"))
    }

    @Test
    fun `bridge list with no pairs returns empty message`() {
        val result = eventGateway.process(addressedMessage("bridge list", user = adminUser))
        assertInstanceOf(OperationResult.Success::class.java, result)
        assertTrue(
            (result as OperationResult.Success)
                .payload
                .toString()
                .contains("No bridge pairs configured")
        )
    }

    @Test
    fun `bridge copy operation cross-posts user messages`() {
        val sourceUri =
            Provenance(protocol = Protocol.IRC, serviceId = "testnet", replyTo = "#test").encode()
        bridgeService.copy(sourceUri, "irc://othernet/%23other")

        // Unaddressed message in a bridged channel
        val message =
            MessageBuilder.withPayload("hello world")
                .setHeader(Provenance.HEADER, provenance("#test", "testnet"))
                .setHeader(Provenance.ADDRESSED, false)
                .setHeader("nick", "testuser")
                .build()

        // BridgeCopyOperation returns null, so the chain continues to NotHandled
        val result = eventGateway.process(message)
        assertInstanceOf(OperationResult.NotHandled::class.java, result)
    }

    @Test
    fun `bridge attribution uses minimal protocol and nick format`() {
        val sourceUri =
            Provenance(protocol = Protocol.IRC, serviceId = "testnet", replyTo = "#test").encode()
        bridgeService.copy(sourceUri, "irc://othernet/%23other")

        val captured = mutableListOf<String>()
        val handler =
            org.springframework.messaging.MessageHandler { msg ->
                val result = msg.payload
                if (result is OperationResult.Success) {
                    captured.add(result.payload.toString())
                }
            }
        egressChannel.subscribe(handler)
        try {
            val message =
                MessageBuilder.withPayload("hello world")
                    .setHeader(Provenance.HEADER, provenance("#test", "testnet"))
                    .setHeader(Provenance.ADDRESSED, false)
                    .setHeader("nick", "testuser")
                    .build()
            eventGateway.process(message)

            assertTrue(captured.any { it.contains("<irc:testuser>") })
            assertTrue(captured.any { it.contains("hello world") })
        } finally {
            egressChannel.unsubscribe(handler)
        }
    }

    @Test
    fun `bridge prefers displayText header over raw payload`() {
        val sourceUri =
            Provenance(protocol = Protocol.IRC, serviceId = "testnet", replyTo = "#test").encode()
        bridgeService.copy(sourceUri, "irc://othernet/%23other")

        val captured = mutableListOf<String>()
        val handler =
            org.springframework.messaging.MessageHandler { msg ->
                val result = msg.payload
                if (result is OperationResult.Success) {
                    captured.add(result.payload.toString())
                }
            }
        egressChannel.subscribe(handler)
        try {
            val message =
                MessageBuilder.withPayload("hello <@857685522533056522>")
                    .setHeader(Provenance.HEADER, provenance("#test", "testnet"))
                    .setHeader(Provenance.ADDRESSED, false)
                    .setHeader("nick", "testuser")
                    .setHeader("displayText", "hello @josephbottinger")
                    .build()
            eventGateway.process(message)

            // Should use the display-resolved text, not the raw mention
            assertTrue(captured.any { it.contains("hello @josephbottinger") })
            assertTrue(captured.none { it.contains("<@857685522533056522>") })
        } finally {
            egressChannel.unsubscribe(handler)
        }
    }

    @Test
    fun `bridged messages are not re-copied`() {
        val sourceUri =
            Provenance(protocol = Protocol.IRC, serviceId = "testnet", replyTo = "#test").encode()
        bridgeService.copy(sourceUri, "irc://othernet/%23other")

        val message =
            MessageBuilder.withPayload("already bridged content")
                .setHeader(
                    Provenance.HEADER,
                    provenance("#test", "testnet")
                        .copy(metadata = mapOf(Provenance.BRIDGED to true)),
                )
                .setHeader(Provenance.ADDRESSED, false)
                .setHeader("nick", "testuser")
                .build()

        val result = eventGateway.process(message)
        assertInstanceOf(OperationResult.NotHandled::class.java, result)
    }

    @Test
    fun `bridge copies action messages with isAction header`() {
        val sourceUri =
            Provenance(protocol = Protocol.IRC, serviceId = "testnet", replyTo = "#test").encode()
        bridgeService.copy(sourceUri, "irc://othernet/%23other")

        val captured = mutableListOf<String>()
        val handler =
            org.springframework.messaging.MessageHandler { msg ->
                val result = msg.payload
                if (result is OperationResult.Success) {
                    captured.add(result.payload.toString())
                }
            }
        egressChannel.subscribe(handler)
        try {
            val message =
                MessageBuilder.withPayload("* testuser waves")
                    .setHeader(Provenance.HEADER, provenance("#test", "testnet"))
                    .setHeader(Provenance.ADDRESSED, false)
                    .setHeader(Provenance.IS_ACTION, true)
                    .setHeader("nick", "testuser")
                    .build()
            eventGateway.process(message)

            assertTrue(captured.any { it.contains("<irc:testuser>") })
            assertTrue(captured.any { it.contains("* testuser waves") })
        } finally {
            egressChannel.unsubscribe(handler)
        }
    }

    @Test
    fun `one-way bridge does not copy in reverse direction`() {
        // Copy from A to B only
        bridgeService.copy("irc://a/%23one", "irc://b/%23two")

        // Message arriving at B should NOT copy to A
        val targets = bridgeService.getCopyTargets("irc://b/%23two")
        assertTrue(targets.isEmpty())
    }

    @Test
    fun `bridge info shows bridge for current channel`() {
        val sourceUri =
            Provenance(protocol = Protocol.IRC, serviceId = "testnet", replyTo = "#test").encode()
        bridgeService.copy(sourceUri, "irc://othernet/%23other")

        val result = eventGateway.process(addressedMessage("bridge info", user = regularUser))
        assertInstanceOf(OperationResult.Success::class.java, result)
        val payload = (result as OperationResult.Success).payload.toString()
        assertTrue(payload.contains("copy to"))
        assertTrue(payload.contains("irc://othernet/%23other"))
    }

    @Test
    fun `bridge info shows no bridge when channel is not bridged`() {
        val result = eventGateway.process(addressedMessage("bridge info", user = regularUser))
        assertInstanceOf(OperationResult.Success::class.java, result)
        assertTrue(
            (result as OperationResult.Success).payload.toString().contains("No bridge configured")
        )
    }

    @Test
    fun `bridge info shows bidirectional bridge`() {
        val sourceUri =
            Provenance(protocol = Protocol.IRC, serviceId = "testnet", replyTo = "#test").encode()
        bridgeService.copy(sourceUri, "irc://othernet/%23other")
        bridgeService.copy("irc://othernet/%23other", sourceUri)

        val result = eventGateway.process(addressedMessage("bridge info", user = regularUser))
        assertInstanceOf(OperationResult.Success::class.java, result)
        val payload = (result as OperationResult.Success).payload.toString()
        assertTrue(payload.contains("copy to"))
        assertTrue(payload.contains("copy from"))
    }

    @Test
    fun `bridge provenance returns current channel URI without admin role`() {
        val result = eventGateway.process(addressedMessage("bridge provenance", user = regularUser))
        assertInstanceOf(OperationResult.Success::class.java, result)
        val uri = (result as OperationResult.Success).payload.toString()
        assertTrue(uri.startsWith("irc://"))
        assertTrue(uri.contains("testnet"))
    }

    @Test
    fun `bridge provenance works without any user at all`() {
        val result = eventGateway.process(addressedMessage("bridge provenance"))
        assertInstanceOf(OperationResult.Success::class.java, result)
        val uri = (result as OperationResult.Success).payload.toString()
        assertTrue(uri.startsWith("irc://"))
    }

    @Test
    fun `bridge copies reaction-format action messages`() {
        val sourceUri =
            Provenance(protocol = Protocol.IRC, serviceId = "testnet", replyTo = "#test").encode()
        bridgeService.copy(sourceUri, "irc://othernet/%23other")

        val captured = mutableListOf<String>()
        val handler =
            org.springframework.messaging.MessageHandler { msg ->
                val result = msg.payload
                if (result is OperationResult.Success) {
                    captured.add(result.payload.toString())
                }
            }
        egressChannel.subscribe(handler)
        try {
            val message =
                MessageBuilder.withPayload("* alice reacted with :thumbsup:")
                    .setHeader(Provenance.HEADER, provenance("#test", "testnet"))
                    .setHeader(Provenance.ADDRESSED, false)
                    .setHeader(Provenance.IS_ACTION, true)
                    .setHeader("nick", "alice")
                    .build()
            eventGateway.process(message)

            assertTrue(captured.any { it.contains("<irc:alice>") })
            assertTrue(captured.any { it.contains("* alice reacted with :thumbsup:") })
        } finally {
            egressChannel.unsubscribe(handler)
        }
    }
}
