/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.operation

import dev.streampack.blog.model.RecordPostAccessRequest
import dev.streampack.blog.repository.PostRepository
import dev.streampack.core.model.Consumed
import dev.streampack.core.model.OperationOutcome
import dev.streampack.core.service.TypedOperation
import java.time.Instant
import org.springframework.messaging.Message
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/** Internal bookkeeping operation for UI-driven blog post access tracking. */
@Component
class RecordPostAccessOperation(private val postRepository: PostRepository) :
    TypedOperation<RecordPostAccessRequest>(RecordPostAccessRequest::class) {

    @Transactional
    override fun handle(payload: RecordPostAccessRequest, message: Message<*>): OperationOutcome {
        val post =
            postRepository.findById(payload.id).orElse(null) ?: return Consumed("post missing")
        postRepository.save(
            post.copy(accessCount = post.accessCount + 1, lastAccessedAt = Instant.now())
        )
        return Consumed("recorded blog post access ${payload.id}")
    }
}
