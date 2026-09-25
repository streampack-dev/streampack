/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.service

import dev.streampack.core.model.CodeChannel
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.transaction.annotation.Transactional

/** Codes are scoped by delivery channel as well as recipient (#53). */
@SpringBootTest
@Transactional
class OneTimeCodeChannelTests {
    @Autowired lateinit var service: OneTimeCodeService

    @Test
    fun `a code is only consumable on the channel it was issued for`() {
        val code = service.generateCode(CodeChannel.MATTERMOST, "work/u1").code
        assertFalse(service.consumeCode(CodeChannel.EMAIL, "work/u1", code))
        assertTrue(service.consumeCode(CodeChannel.MATTERMOST, "work/u1", code))
        assertFalse(service.consumeCode(CodeChannel.MATTERMOST, "work/u1", code))
    }

    @Test
    fun `the active code cap counts per channel and recipient`() {
        repeat(3) { service.generateCode(CodeChannel.MATTERMOST, "work/u2") }
        assertThrows(IllegalStateException::class.java) {
            service.generateCode(CodeChannel.MATTERMOST, "work/u2")
        }
        /* Same recipient key on another channel is an independent budget */
        service.generateCode(CodeChannel.EMAIL, "work/u2")
    }

    @Test
    fun `email recipients are normalized to lowercase and the legacy email entry points still work`() {
        val code = service.generateCode("Alice@Example.com").code
        assertTrue(service.consumeCode(CodeChannel.EMAIL, "alice@example.com", code))
    }
}
