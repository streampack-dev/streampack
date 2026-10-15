/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.service

import dev.streampack.blog.entity.Post
import dev.streampack.blog.model.PostStatus
import dev.streampack.core.entity.User
import dev.streampack.core.model.Role
import dev.streampack.core.repository.UserRepository
import dev.streampack.core.service.MailNotificationPublisher
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.integration.channel.DirectChannel
import org.springframework.transaction.annotation.Transactional

/** The new-draft mail comes from its template, in the site's name (#232). */
@SpringBootTest(properties = ["streampack.blog.site-name=Gholem"])
@Import(BlogNotificationServiceTests.Capture::class)
@Transactional
class BlogNotificationServiceTests {
    class Capturing : MailNotificationPublisher(DirectChannel()) {
        val sent = mutableListOf<Triple<Collection<String>, String, String>>()

        override fun publishAll(recipients: Collection<String>, subject: String, body: String) {
            sent += Triple(recipients, subject, body)
        }
    }

    @TestConfiguration
    class Capture {
        @Bean @Primary fun capturingPublisher() = Capturing()
    }

    @Autowired lateinit var notifications: BlogNotificationService
    @Autowired lateinit var publisher: Capturing
    @Autowired lateinit var userRepository: UserRepository

    @BeforeEach
    fun setUp() {
        publisher.sent.clear()
        userRepository.save(
            User(
                username = "draft-admin",
                email = "admin@gholem.example",
                displayName = "Admin",
                emailVerified = true,
                role = Role.ADMIN,
            ),
        )
    }

    @Test
    fun `the new-draft mail names the site and carries the draft`() {
        val post =
            Post(
                title = "Emergent Behaviour",
                markdownSource = "A body with {{siteName}} in it, left alone.",
                renderedHtml = "",
                status = PostStatus.DRAFT,
            )

        notifications.notifyPostSubmission(post, "2026/10/emergent-behaviour", submitter = null)

        val (recipients, subject, body) = publisher.sent.single()
        assertTrue("admin@gholem.example" in recipients, recipients.toString())
        assertEquals("New post submission: Emergent Behaviour", subject)
        assertTrue(body.startsWith("A new post draft was submitted on Gholem."), body)
        assertTrue(body.contains("Author: Anonymous"), body)
        assertTrue(body.contains("/posts/2026/10/emergent-behaviour"), body)
        assertTrue(body.endsWith("A body with {{siteName}} in it, left alone."), body)
        assertFalse(body.contains("bytecode.news"), body)
    }
}
