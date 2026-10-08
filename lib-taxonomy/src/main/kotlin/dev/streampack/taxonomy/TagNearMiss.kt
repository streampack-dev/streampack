/* Joseph B. Ottinger (C)2026 */
package dev.streampack.taxonomy

import dev.streampack.ai.config.AiProperties
import dev.streampack.ai.service.AiService
import dev.streampack.taxonomy.model.NewTagEvent
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener

/**
 * The AI near-miss (#140): is a new tag a near-duplicate of one already in the vocabulary?
 *
 * It runs after the write that created the tag has committed, on a thread of its own, so the
 * contribution's reply never waits on it and never changes. The tag and the existing vocabulary go
 * to the moderation model ([AiService.moderation]); its answer (a candidate, a confidence and a
 * reason) is stored on the tag's review entry to rank and explain it
 * ([TagCuration.recordNearMiss]). When [TagProperties.aiAutoApplyThreshold] is set and met, the tag
 * is aliased to the candidate at once. Nothing happens when AI is off or [TagProperties.aiNearMiss]
 * is false.
 */
@Component
class TagNearMiss(
    private val aiServiceProvider: ObjectProvider<AiService>,
    private val aiPropertiesProvider: ObjectProvider<AiProperties>,
    private val properties: ObjectProvider<TagProperties>,
    private val vocabulary: TagVocabulary,
    private val curation: TagCuration,
) {
    private val log = LoggerFactory.getLogger(TagNearMiss::class.java)

    /** Where the classification runs: never the writer's thread. Tests may swap it. */
    var executor: Executor = Executors.newVirtualThreadPerTaskExecutor()

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    fun onNewTag(event: NewTagEvent) {
        val settings = settings()
        if (!settings.aiNearMiss) return
        if (aiPropertiesProvider.ifAvailable?.enabled != true) return
        val ai = aiServiceProvider.ifAvailable ?: return
        executor.execute {
            try {
                classify(ai, event, settings)
            } catch (e: Exception) {
                log.warn("Tag near-miss for '{}' failed: {}", event.tag, e.message)
            }
        }
    }

    private fun settings(): TagProperties = properties.getIfAvailable { TagProperties() }

    /** Asks the moderation model about [event]'s tag, and records (or applies) its answer. */
    fun classify(ai: AiService, event: NewTagEvent, settings: TagProperties = settings()) {
        val known = vocabulary.knownNames() - event.tag
        if (known.isEmpty()) return
        val listed = known.sorted().take(MAX_VOCABULARY)
        val prompt = "New tag: ${event.tag}\nExisting tags: ${listed.joinToString(", ")}"
        val answer =
            ai.moderation().promptForObject(SYSTEM, prompt, Answer::class.java)
                ?: return log.info("Tag near-miss for '{}': no answer", event.tag)
        val candidate = TagNames.stored(answer.candidate)?.takeIf { it in known && it != event.tag }
        val confidence = answer.confidence.coerceIn(0.0, 1.0)
        val reason = answer.reason.take(MAX_REASON)
        val model = aiPropertiesProvider.ifAvailable?.moderationModel
        curation.recordNearMiss(event.tag, event.source, candidate, confidence, reason, model)
        log.info(
            "Tag near-miss for '{}': {} ({})",
            event.tag,
            candidate ?: "none",
            "%.2f".format(confidence),
        )
        val threshold = settings.aiAutoApplyThreshold ?: return
        if (candidate != null && confidence >= threshold) {
            curation.alias(event.tag, candidate, "ai:${model ?: "moderation"}")
        }
    }

    /** The model's answer. [candidate] is blank when the tag is no near-duplicate. */
    data class Answer(
        val candidate: String = "",
        val confidence: Double = 0.0,
        val reason: String = "",
    )

    companion object {
        private const val MAX_VOCABULARY = 1000
        private const val MAX_REASON = 1000

        const val SYSTEM =
            """You keep the tag vocabulary of a technical site tidy. You're given a new tag and the
existing tags. Decide whether the new tag is a near-duplicate of exactly one existing tag: the same
concept written differently (singular or plural, spelling, spacing, abbreviation, a common
synonym). Tags that only share letters or a prefix but name different things are not duplicates:
java and javascript, new and news, c and c++ are all different. If it is a near-duplicate, give the
existing tag exactly as listed as candidate; otherwise leave candidate empty. confidence is 0 to 1.
reason is one short sentence."""
    }
}
