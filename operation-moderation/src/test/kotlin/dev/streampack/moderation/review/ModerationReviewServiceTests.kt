/* Joseph B. Ottinger (C)2026 */
package dev.streampack.moderation.review

import dev.streampack.ai.config.AiProperties
import dev.streampack.ai.service.AiService
import dev.streampack.ai.service.AiStructuredResponse
import dev.streampack.core.integration.EventGateway
import dev.streampack.core.model.Protocol
import dev.streampack.core.model.Provenance
import dev.streampack.core.service.ChannelControlService
import dev.streampack.core.service.DirectConversations
import dev.streampack.core.service.MessageLogService
import dev.streampack.moderation.config.ModerationProperties
import dev.streampack.moderation.model.ReportStatus
import dev.streampack.moderation.model.Signal
import dev.streampack.moderation.repository.ModerationReportRepository
import dev.streampack.moderation.signal.ModerationScores
import dev.streampack.test.ResetDatabaseBeforeEach
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.ai.chat.model.ChatModel
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.support.DefaultListableBeanFactory
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.messaging.support.MessageBuilder

/**
 * The hourly review (#150), with a stand-in for the model that records every call: which model it
 * went to, and what it was sent.
 */
@SpringBootTest
@ResetDatabaseBeforeEach
class ModerationReviewServiceTests {

    /** One call to the model: the model it went to ("default" or "moderation"), and the prompt. */
    data class Call(val model: String, val system: String, val prompt: String)

    class RecordingAi :
        AiService(Mockito.mock(ChatModel::class.java), AiProperties(enabled = true)) {
        val calls = CopyOnWriteArrayList<Call>()
        var verdict: ModerationReviewService.Verdict? =
            ModerationReviewService.Verdict(true, "Insults bob repeatedly.", listOf())

        override fun moderation(): AiService = OnModel("moderation")

        override fun prompt(systemInstruction: String, userPrompt: String): String? {
            calls += Call("default", systemInstruction, userPrompt)
            return null
        }

        override fun <T : Any> promptForObjectWithRaw(
            systemInstruction: String,
            userPrompt: String,
            responseType: Class<T>,
        ): AiStructuredResponse<T> {
            calls += Call("default", systemInstruction, userPrompt)
            return AiStructuredResponse(null, null)
        }

        inner class OnModel(private val model: String) :
            AiService(Mockito.mock(ChatModel::class.java), AiProperties(enabled = true)) {
            override fun prompt(systemInstruction: String, userPrompt: String): String? {
                calls += Call(model, systemInstruction, userPrompt)
                return null
            }

            @Suppress("UNCHECKED_CAST")
            override fun <T : Any> promptForObjectWithRaw(
                systemInstruction: String,
                userPrompt: String,
                responseType: Class<T>,
            ): AiStructuredResponse<T> {
                calls += Call(model, systemInstruction, userPrompt)
                return AiStructuredResponse(verdict as T?, verdict?.toString())
            }
        }
    }

    @TestConfiguration
    class Config {
        @Bean @Primary fun recordingAi() = RecordingAi()
    }

    @Autowired lateinit var eventGateway: EventGateway
    @Autowired lateinit var ai: RecordingAi
    @Autowired lateinit var review: ModerationReviewService
    @Autowired lateinit var scores: ModerationScores
    @Autowired lateinit var reports: ModerationReportRepository
    @Autowired lateinit var messageLogService: MessageLogService
    @Autowired lateinit var channelControlService: ChannelControlService
    @Autowired lateinit var properties: ModerationProperties
    @Autowired lateinit var directConversations: DirectConversations

    private val channel = "#moderation"
    private val channelUri = "irc://testnet/%23moderation"

    @BeforeEach
    fun reset() {
        scores.clear()
        ai.calls.clear()
        ai.verdict = ModerationReviewService.Verdict(true, "Insults bob repeatedly.", listOf())
    }

    private fun say(nick: String, text: String, target: String = channel) {
        eventGateway.send(
            MessageBuilder.withPayload(text)
                .setHeader(
                    Provenance.HEADER,
                    Provenance(protocol = Protocol.IRC, serviceId = "testnet", replyTo = target),
                )
                .setHeader(Provenance.ADDRESSED, false)
                .setHeader("nick", nick)
                .build()
        )
    }

    @Test
    fun `nobody over the threshold means no call at all`() {
        say("bob", "the build is red again")
        say("alice", "this fucking build, shit")
        say("alice", "damn it, the tests are flaky")

        assertTrue(review.review().isEmpty())
        assertTrue(ai.calls.isEmpty())
        assertEquals(0, reports.count())
    }

    @Test
    fun `the review asks the moderation model about those over the threshold, with context`() {
        say("bob", "has anyone tried the new release?")
        say("troll", "bob: you're a fucking idiot")
        say("bob", "excuse me?")
        say("troll", "shut up bob, you moron")
        say("carol", "this damn build again")
        say("bob", "anyway, the release notes are up")
        // Said to the bot directly: never scored, never read
        say("troll", "bob is a fucking idiot and here is his secret DM text", target = "troll")
        // A direct line under the channel's own address is still never read
        messageLogService.logInbound(channelUri, "troll", "DIRECT-ONLY you idiot", direct = true)

        val made = review.review()

        assertEquals(1, ai.calls.size, "${ai.calls}")
        val call = ai.calls.single()
        assertEquals("moderation", call.model)
        assertTrue("Person to judge: troll" in call.prompt)
        assertTrue("bob: you're a fucking idiot" in call.prompt)
        assertTrue("has anyone tried the new release?" in call.prompt, "context before")
        assertTrue("excuse me?" in call.prompt, "context between")
        assertFalse("secret DM text" in call.prompt)
        assertFalse("DIRECT-ONLY" in call.prompt)

        val report = made.single()
        assertEquals(channelUri, report.provenanceUri)
        assertEquals("troll", report.sender)
        assertEquals("irc", report.protocol)
        assertEquals("testnet", report.serviceId)
        assertEquals(true, report.verdictAbusive)
        assertEquals("Insults bob repeatedly.", report.verdictReason)
        assertEquals(AiProperties().moderationModel, report.verdictModel)
        assertEquals(2, report.signals[Signal.AIMED_HOSTILITY.name])
        assertEquals(2, report.flaggedLineIds.size)
        assertTrue(report.excerptLineIds.size >= 5)
        assertEquals(ReportStatus.OPEN, report.status)

        assertTrue(review.review().isEmpty(), "reviewed once")
        assertEquals(1, ai.calls.size)
    }

    @Test
    fun `the lines that raised signals are sent even when the person kept talking`() {
        say("bob", "morning")
        say("troll", "bob: fuck you and the horse you rode in on")
        say("troll", "bob: you're full of shit")
        // More of their own lines afterwards than the review takes
        repeat(properties.reviewLines + 5) { say("troll", "anyway, chatter line $it") }

        val report = review.review().single()

        val prompt = ai.calls.single().prompt
        assertTrue("fuck you and the horse you rode in on" in prompt, prompt)
        assertTrue("you're full of shit" in prompt, prompt)
        assertTrue("morning" in prompt, "context before the trouble")
        assertTrue("chatter line ${properties.reviewLines + 4}" in prompt, "the latest line too")
        // Said this fast, the chatter floods: those lines are flagged too, but rank below the
        // insults, which are what's sent first
        assertTrue(report.flaggedLineIds.size >= 2)
        assertTrue(report.flaggedLineIds.all { it in report.excerptLineIds })
    }

    @Test
    fun `the lines the model cites are kept`() {
        say("troll", "you're an idiot, bob")
        say("bob", "what")
        say("troll", "fuck you bob")
        ai.verdict = ModerationReviewService.Verdict(true, "Hostile.", listOf(1, 2, 3))

        val report = review.review().single()
        // Line 2 is bob's: only the person's own lines are cited
        assertEquals(2, report.citedLineIds.size)
    }

    @Test
    fun `with AI off the report is recorded from the signals alone`() {
        val noAi =
            ModerationReviewService(
                scores,
                properties,
                messageLogService,
                channelControlService,
                directConversations,
                reports,
                DefaultListableBeanFactory().getBeanProvider(AiService::class.java),
                DefaultListableBeanFactory().getBeanProvider(AiProperties::class.java),
            )
        say("troll", "you're an idiot, bob")
        say("troll", "fuck you bob")

        val report = noAi.review().single()
        assertTrue(ai.calls.isEmpty())
        assertNull(report.verdictAbusive)
        assertNull(report.verdictReason)
        assertEquals(2, report.flaggedLineIds.size)
    }

    @Test
    fun `a channel that opted out is neither scored nor reviewed`() {
        channelControlService.getOrCreateOptions(channelUri)
        channelControlService.setFlag(channelUri, "moderated", false)
        say("troll", "you're an idiot, bob")
        say("troll", "fuck you bob")
        say("troll", "what a f4ggot")

        assertEquals(0.0, scores.scoreOf(channelUri, "troll"))
        assertTrue(review.review().isEmpty())
        assertTrue(ai.calls.isEmpty())
    }

    @Test
    fun `direct conversations are never scored`() {
        repeat(3) { say("troll", "fuck you, you idiot", target = "nevet") }
        assertEquals(0.0, scores.scoreOf("irc://testnet/nevet", "troll"))
        assertTrue(review.review().isEmpty())
        assertTrue(ai.calls.isEmpty())
    }
}
