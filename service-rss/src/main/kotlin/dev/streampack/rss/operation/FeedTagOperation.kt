/* Joseph B. Ottinger (C)2026 */
package dev.streampack.rss.operation

import dev.streampack.core.extensions.compress
import dev.streampack.core.model.OperationOutcome
import dev.streampack.core.model.OperationResult
import dev.streampack.core.model.Provenance
import dev.streampack.core.model.Role
import dev.streampack.core.service.TypedOperation
import dev.streampack.rss.model.FeedTagEntry
import dev.streampack.rss.model.FeedTagStatus
import dev.streampack.rss.service.FeedTagService
import dev.streampack.taxonomy.TagCuration
import org.springframework.messaging.Message
import org.springframework.stereotype.Component

/**
 * Admin text commands for feed tags the vocabulary didn't know (#139), the same as
 * `/admin/rss/tags`:
 * ```
 * feed tags                          the waiting feed tags, the most carried first
 * feed tag <name>                    one feed tag: counts, status, examples
 * feed tag map <name> = <tag>        make it an alias of an existing tag; "map a b" for one word
 * feed tag ignore <name>             stoplist it
 * feed tag create <name>             create it as a tag now
 * ```
 */
@Component
class FeedTagOperation(private val feedTags: FeedTagService) :
    TypedOperation<String>(String::class) {

    override val priority: Int = 56
    override val addressed: Boolean = true
    override val operationGroup: String = "rss"

    override fun canHandle(payload: String, message: Message<*>): Boolean {
        if (!hasRole(message, Role.ADMIN)) return false
        val words = payload.compress().split(' ', limit = 3)
        if (!words[0].equals("feed", ignoreCase = true) || words.size < 2) return false
        val sub = words[1].lowercase()
        return (sub == "tags" && words.size == 2) || (sub == "tag" && words.size == 3)
    }

    override fun handle(payload: String, message: Message<*>): OperationOutcome {
        val words = payload.compress().split(' ', limit = 3)
        if (words[1].equals("tags", ignoreCase = true)) return waiting()
        val rest = words[2].trim()
        val action = rest.substringBefore(' ').lowercase()
        val argument = rest.substringAfter(' ', "").trim()
        val actor = actor(message)
        return try {
            when {
                action == "map" && argument.isNotEmpty() -> {
                    val (name, tag) =
                        pair(argument)
                            ?: return OperationResult.Error("Usage: feed tag map <name> = <tag>")
                    val entry = feedTags.map(name, tag, actor)
                    OperationResult.Success("Feed tag '${entry.name}' now maps to '${entry.tag}'.")
                }
                action == "ignore" && argument.isNotEmpty() -> {
                    val entry = feedTags.ignore(argument, actor)
                    OperationResult.Success(
                        "Feed tag '${entry.name}' is ignored: it's stoplisted from now on."
                    )
                }
                action == "create" && argument.isNotEmpty() -> {
                    val entry = feedTags.create(argument, actor)
                    OperationResult.Success(
                        "Feed tag '${entry.name}' is now the tag '${entry.tag}'."
                    )
                }
                else -> OperationResult.Success(describe(feedTags.get(rest)))
            }
        } catch (e: IllegalArgumentException) {
            OperationResult.Error(e.message ?: "Bad request")
        } catch (e: FeedTagService.NotFoundException) {
            OperationResult.Error(e.message ?: "Not found")
        } catch (e: TagCuration.NotFoundException) {
            OperationResult.Error(e.message ?: "Not found")
        }
    }

    private fun waiting(): OperationOutcome {
        val page = feedTags.list(FeedTagStatus.WAITING, 0, LISTED)
        if (page.tags.isEmpty()) return OperationResult.Success("No feed tags are waiting.")
        val listed = page.tags.joinToString("; ") { "${it.name} (${it.entries}/${it.feeds})" }
        return OperationResult.Success(
            "${page.waitingCount} feed tag(s) waiting (entries/feeds; a tag at " +
                "${page.promoteEntries}/${page.promoteFeeds}): $listed"
        )
    }

    private fun describe(entry: FeedTagEntry): String {
        val outcome =
            when (entry.status) {
                FeedTagStatus.WAITING -> "waiting"
                FeedTagStatus.IGNORED -> "ignored"
                else -> "${entry.status.name.lowercase()} as '${entry.tag}'"
            }
        val examples = entry.examples.joinToString("; ") { "${it.title} (${it.feedTitle})" }
        return "Feed tag '${entry.name}': $outcome, on ${entry.entries} entries across " +
            "${entry.feeds} feeds" +
            if (examples.isEmpty()) "." else ". E.g. $examples"
    }

    /** `a = b`, or two single words `a b`. */
    private fun pair(text: String): Pair<String, String>? {
        if ('=' in text) {
            val from = text.substringBefore('=').trim()
            val to = text.substringAfter('=').trim()
            return if (from.isEmpty() || to.isEmpty()) null else from to to
        }
        val words = text.split(' ')
        return if (words.size == 2) words[0] to words[1] else null
    }

    private fun actor(message: Message<*>): String {
        val provenance = message.headers[Provenance.HEADER] as? Provenance
        return provenance?.user?.username ?: senderName(message)
    }

    private companion object {
        const val LISTED = 10
    }
}
