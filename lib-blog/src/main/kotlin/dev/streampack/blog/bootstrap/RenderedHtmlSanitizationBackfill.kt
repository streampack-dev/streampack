/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.bootstrap

import dev.streampack.blog.repository.CommentRepository
import dev.streampack.blog.repository.PostRepository
import dev.streampack.blog.service.RenderedHtmlSanitizer
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/**
 * Runs the HTML sanitizer over already-stored `renderedHtml` on posts and comments.
 *
 * New content is sanitized at write time by [dev.streampack.blog.service.MarkdownRenderingService].
 * Content stored before that existed may still carry `javascript:` links or other markup the
 * safelist now rejects, so this pass cleans it in place. It operates on the stored HTML only, not
 * the markdown source, so it is deterministic and cheap; rows whose HTML is already clean are not
 * written. It runs on every start and is a no-op once the data is clean. Disable with
 * `streampack.blog.sanitize-on-startup=false`.
 */
@Component
class RenderedHtmlSanitizationBackfill(
    private val postRepository: PostRepository,
    private val commentRepository: CommentRepository,
    private val sanitizer: RenderedHtmlSanitizer,
    @Value("\${streampack.blog.sanitize-on-startup:true}") private val enabled: Boolean,
) : ApplicationRunner {
    private val logger = LoggerFactory.getLogger(RenderedHtmlSanitizationBackfill::class.java)

    @Transactional
    override fun run(args: ApplicationArguments) {
        if (!enabled) {
            logger.debug("Rendered HTML sanitization backfill disabled")
            return
        }
        var postsChanged = 0
        var commentsChanged = 0
        var page = 0
        while (true) {
            val posts = postRepository.findAll(PageRequest.of(page, PAGE_SIZE))
            for (post in posts) {
                val cleaned = sanitizer.sanitize(post.renderedHtml)
                if (cleaned != post.renderedHtml) {
                    postRepository.save(post.copy(renderedHtml = cleaned))
                    postsChanged++
                }
            }
            if (!posts.hasNext()) break
            page++
        }
        page = 0
        while (true) {
            val comments = commentRepository.findAll(PageRequest.of(page, PAGE_SIZE))
            for (comment in comments) {
                val cleaned = sanitizer.sanitize(comment.renderedHtml)
                if (cleaned != comment.renderedHtml) {
                    commentRepository.save(comment.copy(renderedHtml = cleaned))
                    commentsChanged++
                }
            }
            if (!comments.hasNext()) break
            page++
        }
        if (postsChanged > 0 || commentsChanged > 0) {
            logger.warn(
                "Sanitized stored HTML on {} post(s) and {} comment(s) (issue #48 backfill)",
                postsChanged,
                commentsChanged,
            )
        } else {
            logger.debug("Stored rendered HTML already clean; no backfill needed")
        }
    }

    companion object {
        private const val PAGE_SIZE = 200
    }
}
