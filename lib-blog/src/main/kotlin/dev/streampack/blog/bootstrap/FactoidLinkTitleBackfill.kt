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
 * Gives the factoid links of posts stored before #285 their titles, by rendering each such post
 * again from its markdown with [MarkdownRenderingService.renderPost], as a post saved now is
 * rendered. Before #285 a factoid with no web address, or with an apostrophe in its name, left its
 * link with no title.
 *
 * Only a post with a `/factoids/` link that has no title is rendered again. A link to a factoid
 * with no text keeps no title, so its post is rendered again on each start and is not saved. The
 * pass doesn't change a post's `updatedAt`: the post's words haven't changed.
 */
@Component
class FactoidLinkTitleBackfill(
    private val postRepository: PostRepository,
    private val markdownRenderingService: MarkdownRenderingService,
) : ApplicationRunner {
    private val logger = LoggerFactory.getLogger(FactoidLinkTitleBackfill::class.java)

    @Transactional
    override fun run(args: ApplicationArguments) {
        var changed = 0
        var page = 0
        while (true) {
            val posts = postRepository.findAll(PageRequest.of(page, PAGE_SIZE))
            for (post in posts) {
                if (!FACTOID_LINK_WITHOUT_TITLE.containsMatchIn(post.renderedHtml)) continue
                val rendered = markdownRenderingService.renderPost(post.markdownSource)
                if (rendered.isBlank() || rendered == post.renderedHtml) continue
                postRepository.save(post.copy(renderedHtml = rendered))
                changed++
            }
            if (!posts.hasNext()) break
            page++
        }
        if (changed > 0) logger.info("Gave {} post(s) factoid link titles (#285)", changed)
        else logger.debug("No post's factoid links need titles; no backfill needed")
    }

    companion object {
        private const val PAGE_SIZE = 200

        /** A link to `/factoids/…` with no `title` attribute. */
        private val FACTOID_LINK_WITHOUT_TITLE =
            Regex("""<a\b(?![^>]*\btitle=)[^>]*\bhref=(['"])/factoids/""", RegexOption.IGNORE_CASE)
    }
}
