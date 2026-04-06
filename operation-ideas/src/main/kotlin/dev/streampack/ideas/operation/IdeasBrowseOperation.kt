/* Joseph B. Ottinger (C)2026 */
package dev.streampack.ideas.operation

import dev.streampack.blog.entity.Post
import dev.streampack.blog.model.PostStatus
import dev.streampack.blog.repository.PostRepository
import dev.streampack.blog.repository.PostTagRepository
import dev.streampack.blog.repository.TagRepository
import dev.streampack.core.extensions.compress
import dev.streampack.core.model.OperationOutcome
import dev.streampack.core.model.OperationResult
import dev.streampack.core.model.Provenance
import dev.streampack.core.model.Role
import dev.streampack.core.service.TransformerChainService
import dev.streampack.core.service.TypedOperation
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.messaging.Message
import org.springframework.messaging.MessageChannel
import org.springframework.messaging.support.MessageBuilder
import org.springframework.stereotype.Component

/** Admin operation for browsing, searching, and removing article ideas */
@Component
class IdeasBrowseOperation(
    private val tagRepository: TagRepository,
    private val postTagRepository: PostTagRepository,
    private val postRepository: PostRepository,
    @Qualifier("egressChannel") private val egressChannel: MessageChannel,
    private val transformerChain: TransformerChainService,
) : TypedOperation<String>(String::class) {

    override val priority: Int = 50
    override val addressed: Boolean = true
    override val operationGroup: String = "ideas"

    override fun canHandle(payload: String, message: Message<*>): Boolean {
        val cmd = payload.compress().lowercase()
        return cmd == "ideas" || cmd.startsWith("ideas ")
    }

    override fun handle(payload: String, message: Message<*>): OperationOutcome? {
        requireRole(message, Role.ADMIN)?.let {
            return it
        }

        val compressed = payload.compress()
        val args = compressed.substringAfter("ideas", "").trim()
        val cmd = args.lowercase()

        return when {
            args.isEmpty() -> listIdeas(message)
            cmd == "search" || cmd.startsWith("search ") -> {
                val term = args.substringAfter("search", "").trim()
                searchIdeas(term, message)
            }
            cmd.startsWith("remove #") -> {
                val numberStr = cmd.substringAfter("remove #").trim()
                val number = numberStr.toIntOrNull()
                if (number == null) {
                    OperationResult.Error("Invalid idea number. Usage: '{{ref:ideas remove #N}}'")
                } else {
                    removeIdea(number)
                }
            }
            cmd.startsWith("remove ") -> {
                val numberStr = cmd.substringAfter("remove ").trim()
                val number = numberStr.toIntOrNull()
                if (number == null) {
                    OperationResult.Error("Invalid idea number. Usage: '{{ref:ideas remove #N}}'")
                } else {
                    removeIdea(number)
                }
            }
            else ->
                OperationResult.Error(
                    "Unknown ideas command. Use '{{ref:ideas}}' to list, " +
                        "'{{ref:ideas search <term>}}' to search, " +
                        "or '{{ref:ideas remove #N}}' to remove."
                )
        }
    }

    private fun listIdeas(message: Message<*>): OperationOutcome {
        val ideas = findIdeaPosts()
        if (ideas.isEmpty()) {
            return OperationResult.Success("No article ideas found.")
        }
        return deliverIdeaList(ideas, "Article ideas (${ideas.size}):", message)
    }

    private fun searchIdeas(term: String, message: Message<*>): OperationOutcome {
        if (term.isBlank()) {
            return OperationResult.Error("Search term is required.")
        }
        val ideas = findIdeaPosts()
        val filtered = ideas.filter { it.title.contains(term, ignoreCase = true) }
        if (filtered.isEmpty()) {
            return OperationResult.Success("No ideas matching \"$term\".")
        }
        return deliverIdeaList(filtered, "Ideas matching \"$term\" (${filtered.size}):", message)
    }

    /** Sends header then each idea as separate DMs via egress */
    private fun deliverIdeaList(
        ideas: List<Post>,
        header: String,
        message: Message<*>,
    ): OperationOutcome {
        val sourceProvenance =
            message.headers[Provenance.HEADER] as? Provenance
                ?: return OperationResult.Error("No provenance available.")
        val dmProvenance = buildDmProvenance(sourceProvenance, message)

        sendToEgress(header, dmProvenance)
        for ((index, post) in ideas.withIndex()) {
            val authorName = extractSubmitter(post)
            val line = "#${index + 1} \"${post.title}\" by $authorName"
            sendToEgress(line, dmProvenance)
        }

        return OperationResult.Success(
            "Use '{{ref:ideas remove #N}}' to remove an idea.",
            provenance = dmProvenance,
        )
    }

    /** Builds a provenance targeting the requesting user's DM */
    private fun buildDmProvenance(source: Provenance, message: Message<*>): Provenance {
        val userNick = message.headers["nick"] as? String ?: source.user?.username ?: source.replyTo
        return Provenance(
            protocol = source.protocol,
            serviceId = source.serviceId,
            replyTo = userNick,
        )
    }

    /** Extracts the submitter name from the attribution footer in the post markdown */
    private fun extractSubmitter(post: Post): String {
        val match = CONTRIBUTOR_REGEX.find(post.markdownSource)
        return match?.groupValues?.get(1) ?: post.author?.displayName ?: "Anonymous"
    }

    private fun removeIdea(number: Int): OperationOutcome {
        val ideas = findIdeaPosts()
        if (number < 1 || number > ideas.size) {
            return OperationResult.Error(
                "Idea #$number not found. There are ${ideas.size} idea${if (ideas.size != 1) "s" else ""}."
            )
        }
        val idea = ideas[number - 1]
        postRepository.save(idea.copy(deleted = true))
        logger.info("Idea soft-deleted: {} ({})", idea.title, idea.id)
        return OperationResult.Success("Removed idea #$number: \"${idea.title}\".")
    }

    /** Finds all draft posts tagged with _idea that are not deleted */
    private fun findIdeaPosts(): List<Post> {
        val tag = tagRepository.findByName("_idea") ?: return emptyList()
        val postTags = postTagRepository.findByTag(tag.id)
        val postIds = postTags.map { it.post.id }.toSet()
        return postRepository
            .findAllById(postIds)
            .filter { it.status == PostStatus.DRAFT && !it.deleted }
            .sortedBy { it.createdAt }
    }

    private fun sendToEgress(text: String, provenance: Provenance) {
        val raw = OperationResult.Success(text)
        val transformed = transformerChain.apply(raw, provenance)
        val message =
            MessageBuilder.withPayload(transformed as Any)
                .setHeader(Provenance.HEADER, provenance)
                .build()
        egressChannel.send(message)
    }

    companion object {
        private val CONTRIBUTOR_REGEX = Regex("""\*Contributed by (.+?) via .+\*""")
    }
}
