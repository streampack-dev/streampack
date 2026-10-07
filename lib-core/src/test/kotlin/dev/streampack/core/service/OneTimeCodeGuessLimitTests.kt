/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.service

import dev.streampack.core.config.StreampackProperties
import dev.streampack.core.model.CodeChannel
import dev.streampack.core.repository.OneTimeCodeRepository
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.transaction.annotation.Transactional

/** Wrong guesses at a recipient's codes are limited (#144). */
@SpringBootTest
@Transactional
class OneTimeCodeGuessLimitTests {
    @Autowired lateinit var service: OneTimeCodeService
    @Autowired lateinit var repository: OneTimeCodeRepository
    @Autowired lateinit var properties: StreampackProperties

    private val limit
        get() = properties.otp.maxFailedAttempts

    /** A six-digit code that is not [code] */
    private fun wrong(code: String): String =
        ((code.toInt() + 1) % 1_000_000).toString().padStart(6, '0')

    @Test
    fun `the default limit is five`() {
        assertEquals(5, limit)
    }

    @Test
    fun `after the limit of wrong codes the right one fails`() {
        val code = service.generateCode("guessed@example.com").code
        repeat(limit) { assertFalse(service.consumeCode("guessed@example.com", wrong(code))) }

        assertFalse(service.consumeCode("guessed@example.com", code))
        assertEquals(
            0L,
            repository.countActive(CodeChannel.EMAIL, "guessed@example.com", Instant.now()),
        )
    }

    @Test
    fun `lockout expires every active code for the recipient`() {
        val codes = List(3) { service.generateCode("many@example.com").code }
        /* A guess that matches none of the three */
        val miss =
            generateSequence(0) { it + 1 }
                .map { it.toString().padStart(6, '0') }
                .first { it !in codes }
        repeat(limit) { assertFalse(service.consumeCode("many@example.com", miss)) }

        codes.forEach { assertFalse(service.consumeCode("many@example.com", it)) }
    }

    @Test
    fun `a fresh code after lockout works`() {
        val first = service.generateCode("again@example.com").code
        repeat(limit) { service.consumeCode("again@example.com", wrong(first)) }

        val fresh = service.generateCode("again@example.com").code
        assertTrue(service.consumeCode("again@example.com", fresh))
    }

    @Test
    fun `a correct code before the limit still works and clears the count`() {
        val code = service.generateCode("typo@example.com").code
        repeat(limit - 1) { service.consumeCode("typo@example.com", wrong(code)) }

        assertTrue(service.consumeCode("typo@example.com", code))
        assertNull(repository.countFailures(CodeChannel.EMAIL.name, "typo@example.com"))
    }

    @Test
    fun `misses are counted per channel and recipient`() {
        val code = service.generateCode(CodeChannel.MATTERMOST, "work/u9").code
        repeat(limit) { service.consumeCode(CodeChannel.EMAIL, "work/u9", wrong(code)) }
        repeat(limit - 1) { service.consumeCode("someone-else@example.com", wrong(code)) }

        assertTrue(service.consumeCode(CodeChannel.MATTERMOST, "work/u9", code))
    }

    @Test
    fun `a count whose last miss is older than a code's lifetime starts over`() {
        val now = Instant.now()
        val lifetime = properties.otp.expirationMinutes * 60L
        val longAgo = now.minusSeconds(lifetime + 60)
        repeat(limit - 1) {
            repository.recordFailure("EMAIL", "slow@example.com", longAgo, longAgo)
        }

        repository.recordFailure("EMAIL", "slow@example.com", now, now.minusSeconds(lifetime))

        assertEquals(1, repository.countFailures("EMAIL", "slow@example.com"))
    }
}
