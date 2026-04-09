/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.service

import dev.streampack.blog.entity.Post
import dev.streampack.blog.repository.PostRepository
import dev.streampack.core.integration.TickListener
import java.time.Duration
import java.time.Instant
import java.util.regex.Pattern
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

@Service
class FactoidUpdatedPostRerenderTickListener(
    private val factoidUpdateBuffer: FactoidUpdateBuffer,
    private val postRepository: PostRepository,
    private val markdownRenderingService: MarkdownRenderingService,
) : TickListener {
    private val logger = LoggerFactory.getLogger(FactoidUpdatedPostRerenderTickListener::class.java)
    private var lastRunAt: Instant? = null

    override fun onTick(now: Instant) {
        val previous = lastRunAt
        if (previous != null && Duration.between(previous, now) < RERENDER_INTERVAL) return
        lastRunAt = now

        val selectors = factoidUpdateBuffer.drain()
        if (selectors.isEmpty()) return

        val candidates =
            selectors
                .asSequence()
                .flatMap { selector ->
                    postRepository.findCandidatesByMarkdownContaining(selector).asSequence()
                }
                .distinctBy { it.id }
                .filter { post -> referencesAnySelector(post, selectors) }
                .toList()
        for (post in candidates) {
            val rerendered = markdownRenderingService.render(post.markdownSource)
            if (rerendered == post.renderedHtml) continue
            postRepository.save(post.copy(renderedHtml = rerendered, updatedAt = now))
        }

        logger.debug(
            "Processed {} factoid selector(s) across {} candidate post(s)",
            selectors.size,
            candidates.size,
        )
    }

    private fun referencesAnySelector(post: Post, selectors: Set<String>): Boolean =
        selectors.any { selector ->
            wikiLinkPatternFor(selector).containsMatchIn(post.markdownSource)
        }

    private fun wikiLinkPatternFor(selector: String): Regex {
        val escaped = Pattern.quote(selector)
        return Regex("""\[\[(?:[^|\]]+\|)?$escaped\]\]""", RegexOption.IGNORE_CASE)
    }

    companion object {
        val RERENDER_INTERVAL: Duration = Duration.ofMinutes(1)
    }
}
