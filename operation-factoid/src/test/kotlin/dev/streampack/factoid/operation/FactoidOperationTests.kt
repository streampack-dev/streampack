/* Joseph B. Ottinger (C)2026 */
package dev.streampack.factoid.operation

import dev.streampack.core.integration.EventGateway
import dev.streampack.core.model.OperationResult
import dev.streampack.core.model.Protocol
import dev.streampack.core.model.Provenance
import dev.streampack.core.model.Role
import dev.streampack.core.model.UserPrincipal
import dev.streampack.factoid.repository.FactoidRepository
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.messaging.support.MessageBuilder
import org.springframework.transaction.annotation.Transactional

/**
 * The factoid chat commands, as conversations. The conversations are in `factoid/examples.json`
 * (see [FactoidExamples]); the docs quote them. Each example runs in its own rolled-back
 * transaction, so examples can reuse selectors.
 */
@SpringBootTest
@Transactional
class FactoidOperationTests {

    @Autowired lateinit var eventGateway: EventGateway
    @Autowired lateinit var factoidRepository: FactoidRepository

    @ParameterizedTest(name = "{0}")
    @MethodSource("examples")
    fun `factoid example`(example: FactoidExample) {
        example.steps.forEachIndexed { index, step ->
            val where = "${example.id}, step ${index + 1}: \"${step.say}\""
            repeat(step.repeat) { check(where, step, ask(step.say, step.nick, step.role)) }
        }
    }

    @Test
    fun `a lookup counts an access`() {
        ask("tracked=some value")
        ask("tracked")
        factoidRepository.flush()

        val factoid = factoidRepository.findBySelectorIgnoreCase("tracked")!!
        assertEquals(1, factoid.accessCount)
        assertNotNull(factoid.lastAccessedAt)
    }

    private fun check(where: String, step: FactoidExampleStep, result: Any?) {
        when {
            step.silent -> assertInstanceOf(OperationResult.NotHandled::class.java, result, where)
            step.error != null -> {
                assertInstanceOf(OperationResult.Error::class.java, result, where)
                if (step.error is String) {
                    assertEquals(step.error, (result as OperationResult.Error).message, where)
                }
            }
            step.reply != null -> assertEquals(step.reply, said(where, result), where)
            step.startsWith != null ->
                assertTrue(
                    said(where, result).startsWith(step.startsWith),
                    "$where: ${said(where, result)}",
                )
            step.oneOf != null ->
                assertTrue(said(where, result) in step.oneOf, "$where: ${said(where, result)}")
        }
    }

    private fun said(where: String, result: Any?): String {
        assertInstanceOf(OperationResult.Success::class.java, result, where)
        return (result as OperationResult.Success).payload.toString()
    }

    private fun ask(text: String, nick: String = "alice", role: String = "USER"): Any? =
        eventGateway.process(
            MessageBuilder.withPayload(text)
                .setHeader(Provenance.HEADER, provenance(nick, Role.valueOf(role)))
                .setHeader("nick", nick)
                .build(),
        )

    private fun provenance(nick: String, role: Role) =
        Provenance(
            protocol = Protocol.CONSOLE,
            serviceId = "",
            replyTo = "local",
            user =
                UserPrincipal(
                    id = UUID.randomUUID(),
                    username = nick,
                    displayName = nick,
                    role = role,
                ),
        )

    companion object {
        @JvmStatic fun examples(): List<FactoidExample> = FactoidExamples.load()
    }
}
