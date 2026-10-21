/* Joseph B. Ottinger (C)2026 */
package dev.streampack.polling.operation

import dev.streampack.core.model.OperationOutcome
import dev.streampack.core.model.OperationResult
import dev.streampack.core.model.Protocol
import dev.streampack.core.model.Provenance
import dev.streampack.core.model.Role
import dev.streampack.core.model.UserPrincipal
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.messaging.support.MessageBuilder

/** Verifies command matching, auth checking, and to/for parsing in the base class */
class PollingSourceManagementOperationTests {

    private val adminUser =
        UserPrincipal(
            id = UUID.randomUUID(),
            username = "admin",
            displayName = "Admin",
            role = Role.ADMIN,
        )

    private val guestProvenance =
        Provenance(protocol = Protocol.IRC, serviceId = "libera", replyTo = "#java")

    private val adminProvenance =
        Provenance(
            protocol = Protocol.IRC,
            serviceId = "libera",
            replyTo = "#java",
            user = adminUser,
        )

    private fun guestMessage(text: String) =
        MessageBuilder.withPayload(text).setHeader(Provenance.HEADER, guestProvenance).build()

    private fun adminMessage(text: String) =
        MessageBuilder.withPayload(text).setHeader(Provenance.HEADER, adminProvenance).build()

    /** Concrete test subclass that records calls to handler methods */
    class TestManagementOperation : PollingSourceManagementOperation() {
        override val commandPrefix = "test"
        override val priority = 50

        var lastCall: String = ""
        var lastIdentifier: String = ""
        var lastDestination: String = ""

        override fun onList(): OperationOutcome {
            lastCall = "list"
            return OperationResult.Success("listed")
        }

        override fun onSubscribe(identifier: String, destinationUri: String): OperationOutcome {
            lastCall = "subscribe"
            lastIdentifier = identifier
            lastDestination = destinationUri
            return OperationResult.Success("subscribed $identifier")
        }

        override fun onUnsubscribe(identifier: String, destinationUri: String): OperationOutcome {
            lastCall = "unsubscribe"
            lastIdentifier = identifier
            lastDestination = destinationUri
            return OperationResult.Success("unsubscribed $identifier")
        }

        override fun onSubscriptions(destinationUri: String): OperationOutcome {
            lastCall = "subscriptions"
            lastDestination = destinationUri
            return OperationResult.Success("subscriptions for $destinationUri")
        }

        override fun onRemove(identifier: String): OperationOutcome {
            lastCall = "remove"
            lastIdentifier = identifier
            return OperationResult.Success("removed $identifier")
        }
    }

    private val operation = TestManagementOperation()

    @Test
    fun `list command matches without admin role`() {
        assertTrue(operation.canHandle("test list", guestMessage("test list")))
    }

    @Test
    fun `subscriptions command matches without admin role`() {
        assertTrue(operation.canHandle("test subscriptions", guestMessage("test subscriptions")))
    }

    @Test
    fun `subscriptions for command matches without admin role`() {
        val text = "test subscriptions for irc://libera/%23java"
        assertTrue(operation.canHandle(text, guestMessage(text)))
    }

    @Test
    fun `mutations are taken from everyone, so a non-admin gets a reply`() {
        for (text in listOf("test subscribe foo", "test unsubscribe foo", "test remove foo")) {
            assertTrue(operation.canHandle(text, guestMessage(text)), text)
            assertTrue(operation.canHandle(text, adminMessage(text)), text)
        }
    }

    @Test
    fun `subscribe from a non-admin is refused with a reply`() {
        val result = operation.handle("test subscribe foo", guestMessage("test subscribe foo"))
        assertInstanceOf(OperationResult.Error::class.java, result)
        assertEquals(
            "Only an admin can subscribe test sources",
            (result as OperationResult.Error).message,
        )
        assertEquals("", operation.lastCall)
    }

    @Test
    fun `unsubscribe and remove from a non-admin are refused with a reply`() {
        val unsubscribe =
            operation.handle("test unsubscribe foo", guestMessage("test unsubscribe foo"))
        assertEquals(
            "Only an admin can unsubscribe test sources",
            (unsubscribe as OperationResult.Error).message,
        )
        val remove = operation.handle("test remove foo", guestMessage("test remove foo"))
        assertEquals(
            "Only an admin can remove test sources",
            (remove as OperationResult.Error).message,
        )
        assertEquals("", operation.lastCall)
    }

    @Test
    fun `the refusal names the subclass's own noun`() {
        val feeds =
            object : PollingSourceManagementOperation() {
                override val commandPrefix = "feed"
                override val sourceNoun = "feeds"
                override val priority = 50

                override fun onList() = OperationResult.Success("listed")

                override fun onSubscribe(identifier: String, destinationUri: String) =
                    OperationResult.Success("subscribed")

                override fun onUnsubscribe(identifier: String, destinationUri: String) =
                    OperationResult.Success("unsubscribed")

                override fun onSubscriptions(destinationUri: String) =
                    OperationResult.Success("subscriptions")

                override fun onRemove(identifier: String) = OperationResult.Success("removed")
            }
        val result = feeds.handle("feed subscribe x", guestMessage("feed subscribe x"))
        assertEquals("Only an admin can subscribe feeds", (result as OperationResult.Error).message)
    }

    @Test
    fun `subscriptions command is still open to everyone`() {
        val result = operation.handle("test subscriptions", guestMessage("test subscriptions"))
        assertInstanceOf(OperationResult.Success::class.java, result)
    }

    @Test
    fun `unrelated text does not match`() {
        assertFalse(operation.canHandle("hello world", guestMessage("hello world")))
    }

    @Test
    fun `list dispatches to onList`() {
        val result = operation.handle("test list", adminMessage("test list"))
        assertEquals("list", operation.lastCall)
        assertInstanceOf(OperationResult.Success::class.java, result)
        assertEquals("listed", (result as OperationResult.Success).payload)
    }

    @Test
    fun `subscribe dispatches with provenance destination`() {
        operation.handle("test subscribe myrepo", adminMessage("test subscribe myrepo"))
        assertEquals("subscribe", operation.lastCall)
        assertEquals("myrepo", operation.lastIdentifier)
        assertEquals(adminProvenance.encode(), operation.lastDestination)
    }

    @Test
    fun `subscribe with explicit target parses to destination`() {
        val text = "test subscribe myrepo to irc://oftc/%23kotlin"
        operation.handle(text, adminMessage(text))
        assertEquals("subscribe", operation.lastCall)
        assertEquals("myrepo", operation.lastIdentifier)
        assertEquals("irc://oftc/%23kotlin", operation.lastDestination)
    }

    @Test
    fun `unsubscribe dispatches with provenance destination`() {
        operation.handle("test unsubscribe myrepo", adminMessage("test unsubscribe myrepo"))
        assertEquals("unsubscribe", operation.lastCall)
        assertEquals("myrepo", operation.lastIdentifier)
        assertEquals(adminProvenance.encode(), operation.lastDestination)
    }

    @Test
    fun `unsubscribe with explicit target parses to destination`() {
        val text = "test unsubscribe myrepo to irc://oftc/%23kotlin"
        operation.handle(text, adminMessage(text))
        assertEquals("unsubscribe", operation.lastCall)
        assertEquals("myrepo", operation.lastIdentifier)
        assertEquals("irc://oftc/%23kotlin", operation.lastDestination)
    }

    @Test
    fun `subscriptions dispatches with provenance destination`() {
        operation.handle("test subscriptions", adminMessage("test subscriptions"))
        assertEquals("subscriptions", operation.lastCall)
        assertEquals(adminProvenance.encode(), operation.lastDestination)
    }

    @Test
    fun `subscriptions for dispatches with explicit destination`() {
        val text = "test subscriptions for irc://oftc/%23kotlin"
        operation.handle(text, adminMessage(text))
        assertEquals("subscriptions", operation.lastCall)
        assertEquals("irc://oftc/%23kotlin", operation.lastDestination)
    }

    @Test
    fun `remove dispatches to onRemove`() {
        operation.handle("test remove myrepo", adminMessage("test remove myrepo"))
        assertEquals("remove", operation.lastCall)
        assertEquals("myrepo", operation.lastIdentifier)
    }

    @Test
    fun `command matching is case insensitive`() {
        assertTrue(operation.canHandle("Test List", guestMessage("Test List")))
        assertTrue(operation.canHandle("TEST SUBSCRIBE foo", adminMessage("TEST SUBSCRIBE foo")))
    }
}
