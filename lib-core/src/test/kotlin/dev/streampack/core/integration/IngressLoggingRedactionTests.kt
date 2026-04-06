/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.integration

import dev.streampack.core.model.RedactionRule
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Unit tests for positional redaction logic */
class IngressLoggingRedactionTests {

    private val ircConnectRule = RedactionRule("irc connect", setOf(5, 6))

    @Test
    fun `credentials are redacted from irc connect`() {
        val result =
            IngressLoggingInterceptor.redact(
                "irc connect libera irc.libera.chat nevet myaccount mypassword",
                listOf(ircConnectRule),
            )
        assertEquals("irc connect libera irc.libera.chat nevet [REDACTED] [REDACTED]", result)
    }

    @Test
    fun `irc connect without credentials is unchanged`() {
        val result =
            IngressLoggingInterceptor.redact(
                "irc connect libera irc.libera.chat nevet",
                listOf(ircConnectRule),
            )
        assertEquals("irc connect libera irc.libera.chat nevet", result)
    }

    @Test
    fun `prefix match is case-insensitive`() {
        val result =
            IngressLoggingInterceptor.redact(
                "IRC CONNECT libera irc.libera.chat nevet myaccount mypassword",
                listOf(ircConnectRule),
            )
        assertEquals("IRC CONNECT libera irc.libera.chat nevet [REDACTED] [REDACTED]", result)
    }

    @Test
    fun `non-matching prefix is not redacted`() {
        val result =
            IngressLoggingInterceptor.redact("irc disconnect libera", listOf(ircConnectRule))
        assertEquals("irc disconnect libera", result)
    }

    @Test
    fun `unrelated message is not redacted`() {
        val result = IngressLoggingInterceptor.redact("hello world", listOf(ircConnectRule))
        assertEquals("hello world", result)
    }

    @Test
    fun `no rules means no redaction`() {
        val result =
            IngressLoggingInterceptor.redact(
                "irc connect libera irc.libera.chat nevet secret pass",
                emptyList(),
            )
        assertEquals("irc connect libera irc.libera.chat nevet secret pass", result)
    }

    @Test
    fun `multiple rules from different operations`() {
        val rules = listOf(ircConnectRule, RedactionRule("github add", setOf(3)))

        val ircResult =
            IngressLoggingInterceptor.redact("irc connect libera host nick acct pass", rules)
        assertEquals("irc connect libera host nick [REDACTED] [REDACTED]", ircResult)

        val ghResult =
            IngressLoggingInterceptor.redact("github add owner/repo ghp_secret123", rules)
        assertEquals("github add owner/repo [REDACTED]", ghResult)
    }

    @Test
    fun `only matching positions are redacted`() {
        val rules = listOf(RedactionRule("secret cmd", setOf(2)))
        val result = IngressLoggingInterceptor.redact("secret cmd visible hidden visible2", rules)
        assertEquals("secret cmd [REDACTED] hidden visible2", result)
    }

    @Test
    fun `irc connect with only account and no password redacts account`() {
        val result =
            IngressLoggingInterceptor.redact(
                "irc connect libera irc.libera.chat nevet myaccount",
                listOf(ircConnectRule),
            )
        assertEquals("irc connect libera irc.libera.chat nevet [REDACTED]", result)
    }
}
