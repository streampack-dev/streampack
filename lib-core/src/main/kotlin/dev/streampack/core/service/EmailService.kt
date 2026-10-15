/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.service

import dev.streampack.core.config.StreampackProperties
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.mail.SimpleMailMessage
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.stereotype.Service

/**
 * Sends transactional emails for authentication and account management flows. Their text is a
 * template each (#232), `mail-verify-email` and `mail-sign-in-code`, named with the site's name
 * (`BLOG_SITE_NAME`); a file of the same name in `generative/` replaces the bundled one.
 */
@Service
class EmailService(
    private val mailSender: JavaMailSender,
    properties: StreampackProperties,
    templates: ObjectProvider<TextTemplates>,
    @Value("\${streampack.blog.site-name:\${streampack.name:nevet}}") private val siteName: String,
) {
    private val baseUrl = properties.baseUrl
    private val fromAddress = properties.mail.from
    private val otpExpirationMinutes = properties.otp.expirationMinutes
    private val verificationHours = properties.token.emailVerificationHours
    private val templates = templates.getIfAvailable { ClasspathTextTemplates() }
    private val logger = LoggerFactory.getLogger(EmailService::class.java)

    /** Sends an email verification link to the user */
    fun sendVerificationEmail(to: String, token: String) {
        val text =
            mail(
                "mail-verify-email",
                mapOf("link" to "$baseUrl/auth/verify?token=$token", "hours" to verificationHours),
            )
        logger.info("Sending verification email to {}", to)
        send(to, text)
    }

    /** Sends a one-time sign-in code to the given email address */
    fun sendOneTimeCode(to: String, code: String) {
        val text =
            mail("mail-sign-in-code", mapOf("code" to code, "minutes" to otpExpirationMinutes))
        logger.info("Sending OTP code to {}", to)
        send(to, text)
    }

    private fun mail(name: String, values: Map<String, Any?>): MailText =
        MailText.parse(
            templates.render(
                name,
                "dev/streampack/core/mail/$name.txt",
                values + ("siteName" to siteName),
            ),
            fallbackSubject = siteName,
        )

    private fun send(to: String, text: MailText) {
        val message = SimpleMailMessage()
        message.from = fromAddress
        message.setTo(to)
        message.subject = text.subject
        message.text = text.body
        mailSender.send(message)
    }
}
