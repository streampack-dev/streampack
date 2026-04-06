/* Joseph B. Ottinger (C)2026 */
package dev.streampack.karma.operation

import dev.streampack.core.integration.EventGateway
import dev.streampack.core.model.OperationResult
import dev.streampack.core.model.Protocol
import dev.streampack.core.model.Provenance
import dev.streampack.core.service.OperationConfigService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.messaging.support.MessageBuilder
import org.springframework.transaction.annotation.Transactional

@SpringBootTest
@Transactional
class KarmaOperationTests {

    @Autowired lateinit var eventGateway: EventGateway
    @Autowired lateinit var operationConfigService: OperationConfigService

    private fun provenance() =
        Provenance(protocol = Protocol.CONSOLE, serviceId = "", replyTo = "local")

    /** Builds an unaddressed karma message (no nick header) */
    private fun karmaMessage(text: String) =
        MessageBuilder.withPayload(text).setHeader(Provenance.HEADER, provenance()).build()

    /** Builds an unaddressed karma message with a sender nick header */
    private fun karmaMessage(text: String, nick: String) =
        MessageBuilder.withPayload(text)
            .setHeader(Provenance.HEADER, provenance())
            .setHeader("nick", nick)
            .build()

    @Test
    fun `foo++ returns success with karma of 1`() {
        val result = eventGateway.process(karmaMessage("foo++"))
        assertInstanceOf(OperationResult.Success::class.java, result)
        assertEquals("foo now has karma of 1.", (result as OperationResult.Success).payload)
    }

    @Test
    fun `foo-- returns success with karma of -1`() {
        val result = eventGateway.process(karmaMessage("foo--"))
        assertInstanceOf(OperationResult.Success::class.java, result)
        assertEquals("foo now has karma of -1.", (result as OperationResult.Success).payload)
    }

    @Test
    fun `c++-- parses as decrement on c++`() {
        val result = eventGateway.process(karmaMessage("c++--"))
        assertInstanceOf(OperationResult.Success::class.java, result)
        val payload = (result as OperationResult.Success).payload as String
        assertEquals("c++ now has karma of -1.", payload)
    }

    @Test
    fun `c+++ parses as increment on c+`() {
        val result = eventGateway.process(karmaMessage("c+++"))
        assertInstanceOf(OperationResult.Success::class.java, result)
        val payload = (result as OperationResult.Success).payload as String
        assertEquals("c+ now has karma of 1.", payload)
    }

    @Test
    fun `arrow fix prevents false match on arrow`() {
        val result = eventGateway.process(karmaMessage("foo --> bar"))
        assertInstanceOf(OperationResult.NotHandled::class.java, result)
    }

    @Test
    fun `subject over max length returns not handled`() {
        val longSubject = "a".repeat(46)
        val result = eventGateway.process(karmaMessage("$longSubject++"))
        assertInstanceOf(OperationResult.NotHandled::class.java, result)
    }

    @Test
    fun `subject at max length is handled`() {
        val subject = "a".repeat(45)
        val result = eventGateway.process(karmaMessage("$subject++"))
        assertInstanceOf(OperationResult.Success::class.java, result)
    }

    @Test
    fun `empty subject returns not handled`() {
        val result = eventGateway.process(karmaMessage("++"))
        assertInstanceOf(OperationResult.NotHandled::class.java, result)
    }

    @Test
    fun `self-karma increment flips to decrement`() {
        val result = eventGateway.process(karmaMessage("testuser++", "testuser"))
        assertInstanceOf(OperationResult.Success::class.java, result)
        val payload = (result as OperationResult.Success).payload as String
        assertEquals("You can't increment your own karma! Your karma is now -1.", payload)
    }

    @Test
    fun `self-karma decrement stays as decrement`() {
        val result = eventGateway.process(karmaMessage("testuser--", "testuser"))
        assertInstanceOf(OperationResult.Success::class.java, result)
        val payload = (result as OperationResult.Success).payload as String
        assertEquals("Your karma is now -1.", payload)
    }

    @Test
    fun `immune subject returns not handled`() {
        val result = eventGateway.process(karmaMessage("immune_bot++"))
        assertInstanceOf(OperationResult.NotHandled::class.java, result)
    }

    @Test
    fun `karma query returns karma info`() {
        // First set some karma
        eventGateway.process(karmaMessage("eclipse++"))
        eventGateway.process(karmaMessage("eclipse++"))

        val result = eventGateway.process(karmaMessage("karma eclipse"))
        assertInstanceOf(OperationResult.Success::class.java, result)
        assertEquals("eclipse has karma of 2.", (result as OperationResult.Success).payload)
    }

    @Test
    fun `karma query for unknown subject returns no karma data`() {
        val result = eventGateway.process(karmaMessage("karma unknown_thing"))
        assertInstanceOf(OperationResult.Success::class.java, result)
        assertEquals(
            "unknown_thing has no karma data.",
            (result as OperationResult.Success).payload,
        )
    }

    @Test
    fun `karma self-query uses personalized response`() {
        eventGateway.process(karmaMessage("myuser++"))
        val result = eventGateway.process(karmaMessage("karma myuser", "myuser"))
        assertInstanceOf(OperationResult.Success::class.java, result)
        val payload = (result as OperationResult.Success).payload as String
        assertEquals("myuser, you have karma of 1.", payload)
    }

    @Test
    fun `non-karma message returns not handled`() {
        val result = eventGateway.process(karmaMessage("just a regular message"))
        assertInstanceOf(OperationResult.NotHandled::class.java, result)
    }

    @Test
    fun `trailing text after predicate is discarded`() {
        val result = eventGateway.process(karmaMessage("kotlin++ is great"))
        assertInstanceOf(OperationResult.Success::class.java, result)
        assertEquals("kotlin now has karma of 1.", (result as OperationResult.Success).payload)
    }

    @Test
    fun `colon completion suffix is stripped from subject`() {
        val result = eventGateway.process(karmaMessage("jreicher: ++"))
        assertInstanceOf(OperationResult.Success::class.java, result)
        assertEquals("jreicher now has karma of 1.", (result as OperationResult.Success).payload)
    }

    @Test
    fun `colon without space is stripped from subject`() {
        val result = eventGateway.process(karmaMessage("jreicher:++"))
        assertInstanceOf(OperationResult.Success::class.java, result)
        assertEquals("jreicher now has karma of 1.", (result as OperationResult.Success).payload)
    }

    @Test
    fun `comma completion suffix is stripped from subject`() {
        val result = eventGateway.process(karmaMessage("jreicher, ++"))
        assertInstanceOf(OperationResult.Success::class.java, result)
        assertEquals("jreicher now has karma of 1.", (result as OperationResult.Success).payload)
    }

    @Test
    fun `neutral karma displays correctly`() {
        eventGateway.process(karmaMessage("balanced++"))
        eventGateway.process(karmaMessage("balanced--"))
        val result = eventGateway.process(karmaMessage("karma balanced"))
        assertInstanceOf(OperationResult.Success::class.java, result)
        assertEquals("balanced has neutral karma.", (result as OperationResult.Success).payload)
    }

    // -- Karma ranking --

    @Test
    fun `top karma with no data returns empty message`() {
        val result = eventGateway.process(karmaMessage("top karma"))
        assertInstanceOf(OperationResult.Success::class.java, result)
        assertEquals("No karma data yet.", (result as OperationResult.Success).payload)
    }

    @Test
    fun `top karma returns subjects in descending order`() {
        eventGateway.process(karmaMessage("alpha++"))
        eventGateway.process(karmaMessage("alpha++"))
        eventGateway.process(karmaMessage("alpha++"))
        eventGateway.process(karmaMessage("bravo++"))
        eventGateway.process(karmaMessage("bravo++"))
        eventGateway.process(karmaMessage("charlie++"))

        val result = eventGateway.process(karmaMessage("top karma"))
        assertInstanceOf(OperationResult.Success::class.java, result)
        val payload = (result as OperationResult.Success).payload as String
        assertTrue(payload.startsWith("Top karma: "))
        assertTrue(payload.indexOf("alpha") < payload.indexOf("bravo"))
        assertTrue(payload.indexOf("bravo") < payload.indexOf("charlie"))
    }

    @Test
    fun `bottom karma returns subjects in ascending order`() {
        eventGateway.process(karmaMessage("hero++"))
        eventGateway.process(karmaMessage("hero++"))
        eventGateway.process(karmaMessage("villain--"))
        eventGateway.process(karmaMessage("villain--"))
        eventGateway.process(karmaMessage("sidekick--"))

        val result = eventGateway.process(karmaMessage("bottom karma"))
        assertInstanceOf(OperationResult.Success::class.java, result)
        val payload = (result as OperationResult.Success).payload as String
        assertTrue(payload.startsWith("Bottom karma: "))
        assertTrue(payload.indexOf("villain") < payload.indexOf("sidekick"))
        assertTrue(payload.indexOf("sidekick") < payload.indexOf("hero"))
    }

    @Test
    fun `top karma with explicit count`() {
        eventGateway.process(karmaMessage("first++"))
        eventGateway.process(karmaMessage("first++"))
        eventGateway.process(karmaMessage("first++"))
        eventGateway.process(karmaMessage("second++"))
        eventGateway.process(karmaMessage("second++"))
        eventGateway.process(karmaMessage("third++"))

        val result = eventGateway.process(karmaMessage("top 2 karma"))
        assertInstanceOf(OperationResult.Success::class.java, result)
        val payload = (result as OperationResult.Success).payload as String
        assertTrue(payload.contains("first"))
        assertTrue(payload.contains("second"))
        assertTrue(!payload.contains("third"))
    }

    @Test
    fun `top karma count after keyword`() {
        eventGateway.process(karmaMessage("aaa++"))
        eventGateway.process(karmaMessage("aaa++"))
        eventGateway.process(karmaMessage("bbb++"))

        val result = eventGateway.process(karmaMessage("top karma 1"))
        assertInstanceOf(OperationResult.Success::class.java, result)
        val payload = (result as OperationResult.Success).payload as String
        assertTrue(payload.contains("aaa"))
        assertTrue(!payload.contains("bbb"))
    }

    @Test
    fun `neutral karma subjects are excluded from ranking`() {
        eventGateway.process(karmaMessage("winner++"))
        eventGateway.process(karmaMessage("neutral++"))
        eventGateway.process(karmaMessage("neutral--"))

        val result = eventGateway.process(karmaMessage("top karma"))
        assertInstanceOf(OperationResult.Success::class.java, result)
        val payload = (result as OperationResult.Success).payload as String
        assertTrue(payload.contains("winner"))
        assertTrue(!payload.contains("neutral"))
    }

    // -- Prose dash neutralization --

    @Test
    fun `prose dash with spaces on both sides is not karma`() {
        val result = eventGateway.process(karmaMessage("foo says Y -- and I don't like that"))
        assertInstanceOf(OperationResult.NotHandled::class.java, result)
    }

    @Test
    fun `attached dash without leading space is karma`() {
        val result = eventGateway.process(karmaMessage("Hey-- I don't like XYZ"))
        assertInstanceOf(OperationResult.Success::class.java, result)
        val payload = (result as OperationResult.Success).payload as String
        assertEquals("Hey now has karma of -1.", payload)
    }

    @Test
    fun `attached dash without trailing space is karma`() {
        val result = eventGateway.process(karmaMessage("foo --bar"))
        assertInstanceOf(OperationResult.NotHandled::class.java, result)
    }

    @Test
    fun `prose dash becomes karma when ignoreEmdash is disabled`() {
        val provenanceUri = provenance().encode()
        operationConfigService.setConfigValue(provenanceUri, "karma", "ignoreEmdash", "false")

        val result = eventGateway.process(karmaMessage("foo says Y -- and I don't like that"))
        assertInstanceOf(OperationResult.Success::class.java, result)
    }

    // -- Program flag rejection --

    @Test
    fun `program flag --verbose is not karma`() {
        val result = eventGateway.process(karmaMessage("--verbose"))
        assertInstanceOf(OperationResult.NotHandled::class.java, result)
    }

    @Test
    fun `program flag --ignore-capability is not karma`() {
        val result = eventGateway.process(karmaMessage("--ignore-capability"))
        assertInstanceOf(OperationResult.NotHandled::class.java, result)
    }

    @Test
    fun `program flag in sentence is not karma`() {
        val result = eventGateway.process(karmaMessage("use --no-verify to skip"))
        assertInstanceOf(OperationResult.NotHandled::class.java, result)
    }

    // -- Language reference guard --

    @Test
    fun `multi-word subject ending in C is rejected`() {
        val result = eventGateway.process(karmaMessage("I really like C++"))
        assertInstanceOf(OperationResult.NotHandled::class.java, result)
    }

    @Test
    fun `multi-word subject ending in J is rejected`() {
        val result = eventGateway.process(karmaMessage("you should learn J++"))
        assertInstanceOf(OperationResult.NotHandled::class.java, result)
    }

    @Test
    fun `bare C++ is still karma on C`() {
        val result = eventGateway.process(karmaMessage("C++"))
        assertInstanceOf(OperationResult.Success::class.java, result)
        assertEquals("C now has karma of 1.", (result as OperationResult.Success).payload)
    }

    @Test
    fun `single-word C++ is still karma`() {
        val result = eventGateway.process(karmaMessage("C++ is great"))
        assertInstanceOf(OperationResult.Success::class.java, result)
    }

    // -- Helper --

    private fun assertTrue(condition: Boolean) {
        org.junit.jupiter.api.Assertions.assertTrue(condition)
    }
}
