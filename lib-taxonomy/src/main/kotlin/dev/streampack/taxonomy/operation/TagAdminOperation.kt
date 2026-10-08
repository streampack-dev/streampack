/* Joseph B. Ottinger (C)2026 */
package dev.streampack.taxonomy.operation

import dev.streampack.core.extensions.compress
import dev.streampack.core.model.OperationOutcome
import dev.streampack.core.model.OperationResult
import dev.streampack.core.model.Provenance
import dev.streampack.core.model.Role
import dev.streampack.core.service.TypedOperation
import dev.streampack.taxonomy.TagCuration
import dev.streampack.taxonomy.model.TagChangeResult
import dev.streampack.taxonomy.model.TagReviewStatus
import org.springframework.messaging.Message
import org.springframework.stereotype.Component

/**
 * Admin text commands for the tag vocabulary (#140), the same actions as `/admin/tags`:
 * ```
 * tag review                         the open queue
 * tag alias <from> = <to>            alias (and re-point) a tag; "tag alias a b" for one-word tags
 * tag unalias <alias>
 * tag aliases
 * tag split <tag> [= <part>, <part>] split a missing-comma tag (its hint's parts by default)
 * tag keep <tag>
 * tag dismiss <tag>
 * tag stop <term>
 * tag unstop <term>
 * tag stops
 * ```
 *
 * Only an admin's `tag <subcommand>` is taken here; anyone else's, and an admin's `tag <word>` that
 * isn't a subcommand, falls through to the factoid tag search (`tag <term>`), which runs after
 * this.
 */
@Component
class TagAdminOperation(private val curation: TagCuration) : TypedOperation<String>(String::class) {

    override val priority: Int = 60
    override val addressed: Boolean = true
    override val operationGroup: String = "taxonomy"

    override fun canHandle(payload: String, message: Message<*>): Boolean {
        if (!hasRole(message, Role.ADMIN)) return false
        val words = payload.compress().split(' ', limit = 3)
        if (words.size < 2 || !words[0].equals("tag", ignoreCase = true)) return false
        val sub = words[1].lowercase()
        return if (words.size == 2) sub in BARE else sub in WITH_ARGUMENT
    }

    override fun handle(payload: String, message: Message<*>): OperationOutcome {
        val words = payload.compress().split(' ', limit = 3)
        val sub = words[1].lowercase()
        val rest = words.getOrNull(2)?.trim().orEmpty()
        val actor = actor(message)
        return try {
            when (sub) {
                "review" -> review()
                "aliases" -> aliases()
                "stops" -> stops()
                "alias" -> {
                    val (from, to) =
                        pair(rest) ?: return OperationResult.Error("Usage: tag alias <from> = <to>")
                    OperationResult.Success(changed(curation.alias(from, to, actor)))
                }
                "unalias" -> {
                    val removed = curation.removeAlias(rest, actor)
                    OperationResult.Success("'${removed.alias}' no longer means '${removed.tag}'.")
                }
                "split" -> {
                    val tag = rest.substringBefore('=').trim()
                    val parts =
                        rest
                            .substringAfter('=', "")
                            .split(',')
                            .map { it.trim() }
                            .filter {
                                it.isNotEmpty()
                            }
                    OperationResult.Success(
                        changed(curation.split(tag, parts.ifEmpty { null }, actor))
                    )
                }
                "keep" -> {
                    curation.keep(rest, actor)
                    OperationResult.Success("Kept '$rest' as a tag.")
                }
                "dismiss" -> {
                    curation.dismiss(rest, actor)
                    OperationResult.Success("Dismissed '$rest' from the review queue.")
                }
                "stop" -> {
                    val stop = curation.stop(rest, actor)
                    OperationResult.Success(
                        "'${stop.term}' is stoplisted: it's dropped from tags written from now on."
                    )
                }
                "unstop" -> {
                    val stop = curation.unstop(rest, actor)
                    OperationResult.Success("'${stop.term}' is off the stoplist.")
                }
                else -> OperationResult.Error("Unknown tag subcommand: $sub")
            }
        } catch (e: IllegalArgumentException) {
            OperationResult.Error(e.message ?: "Bad request")
        } catch (e: TagCuration.NotFoundException) {
            OperationResult.Error(e.message ?: "Not found")
        }
    }

    private fun review(): OperationOutcome {
        val queue = curation.queue(TagReviewStatus.OPEN, 0, LISTED)
        if (queue.entries.isEmpty())
            return OperationResult.Success("The tag review queue is empty.")
        val listed =
            queue.entries.joinToString("; ") { entry ->
                val ai =
                    entry.aiCandidate?.let {
                        ", AI: $it ${"%.0f".format((entry.aiConfidence ?: 0.0) * 100)}%"
                    } ?: ""
                "${entry.tag} (${entry.hintKind.name.lowercase()} ${entry.hintTags.joinToString("+")}$ai)"
            }
        return OperationResult.Success("${queue.openCount} to review: $listed")
    }

    private fun aliases(): OperationOutcome {
        val all = curation.aliases()
        if (all.isEmpty()) return OperationResult.Success("No tag aliases.")
        return OperationResult.Success(
            "Tag aliases: " +
                all.take(LISTED).joinToString("; ") { "${it.alias} = ${it.tag}" } +
                if (all.size > LISTED) " (and ${all.size - LISTED} more)" else ""
        )
    }

    private fun stops(): OperationOutcome {
        val all = curation.stoplist()
        if (all.isEmpty()) return OperationResult.Success("The tag stoplist is empty.")
        return OperationResult.Success(
            "Tag stoplist: " +
                all.take(LISTED).joinToString(", ") { it.term } +
                if (all.size > LISTED) " (and ${all.size - LISTED} more)" else ""
        )
    }

    private fun changed(result: TagChangeResult): String =
        "'${result.tag}' is now ${result.now.joinToString(", ") { "'$it'" }}: " +
            "${result.posts} post(s) and ${result.factoids} factoid(s) re-pointed."

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
        val BARE = setOf("review", "aliases", "stops")
        val WITH_ARGUMENT = setOf("alias", "unalias", "split", "keep", "dismiss", "stop", "unstop")
    }
}
