/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.service

import com.enigmastation.streampack.core.config.StreampackProperties
import org.slf4j.LoggerFactory
import org.springframework.mail.SimpleMailMessage
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.stereotype.Service

/** Sends transactional emails for authentication and account management flows */
@Service
class EmailService(private val mailSender: JavaMailSender, properties: StreampackProperties) {
    private val baseUrl = properties.baseUrl
    private val fromAddress = properties.mail.from
    private val otpExpirationMinutes = properties.otp.expirationMinutes
    private val logger = LoggerFactory.getLogger(EmailService::class.java)

    /** Sends an email verification link to the user */
    fun sendVerificationEmail(to: String, token: String) {
        val link = "$baseUrl/auth/verify?token=$token"
        val message = SimpleMailMessage()
        message.from = fromAddress
        message.setTo(to)
        message.subject = "Verify your email address"
        message.text =
            "Welcome to bytecode.news!\n\n" +
                "Please verify your email address by visiting:\n$link\n\n" +
                "This link will expire in 24 hours."
        logger.info("Sending verification email to {}", to)
        mailSender.send(message)
    }

    /** Sends a one-time sign-in code to the given email address */
    fun sendOneTimeCode(to: String, code: String) {
        val message = SimpleMailMessage()
        message.from = fromAddress
        message.setTo(to)
        message.subject = "Your sign-in code for bytecode.news"
        message.text =
            "Your one-time sign-in code is: $code\n\n" +
                "This code will expire in $otpExpirationMinutes minutes.\n" +
                "If you did not request this, please ignore this email."
        logger.info("Sending OTP code to {}", to)
        mailSender.send(message)
    }
}
