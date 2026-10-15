/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.service

import dev.streampack.core.config.StreampackProperties
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.support.StaticListableBeanFactory
import org.springframework.mail.SimpleMailMessage
import org.springframework.mail.javamail.JavaMailSenderImpl

/** The account emails come from their templates, in the site's name (#232). */
class EmailServiceTests {
    private class Capturing : JavaMailSenderImpl() {
        val sent = mutableListOf<SimpleMailMessage>()

        override fun send(vararg simpleMessages: SimpleMailMessage) {
            sent += simpleMessages
        }
    }

    private val sender = Capturing()
    private val service =
        EmailService(
            sender,
            StreampackProperties(baseUrl = "https://gholem.example"),
            StaticListableBeanFactory().getBeanProvider(TextTemplates::class.java),
            "Gholem",
        )

    @Test
    fun `the sign-in code mail names the site and gives the code`() {
        service.sendOneTimeCode("me@example.com", "654321")

        val mail = sender.sent.single()
        assertEquals("Your sign-in code for Gholem", mail.subject)
        assertTrue(mail.text!!.startsWith("Your one-time sign-in code is: 654321"), mail.text)
        assertTrue(mail.text!!.contains("expire in 10 minutes"), mail.text)
        assertFalse(mail.text!!.contains("bytecode.news"), mail.text)
    }

    @Test
    fun `the verification mail names the site and gives the link`() {
        service.sendVerificationEmail("me@example.com", "abc")

        val mail = sender.sent.single()
        assertEquals("Verify your email address", mail.subject)
        assertTrue(mail.text!!.startsWith("Welcome to Gholem!"), mail.text)
        assertTrue(mail.text!!.contains("https://gholem.example/auth/verify?token=abc"), mail.text)
        assertTrue(mail.text!!.contains("expire in 24 hours"), mail.text)
    }
}
