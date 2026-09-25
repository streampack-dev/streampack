/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.auth

import dev.streampack.blog.model.LoginResponse
import dev.streampack.blog.model.OtpRequest
import dev.streampack.blog.model.OtpVerifyRequest
import dev.streampack.core.integration.EventGateway
import dev.streampack.core.model.CodeChannel
import dev.streampack.core.model.CodeIdentity
import dev.streampack.core.model.OperationResult
import dev.streampack.core.model.Protocol
import dev.streampack.core.model.Provenance
import dev.streampack.core.model.ResolvedRecipient
import dev.streampack.core.repository.ServiceBindingRepository
import dev.streampack.core.repository.UserRepository
import dev.streampack.core.service.CodeDelivery
import dev.streampack.test.TestSecurityConfiguration
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.messaging.support.MessageBuilder
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import org.springframework.transaction.annotation.Transactional

/**
 * One-time codes over a chat channel, exercised through a fake delivery that knows two users on a
 * server called `work`. Email stays open and unchanged; a chat identity the server does not know
 * gets the standard message and no delivery; a first verify creates the account.
 */
@SpringBootTest
@AutoConfigureMockMvc(addFilters = false)
@Import(TestSecurityConfiguration::class)
@Transactional
class ChannelNeutralOtpTests {

    @TestConfiguration
    class FakeChatConfig {
        @Bean fun fakeChatDelivery() = FakeChatDelivery()
    }

    class FakeChatDelivery : CodeDelivery {
        val delivered = CopyOnWriteArrayList<Pair<String, String>>()
        private val users =
            mapOf("alice" to ("u-alice" to "Alice Liddell"), "bob" to ("u-bob" to "Bob"))

        override val channel = CodeChannel.MATTERMOST

        override fun servers(): List<String> = listOf("work")

        override fun resolve(identity: CodeIdentity): ResolvedRecipient? {
            if (identity.server != "work") return null
            val (id, name) = users[identity.address.lowercase()] ?: return null
            return ResolvedRecipient(
                key = "work/$id",
                protocol = Protocol.MATTERMOST,
                serviceId = "work",
                externalIdentifier = id,
                username = identity.address.lowercase(),
                displayName = name,
            )
        }

        override fun deliver(recipient: ResolvedRecipient, code: String) {
            delivered += recipient.key to code
        }
    }

    @Autowired lateinit var eventGateway: EventGateway
    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var fake: FakeChatDelivery
    @Autowired lateinit var userRepository: UserRepository
    @Autowired lateinit var bindingRepository: ServiceBindingRepository

    private fun message(payload: Any) =
        MessageBuilder.withPayload(payload)
            .setHeader(
                Provenance.HEADER,
                Provenance(protocol = Protocol.HTTP, serviceId = "blog", replyTo = "test"),
            )
            .build()

    @BeforeEach fun clear() = fake.delivered.clear()

    @Test
    fun `a known chat user gets a code, verifies it, and an account is created without an email`() {
        val request =
            eventGateway.process(
                message(
                    OtpRequest(channel = CodeChannel.MATTERMOST, server = "work", address = "Alice")
                )
            )
        assertInstanceOf(OperationResult.Success::class.java, request)
        assertEquals(1, fake.delivered.size)
        val (key, code) = fake.delivered.single()
        assertEquals("work/u-alice", key)

        val verify =
            eventGateway.process(
                message(
                    OtpVerifyRequest(
                        channel = CodeChannel.MATTERMOST,
                        server = "work",
                        address = "alice",
                        code = code,
                    )
                )
            )
        val login =
            assertInstanceOf(OperationResult.Success::class.java, verify).payload as LoginResponse
        assertEquals("alice", login.principal.username)
        val user = userRepository.findByUsername("alice")!!
        assertEquals("", user.email)
        assertEquals("Alice Liddell", user.displayName)
        assertNotNull(bindingRepository.resolve(Protocol.MATTERMOST, "work", "u-alice"))

        /* Second login converges onto the same account */
        eventGateway.process(
            message(
                OtpRequest(channel = CodeChannel.MATTERMOST, server = "work", address = "alice")
            )
        )
        val again =
            eventGateway.process(
                message(
                    OtpVerifyRequest(
                        channel = CodeChannel.MATTERMOST,
                        server = "work",
                        address = "alice",
                        code = fake.delivered.last().second,
                    )
                )
            )
        assertEquals(
            user.id,
            (assertInstanceOf(OperationResult.Success::class.java, again).payload as LoginResponse)
                .principal
                .id,
        )
        assertEquals(1, userRepository.findActive().count { it.username == "alice" })
    }

    @Test
    fun `an unknown chat user or server gets the standard message and no delivery`() {
        val unknownUser =
            eventGateway.process(
                message(
                    OtpRequest(
                        channel = CodeChannel.MATTERMOST,
                        server = "work",
                        address = "mallory",
                    )
                )
            )
        val unknownServer =
            eventGateway.process(
                message(
                    OtpRequest(
                        channel = CodeChannel.MATTERMOST,
                        server = "elsewhere",
                        address = "alice",
                    )
                )
            )
        val known =
            eventGateway.process(
                message(
                    OtpRequest(channel = CodeChannel.MATTERMOST, server = "work", address = "bob")
                )
            )
        val texts =
            listOf(unknownUser, unknownServer, known).map {
                assertInstanceOf(OperationResult.Success::class.java, it).payload.toString()
            }
        assertEquals(
            1,
            texts.distinct().size,
            "responses must not distinguish known from unknown: $texts",
        )
        assertEquals(listOf("work/u-bob"), fake.delivered.map { it.first })
    }

    @Test
    fun `a code issued on one channel cannot verify another`() {
        eventGateway.process(
            message(OtpRequest(channel = CodeChannel.MATTERMOST, server = "work", address = "bob"))
        )
        val code = fake.delivered.single().second
        val wrong =
            eventGateway.process(message(OtpVerifyRequest(email = "bob@example.com", code = code)))
        assertInstanceOf(OperationResult.Error::class.java, wrong)
    }

    @Test
    fun `a chat username that does not exist cannot verify even with a real-looking code`() {
        val result =
            eventGateway.process(
                message(
                    OtpVerifyRequest(
                        channel = CodeChannel.MATTERMOST,
                        server = "work",
                        address = "mallory",
                        code = "123456",
                    )
                )
            )
        assertInstanceOf(OperationResult.Error::class.java, result)
    }

    @Test
    fun `the HTTP endpoints accept the channel shape and the legacy email body`() {
        mockMvc
            .post("/auth/otp/request") {
                contentType = MediaType.APPLICATION_JSON
                content = """{"channel":"mattermost","server":"work","address":"alice"}"""
            }
            .andExpect { status { isAccepted() } }
        assertEquals(1, fake.delivered.size)
        mockMvc
            .post("/auth/otp/request") {
                contentType = MediaType.APPLICATION_JSON
                content = """{"email":"someone@example.com"}"""
            }
            .andExpect { status { isAccepted() } }
        val code = fake.delivered.single().second
        mockMvc
            .post("/auth/otp/verify") {
                contentType = MediaType.APPLICATION_JSON
                content =
                    """{"channel":"mattermost","server":"work","address":"alice","code":"$code"}"""
            }
            .andExpect {
                status { isOk() }
                jsonPath("$.principal.username") { value("alice") }
            }
        assertTrue(userRepository.findByUsername("alice") != null)
    }
}
