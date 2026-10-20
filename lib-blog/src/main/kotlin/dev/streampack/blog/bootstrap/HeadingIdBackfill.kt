/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.bootstrap

import dev.streampack.blog.repository.PostRepository
import dev.streampack.blog.service.MarkdownRenderingService
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/**
 * Gives the headings of posts stored before #270 their ids, by rendering each such post again from
 * its markdown with [MarkdownRenderingService.renderPost], as a post saved now is rendered.
 *
 * Only a post with a heading that has no id is rendered again, so the pass runs on every start and
 * does nothing once every post has its ids. It doesn't change a post's `updatedAt`: the post's
 * words haven't changed, and its feeds shouldn't say it was updated. Comments keep their headings
 * as they are; they never get ids.
 */
@Component
class HeadingIdBackfill(
    private val postRepository: PostRepository,
    private val markdownRenderingService: MarkdownRenderingService,
) : ApplicationRunner {
    private val logger = LoggerFactory.getLogger(HeadingIdBackfill::class.java)

    @Transactional
    override fun run(args: ApplicationArguments) {
        var changed = 0
        var page = 0
        while (true) {
            val posts = postRepository.findAll(PageRequest.of(page, PAGE_SIZE))
            for (post in posts) {
                if (!HEADING_WITHOUT_ID.containsMatchIn(post.renderedHtml)) continue
                val rendered = markdownRenderingService.renderPost(post.markdownSource)
                if (rendered.isBlank() || rendered == post.renderedHtml) continue
                postRepository.save(post.copy(renderedHtml = rendered))
                changed++
            }
            if (!posts.hasNext()) break
            page++
        }
        if (changed > 0) logger.info("Gave {} post(s) heading ids (#270)", changed)
        else logger.debug("Every post's headings have ids; no backfill needed")
    }

    companion object {
        private const val PAGE_SIZE = 200

        /** A heading with no id: `<h2>`, or `<h2 class="…">`, never `<h2 id="…">`. */
        private val HEADING_WITHOUT_ID = Regex("<h[1-6](?![^>]*\\bid=)[\\s>]")
    }
}
