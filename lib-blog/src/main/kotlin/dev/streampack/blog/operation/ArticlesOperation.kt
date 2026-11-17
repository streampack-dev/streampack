/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.operation

import dev.streampack.blog.config.BlogProperties
import dev.streampack.blog.model.ContentListResponse
import dev.streampack.blog.model.FindContentRequest
import dev.streampack.core.model.OperationOutcome
import dev.streampack.core.model.OperationResult
import dev.streampack.core.repository.UserRepository
import dev.streampack.core.service.TypedOperation
import org.springframework.messaging.Message
import org.springframework.stereotype.Component

/**
 * The `articles` chat command, over published posts only, as readers see them on the site:
 * - `articles`: the newest posts;
 * - `articles by <author>`: one author's, newest first;
 * - `articles <words>`: a search, with the same query as `GET /posts/search`.
 *
 * A reply has at most [MAX_LINES] lines, one post each, `Title - Author - link`, so it stays short
 * in a channel. When more posts match, the last line counts them instead and points to the site.
 *
 * `<author>` is matched first against the name an author publishes under (the display name, any
 * case), then against a username. A chat nick often differs from the name on the site, and the
 * site's name is the one readers see.
 *
 * The queries are [FindContentOperation]'s, so what the site would hide stays hidden here.
 */
@Component
class ArticlesOperation(
    private val findContent: FindContentOperation,
    private val userRepository: UserRepository,
    private val blogProperties: BlogProperties,
) : TypedOperation<String>(String::class) {

    override val priority: Int = 50
    override val addressed: Boolean = true

    override fun canHandle(payload: String, message: Message<*>): Boolean {
        val lower = payload.trim().lowercase()
        return lower == COMMAND || lower.startsWith("$COMMAND ")
    }

    override fun handle(payload: String, message: Message<*>): OperationOutcome {
        val args = payload.trim().substring(COMMAND.length).trim()
        val words = args.split(Regex("\\s+"), limit = 2)
        return when {
            args.isEmpty() -> reply(FindContentRequest.FindPublished(0, MAX_LINES), message, null)
            words[0].equals("by", ignoreCase = true) -> byAuthor(words.getOrElse(1) { "" }, message)
            else ->
                reply(FindContentRequest.Search(args, 0, MAX_LINES), message) {
                    "No articles match \"$args\""
                }
        }
    }

    private fun byAuthor(name: String, message: Message<*>): OperationOutcome {
        if (name.isBlank()) return OperationResult.Success("Usage: $COMMAND by <author>")
        // shortcut: two authors with one display name list the older account's posts only;
        // name both in the reply if that ever happens.
        val author =
            userRepository.findActiveByDisplayName(name).firstOrNull()
                ?: userRepository.findByUsername(name)
                ?: userRepository.findByUsername(name.lowercase())
                ?: return OperationResult.Success("No author named \"$name\"")
        return reply(FindContentRequest.FindByAuthor(author.id, 0, MAX_LINES), message) {
            "No articles by \"$name\""
        }
    }

    /** Runs [request] and formats its posts; [none] is the reply when nothing matched. */
    private fun reply(
        request: FindContentRequest,
        message: Message<*>,
        none: (() -> String)?,
    ): OperationOutcome {
        val result = findContent.handle(request, message)
        val list =
            (result as? OperationResult.Success)?.payload as? ContentListResponse ?: return result
        if (list.posts.isEmpty())
            return OperationResult.Success(none?.invoke() ?: "No articles yet")
        val site = blogProperties.baseUrl.trimEnd('/')
        val shown = if (list.totalCount > MAX_LINES) list.posts.take(MAX_LINES - 1) else list.posts
        val lines = shown.map { "${it.title} - ${it.authorDisplayName} - $site/posts/${it.slug}" }
        val more = list.totalCount - shown.size
        val text = if (more > 0) lines + "...and $more more at $site" else lines
        return OperationResult.Success(text.joinToString("\n"))
    }

    private companion object {
        const val COMMAND = "articles"
        const val MAX_LINES = 4
    }
}
