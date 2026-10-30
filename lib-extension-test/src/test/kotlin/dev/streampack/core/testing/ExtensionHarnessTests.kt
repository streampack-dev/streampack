/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.testing

import dev.streampack.core.integration.TickListener
import dev.streampack.core.model.Declined
import dev.streampack.core.model.OperationOutcome
import dev.streampack.core.model.OperationResult
import dev.streampack.core.model.Role
import dev.streampack.core.service.TypedOperation
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.messaging.Message
import org.springframework.mock.web.MockHttpServletRequest

class ExtensionHarnessTests {
    private class Answer(
        val word: String,
        val text: String?,
        override val priority: Int = 50,
        override val addressed: Boolean = true,
        val decline: Boolean = false,
    ) : TypedOperation<String>(String::class) {
        override fun canHandle(payload: String, message: Message<*>) = payload == word

        override fun handle(payload: String, message: Message<*>): OperationOutcome? =
            if (decline) Declined("no") else text?.let { OperationResult.Success(it) }
    }

    @Test
    fun `operations answer in priority order, and null or Declined passes on`() {
        val harness =
            ExtensionHarness()
                .use(
                    Answer("hi", "late", priority = 90),
                    Answer("hi", null, priority = 10),
                    Answer("hi", "declined", priority = 20, decline = true),
                    Answer("hi", "first", priority = 30),
                )
        assertThat(harness.reply("hi")).isEqualTo("first")
        assertThat(harness.send("bye")).isEqualTo(OperationResult.NotHandled)
    }

    @Test
    fun `an unaddressed message reaches only operations that don't need addressing`() {
        val harness =
            ExtensionHarness()
                .use(
                    Answer("hi", "addressed"),
                    Answer("hi", "anyone", priority = 60, addressed = false),
                )
        assertThat(harness.send("hi", addressed = false))
            .isEqualTo(OperationResult.Success("anyone"))
    }

    @Test
    fun `a tick reaches every tick listener`() {
        val seen = mutableListOf<Instant>()
        val now = Instant.parse("2026-10-29T12:00:00Z")
        ExtensionHarness()
            .use(
                object : TickListener {
                    override fun onTick(now: Instant) {
                        seen += now
                    }
                },
            )
            .tick(now)
        assertThat(seen).containsExactly(now)
    }

    @Test
    fun `signIn sets who every request comes from, and signOut clears it`() {
        val harness = ExtensionHarness()
        val request = MockHttpServletRequest()
        assertThat(harness.caller.resolve(request)).isNull()

        val admin = harness.signIn(ExtensionHarness.user("ada", Role.ADMIN), fromCookie = true)
        val credential = harness.caller.resolve(request)!!
        assertThat(credential.principal).isEqualTo(admin)
        assertThat(credential.fromCookie).isTrue()

        harness.signOut()
        assertThat(harness.caller.resolve(request)).isNull()
    }
}
