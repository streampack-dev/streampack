/* Joseph B. Ottinger (C)2026 */
package dev.streampack.rss.service

import dev.streampack.ai.config.AiProperties
import dev.streampack.ai.service.AiService
import dev.streampack.generative.service.GenerativePromptService
import dev.streampack.rss.config.RssProperties
import dev.streampack.rss.entity.RssEntry
import dev.streampack.rss.entity.RssItemGuess
import dev.streampack.rss.entity.RssTextSource
import dev.streampack.rss.model.RssRating
import dev.streampack.rss.model.RssRatingGuessRunResponse
import dev.streampack.rss.repository.RssEntryRepository
import dev.streampack.rss.repository.RssItemGuessRepository
import dev.streampack.rss.repository.RssItemRatingRepository
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.core.io.ClassPathResource
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service

/**
 * The model's hidden guess at how the editor would rate a feed item (#187), off unless
 * `streampack.rss.rating.model-guess`. A pass takes the items received in the last
 * [RssProperties.Rating.guessLookback] that have no guess, and asks the moderation model about
 * them, [RssProperties.Rating.chunkSize] to a call, with structured output. Each answer is checked
 * and stored in `rss_item_guess`; an item with no usable answer is skipped, and tried again in a
 * later pass while it's still in the lookback.
 *
 * One plain call per chunk: at 15 to 25 items a day that's one call a day. Should the volume grow,
 * the Message Batches API is the upgrade: asynchronous and half the price, at the cost of polling
 * for the result.
 *
 * The system prompt is the rubric, then [ANSWER] (the texts' sources and the answer's shape), then
 * the examples. The rubric is the `rss-rating-rubric` prompt from `lib-generative` (#192): bundled
 * at [RUBRIC_RESOURCE], and replaced by `rss-rating-rubric.clj` or `.txt` in
 * `STREAMPACK_GENERATIVE_PROMPT_DIR` without a rebuild. An override changes how items are judged,
 * never how the answer is read: [ANSWER] stays in code. The rubric is rendered once per pass, so
 * the system prompt is the same for every chunk of a pass (and every pass, until the editor rates
 * more or the rubric changes), and prompt caching can serve it; the items are the user message.
 * Each guess records [rubricHash] of the rubric it was made with. The moderation model is never
 * asked to think (`streampack.ai.thinking` is the default model's alone).
 *
 * Nothing here blocks polling: the pass runs off the tick thread, and every failure (AI off, no
 * answer, a bad answer, an item the model invented) is logged and skipped. Guesses are never shown
 * where items are rated; only the CSV export, the rating stats and the admin list of guesses read
 * them.
 */
@Service
class RssRatingGuessService(
    private val properties: RssProperties,
    private val entries: RssEntryRepository,
    private val ratings: RssItemRatingRepository,
    private val guesses: RssItemGuessRepository,
    private val itemTexts: RssItemTextService,
    private val prompts: GenerativePromptService,
    private val aiServiceProvider: ObjectProvider<AiService>,
    private val aiPropertiesProvider: ObjectProvider<AiProperties>,
) {
    private val logger = LoggerFactory.getLogger(RssRatingGuessService::class.java)
    private val running = AtomicBoolean(false)

    /** The guess is off (`streampack.rss.rating.model-guess`). */
    class GuessOffException : RuntimeException("The model's guess is off (RSS_RATING_MODEL_GUESS)")

    /** A pass is running already. */
    class BusyException : RuntimeException("A guess pass is running already")

    val enabled: Boolean
        get() = properties.rating.modelGuess

    /** The model's answer: a guess per item. */
    data class GuessAnswer(val guesses: List<ItemGuess> = emptyList())

    /**
     * The model's guess at one item: [itemId] as it was given, [label] `RATES`, `MIGHT` or `DULL`,
     * [confidence] from 0 to 1, and a short [reason].
     */
    data class ItemGuess(
        val itemId: String = "",
        val label: String = "",
        val confidence: Double = -1.0,
        val reason: String = "",
    )

    /** The rubric a pass judges with, and its [hash], stored with each guess. */
    data class Rubric(val text: String, val hash: String)

    /** An example the model is shown: an item and its rating. */
    data class Example(
        val rating: RssRating,
        val title: String,
        val feed: String,
        val summary: String?,
    )

    /**
     * Runs a pass now. Throws [GuessOffException] when the guess is off, and [BusyException] when a
     * pass is running already; otherwise never throws.
     */
    fun run(now: Instant = Instant.now()): RssRatingGuessRunResponse {
        if (!enabled) throw GuessOffException()
        if (!running.compareAndSet(false, true)) throw BusyException()
        try {
            return pass(now)
        } finally {
            running.set(false)
        }
    }

    private fun pass(now: Instant): RssRatingGuessRunResponse {
        val settings = properties.rating
        val rated = examples()
        val shown = rated.map { it.first }.toSet()
        // An item shown as an example isn't guessed: its rating is in the prompt
        val candidates =
            guesses
                .findUnguessedSince(now.minus(settings.guessLookback), settings.maxItemsPerRun)
                .filterNot { it in shown }
        if (candidates.isEmpty()) {
            logger.info("RSS rating guess: nothing new to guess")
            return RssRatingGuessRunResponse(0, 0, 0, 0)
        }
        val ai = aiServiceProvider.getIfAvailable()
        if (ai == null) {
            logger.warn(
                "RSS rating guess: {} item(s) to guess, but AI is off; skipped",
                candidates.size,
            )
            return RssRatingGuessRunResponse(candidates.size, 0, 0, candidates.size)
        }
        val shownExamples = rated.map { it.second }
        // Rendered once, so every chunk of the pass has the same system prompt and rubric hash
        val rubric = rubric()
        val system = systemPrompt(rubric.text, shownExamples, seeds(shownExamples))
        val model = aiPropertiesProvider.getIfAvailable()?.moderationModel
        var calls = 0
        var stored = 0
        for (chunk in candidates.chunked(settings.chunkSize.coerceAtLeast(1))) {
            val items =
                entries
                    .findWithFeedByIdIn(chunk)
                    .sortedBy { chunk.indexOf(it.id) }
                    .map {
                        it to textOf(it, now)
                    }
            if (items.isEmpty()) continue
            calls++
            val answer =
                try {
                    ai.moderation()
                        .promptForObject(system, userPrompt(items), GuessAnswer::class.java)
                } catch (e: Exception) {
                    logger.warn("RSS rating guess: the call failed: {}", e.message)
                    null
                }
            if (answer == null) {
                logger.warn("RSS rating guess: no usable answer for {} item(s)", items.size)
                continue
            }
            stored +=
                store(
                    answer,
                    items.associate { it.first.id to it.second.source },
                    model,
                    rubric.hash,
                    now,
                )
        }
        val skipped = candidates.size - stored
        logger.info(
            "RSS rating guess: {} item(s), {} call(s), {} stored, {} skipped",
            candidates.size,
            calls,
            stored,
            skipped,
        )
        return RssRatingGuessRunResponse(candidates.size, calls, stored, skipped)
    }

    /** [entry]'s text; its summary if finding a better one failed. */
    private fun textOf(entry: RssEntry, now: Instant): RssItemTextService.ItemText =
        try {
            itemTexts.textFor(entry, now)
        } catch (e: Exception) {
            logger.warn("RSS rating guess: no text for {}: {}", entry.id, e.message)
            entry.summary?.let { RssItemTextService.ItemText(RssTextSource.SUMMARY, it) }
                ?: RssItemTextService.ItemText(RssTextSource.TITLE, "")
        }

    /**
     * Stores the usable guesses in [answer] for the items [asked] (each with the source of the text
     * it was judged on), made with the rubric [rubricHash], returning how many.
     */
    private fun store(
        answer: GuessAnswer,
        asked: Map<UUID, RssTextSource>,
        model: String?,
        rubricHash: String,
        now: Instant,
    ): Int {
        val seen = mutableSetOf<UUID>()
        var stored = 0
        for (guess in answer.guesses) {
            val id = runCatching { UUID.fromString(guess.itemId.trim()) }.getOrNull()
            val label = RssRating.parse(guess.label)
            val reason = guess.reason.trim()
            val problem =
                when {
                    id == null || id !in asked.keys -> "an item it wasn't asked about"
                    id in seen -> "an item twice"
                    label == null -> "a bad label"
                    guess.confidence.isNaN() || guess.confidence !in 0.0..1.0 -> "a bad confidence"
                    reason.isEmpty() -> "no reason"
                    else -> null
                }
            if (problem != null) {
                logger.warn("RSS rating guess: skipped {}: {}", problem, guess)
                continue
            }
            seen += id!!
            if (guesses.existsById(id)) continue
            guesses.save(
                RssItemGuess(
                    itemId = id,
                    label = label!!,
                    confidence = guess.confidence,
                    reason = reason.take(MAX_REASON),
                    model = model,
                    textSource = asked.getValue(id),
                    guessedAt = now,
                    rubric = rubricHash,
                ),
            )
            stored++
        }
        return stored
    }

    /**
     * The rubric, rendered from the `rss-rating-rubric` prompt: an override in
     * `STREAMPACK_GENERATIVE_PROMPT_DIR` if there's a usable one, else the bundled default. An
     * override that renders blank is no rubric at all, so the default is used instead.
     */
    fun rubric(): Rubric {
        val text =
            prompts.render(RUBRIC_PROMPT, RUBRIC_RESOURCE).trim().ifEmpty {
                logger.warn(
                    "RSS rating guess: the {} prompt is blank; using the default",
                    RUBRIC_PROMPT,
                )
                defaultRubric()
            }
        return Rubric(text, rubricHash(text))
    }

    /**
     * The editor's own most recent ratings, as examples: [RATES_EXAMPLES] RATES and [DULL_EXAMPLES]
     * DULL, newest first, each with its item's id.
     */
    fun examples(): List<Pair<UUID, Example>> {
        val chosen =
            ratings.findByRatingOrderByRatedAtDesc(
                RssRating.RATES,
                PageRequest.of(0, RATES_EXAMPLES),
            ) +
                ratings.findByRatingOrderByRatedAtDesc(
                    RssRating.DULL,
                    PageRequest.of(0, DULL_EXAMPLES),
                )
        val items = entries.findWithFeedByIdIn(chosen.map { it.itemId }).associateBy { it.id }
        return chosen.mapNotNull { rating ->
            items[rating.itemId]?.let { rating.itemId to it.asExample(rating.rating) }
        }
    }

    /** The seed examples, while the editor's own ratings hold too few RATES. */
    private fun seeds(rated: List<Example>): List<String> =
        if (rated.count { it.rating == RssRating.RATES } < properties.rating.minRatedExamples)
            properties.rating.seedExamples.filter { it.isNotBlank() }
        else emptyList()

    private fun RssEntry.asExample(rating: RssRating) = Example(rating, title, feed.title, summary)

    companion object {
        const val RATES_EXAMPLES = 10
        const val DULL_EXAMPLES = 5
        private const val MAX_REASON = 1000
        private const val MAX_SUMMARY = 300

        /** The rubric's prompt name in `lib-generative` (#192). */
        const val RUBRIC_PROMPT = "rss-rating-rubric"

        /** The bundled default rubric, used when no override is usable. */
        const val RUBRIC_RESOURCE = "dev/streampack/rss/prompts/rss-rating-rubric.txt"

        /** How many hex characters of the rubric's SHA-256 a guess records. */
        const val RUBRIC_HASH_LENGTH = 12

        /**
         * What the parsing depends on, appended to every rubric: where an item's text came from,
         * and the answer's shape. Kept in code, so an override can't break how the answer is read.
         */
        const val ANSWER =
            """Each item comes with its text and where that came from: "content" is the feed's own
full text, "page" the article page's, "summary" only the feed's short summary, and "title" nothing
but the title. With only a summary or a title you know less, so lower your confidence. Give each
item's itemId exactly as it was given, a label, your confidence from 0 to 1 that the editor would
rate it so, and a reason of one short sentence (under 20 words)."""

        /** The bundled default rubric, trimmed. */
        fun defaultRubric(): String =
            ClassPathResource(RUBRIC_RESOURCE).inputStream.use {
                it.readAllBytes().toString(Charsets.UTF_8).trim()
            }

        /** The first [RUBRIC_HASH_LENGTH] hex characters of the SHA-256 of [rubric]. */
        fun rubricHash(rubric: String): String =
            MessageDigest.getInstance("SHA-256")
                .digest(rubric.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
                .take(RUBRIC_HASH_LENGTH)

        /**
         * The system prompt: the [rubric], then [ANSWER], then the examples, the same for every
         * item in a pass.
         */
        fun systemPrompt(
            rubric: String,
            examples: List<Example>,
            seeds: List<String> = emptyList(),
        ): String {
            val instructions = rubric.trim() + "\n\n" + ANSWER
            val lines =
                examples.map { example ->
                    val summary = example.summary?.let { " ${it.take(MAX_SUMMARY)}" }.orEmpty()
                    "- ${example.rating}: \"${example.title}\" (${example.feed}).$summary"
                } + seeds.map { "- $it" }
            if (lines.isEmpty()) return instructions
            return instructions +
                "\n\nExamples of how the editor rates items:\n" +
                lines.joinToString("\n")
        }

        /**
         * The user message: the items to guess, each with its id, feed, title, link, the source of
         * its text, and the text.
         */
        fun userPrompt(items: List<Pair<RssEntry, RssItemTextService.ItemText>>): String =
            "Guess the editor's rating of each of these ${items.size} item(s).\n\n" +
                items.joinToString("\n\n") { (item, text) ->
                    val body = text.text.takeIf { it.isNotBlank() }?.let { "\ntext: $it" }.orEmpty()
                    "itemId: ${item.id}\nfeed: ${item.feed.title}\ntitle: ${item.title}\n" +
                        "link: ${item.link}\nsource: ${text.source.name.lowercase()}$body"
                }
    }
}
