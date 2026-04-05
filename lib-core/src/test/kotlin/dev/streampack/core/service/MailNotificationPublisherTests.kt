/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.service

import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Test
import org.springframework.messaging.Message
import org.springframework.messaging.MessageChannel

class MailNotificationPublisherTests {

    @Test
    fun `publish swallows channel failures`() {
        val failingChannel =
            object : MessageChannel {
                override fun send(message: Message<*>, timeout: Long): Boolean {
                    throw IllegalStateException("channel down")
                }
            }
        val publisher = MailNotificationPublisher(failingChannel)

        assertDoesNotThrow { publisher.publish("user@example.com", "Subject", "Body") }
    }
}
