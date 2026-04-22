/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.operation

import dev.streampack.blog.entity.Post
import dev.streampack.blog.model.PostStatus
import dev.streampack.blog.model.RecordPostAccessRequest
import dev.streampack.blog.repository.PostRepository
import dev.streampack.core.integration.EventGateway
import dev.streampack.core.model.OperationResult
import dev.streampack.temperature.repository.TemperatureBucketRepository
import java.time.LocalDate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.messaging.support.MessageBuilder
import org.springframework.transaction.annotation.Transactional

@SpringBootTest
@Transactional
class RecordPostAccessOperationTests {

    @Autowired lateinit var eventGateway: EventGateway
    @Autowired lateinit var postRepository: PostRepository
    @Autowired lateinit var temperatureBucketRepository: TemperatureBucketRepository

    private lateinit var postId: java.util.UUID

    @BeforeEach
    fun setUp() {
        postId =
            postRepository
                .save(
                    Post(
                        title = "Tracked Post",
                        markdownSource = "Tracked markdown.",
                        renderedHtml = "<p>Tracked markdown.</p>",
                        excerpt = "Tracked markdown.",
                        status = PostStatus.APPROVED,
                    )
                )
                .id
    }

    @Test
    fun `record post access request increments persisted usage`() {
        val result =
            eventGateway.process(
                MessageBuilder.withPayload(RecordPostAccessRequest(postId)).build()
            )

        assertEquals(OperationResult.NotHandled, result)
        postRepository.flush()
        val refreshed = postRepository.findById(postId).orElseThrow()
        assertEquals(1, refreshed.accessCount)
        assertNotNull(refreshed.lastAccessedAt)
        val bucket =
            temperatureBucketRepository.findByNamespaceAndSubjectKeyAndSignalAndBucketDate(
                "blog.post",
                postId.toString(),
                "hit",
                LocalDate.now(),
            )
        assertNotNull(bucket)
        assertEquals(1, bucket!!.positiveDelta)
    }
}
