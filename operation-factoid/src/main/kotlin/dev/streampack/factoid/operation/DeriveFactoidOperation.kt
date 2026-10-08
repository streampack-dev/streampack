/* Joseph B. Ottinger (C)2026 */
package dev.streampack.factoid.operation

import dev.streampack.ai.service.AiService
import dev.streampack.core.fetch.FetchException
import dev.streampack.core.fetch.GuardedFetcher
import dev.streampack.core.model.OperationOutcome
import dev.streampack.core.model.OperationResult
import dev.streampack.core.model.Role
import dev.streampack.core.service.TypedOperation
import dev.streampack.factoid.entity.FactoidAttribute
import dev.streampack.factoid.model.DeriveFactoidRequest
import dev.streampack.factoid.model.FactoidAttributeType
import dev.streampack.factoid.model.FactoidCatalog
import dev.streampack.factoid.model.FactoidCatalogEntry
import dev.streampack.factoid.model.FactoidDraft
import dev.streampack.factoid.model.FactoidMatcher
import dev.streampack.factoid.repository.FactoidAttributeRepository
import dev.streampack.factoid.repository.FactoidRepository
import dev.streampack.taxonomy.TagNames
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.messaging.Message
import org.springframework.stereotype.Component

/**
 * Drafts a factoid for a term (#132, for #114's editor): text, URLs, tags and see-also in the
 * knowledge base's own style, for its author to edit and save; nothing is stored.
 *
 * One model call writes the draft, guided by real factoids as examples, the tags in use, and the
 * factoids the surrounding writing mentions (candidates for see-also). Then everything it says is
 * checked rather than trusted: each URL is fetched (models invent addresses) and dropped unless it
 * answers; tags are limited to those in use, with at most one new; see-also only names existing
 * factoids; and the line the bot would say is measured, with one more try for a shorter text when
 * it's past the target, after which the draft comes back marked as not fitting, never cut.
 *
 * Signed-in readers only: each call costs a model request.
 */
@Component
class DeriveFactoidOperation(
    private val aiServiceProvider: ObjectProvider<AiService>,
    private val fetcher: GuardedFetcher,
    private val factoidRepository: FactoidRepository,
    private val attributeRepository: FactoidAttributeRepository,
    @Value("\${streampack.factoid.line-length:300}") private val target: Int = 300,
    @Value("\${streampack.factoid.draft.examples:spring boot,4gl,maven,openapi}")
    private val exampleSelectors: List<String> = DEFAULT_EXAMPLES,
) : TypedOperation<DeriveFactoidRequest>(DeriveFactoidRequest::class) {

    override val addressed: Boolean = false

    override fun handle(payload: DeriveFactoidRequest, message: Message<*>): OperationOutcome {
        requireRole(message, Role.USER)?.let {
            return it
        }
        val selector = payload.selector.trim()
        if (selector.isEmpty()) return OperationResult.Error("Give the factoid a name.")
        if (selector.length > MAX_SELECTOR) {
            return OperationResult.Error("A factoid's name is at most $MAX_SELECTOR characters.")
        }
        val ai = aiServiceProvider.ifAvailable ?: return OperationResult.Error(AI_UNAVAILABLE)
        val catalog = catalog()
        if (!catalog.find(selector)?.text.isNullOrBlank()) {
            return OperationResult.Error("A factoid named \"$selector\" already exists.")
        }
        val context = payload.context.take(MAX_CONTEXT)
        val candidates =
            FactoidMatcher(catalog)
                .find(context)
                .map { it.selector }
                .filter { !it.equals(selector, ignoreCase = true) }
                .distinct()
        val knownTags = attributeRepository.findTagCounts().map { it.name.lowercase() }

        val prompt = userPrompt(selector, context, examples(catalog), knownTags, candidates)
        var proposal =
            ask(ai, prompt) ?: return OperationResult.Error("The draft couldn't be written.")
        var draft = check(selector, proposal, knownTags, catalog)
        if (!draft.fits) {
            val budget = (target - (draft.lineLength - draft.text.length)).coerceAtLeast(60)
            val shorter =
                prompt +
                    "\n\nYour text made a line of ${draft.lineLength} characters; it must be at " +
                    "most $target. Write the text again in under $budget characters."
            ask(ai, shorter)?.let { retried ->
                proposal = retried
                draft = check(selector, retried, knownTags, catalog)
            }
        }
        logger.info(
            "Drafted factoid '{}': text {} chars, line {} ({}), {} URL(s) kept, {} dropped",
            selector,
            draft.text.length,
            draft.lineLength,
            if (draft.fits) "fits" else "too long",
            draft.urls.size,
            draft.droppedUrls.size,
        )
        return OperationResult.Success(draft)
    }

    private fun ask(ai: AiService, prompt: String): Proposal? {
        val response = ai.promptForObjectWithRaw(SYSTEM, prompt, Proposal::class.java)
        return response.value?.takeIf { it.text.isNotBlank() }
    }

    /** The model's proposal, held to the knowledge base's rules. */
    private fun check(
        selector: String,
        proposal: Proposal,
        knownTags: List<String>,
        catalog: FactoidCatalog,
    ): FactoidDraft {
        val text = cleanText(selector, proposal.text)
        // The first that answer, of the first few given: an invented one doesn't crowd out a real.
        val urls = mutableListOf<String>()
        val dropped = mutableListOf<String>()
        proposal.urls
            .map { it.trim() }
            .filter { it.startsWith("http://") || it.startsWith("https://") }
            .distinct()
            .take(MAX_URLS_CHECKED)
            .forEach { url ->
                when {
                    urls.size >= MAX_URLS -> Unit
                    answers(url) -> urls += url
                    else -> dropped += url
                }
            }
        val tags = tags(proposal.tags, knownTags)
        val seeAlso =
            proposal.seeAlso
                .mapNotNull { catalog.find(it)?.selector }
                .filter { !it.equals(selector, ignoreCase = true) }
                .distinct()
                .take(MAX_SEE_ALSO)
        val attributes = buildList {
            add(attribute(FactoidAttributeType.TEXT, text))
            if (urls.isNotEmpty()) add(attribute(FactoidAttributeType.URLS, urls.joinToString(",")))
            if (tags.isNotEmpty()) add(attribute(FactoidAttributeType.TAGS, tags.joinToString(",")))
            if (seeAlso.isNotEmpty()) {
                add(attribute(FactoidAttributeType.SEEALSO, seeAlso.joinToString(",")))
            }
        }
        val line = attributes.summarize(selector, "")
        return FactoidDraft(
            selector = selector,
            text = text,
            urls = urls,
            tags = tags,
            seeAlso = seeAlso,
            line = line,
            lineLength = line.length,
            fits = line.length <= target,
            droppedUrls = dropped,
        )
    }

    /** The text as the bot continues it: without "<selector> is" or quotes the model added. */
    private fun cleanText(selector: String, raw: String): String {
        var text = raw.trim().removeSurrounding("\"").trim()
        val lead = Regex("^${Regex.escape(selector)}\\s+(is|are)\\s+", RegexOption.IGNORE_CASE)
        text = text.replace(lead, "")
        return text.replace(Regex("^(is|are)\\s+", RegexOption.IGNORE_CASE), "").trim()
    }

    /** Two or three tags, those in use first, with at most one new one. */
    private fun tags(proposed: List<String>, known: List<String>): List<String> {
        val normalized = TagNames.normalizeAll(proposed).filterNot(TagNames::isSystem)
        // Known tags as stored (`self-hosted`) compare in the shape proposals have (`self hosted`).
        val inUse = known.mapNotNull(TagNames::normalize).toSet()
        val existing = normalized.filter { it in inUse }
        val fresh = normalized.filter { it !in inUse }.take(1)
        return (existing + fresh).take(MAX_TAGS)
    }

    /** Whether [url] answers, through the guarded fetcher: invented addresses don't. */
    private fun answers(url: String): Boolean =
        try {
            fetcher.get(url, maxBytes = PROBE_BYTES).status in 200..399
        } catch (e: FetchException) {
            false
        }

    private fun attribute(type: FactoidAttributeType, value: String) =
        FactoidAttribute(attributeType = type, attributeValue = value)

    private fun catalog(): FactoidCatalog {
        val texts =
            attributeRepository.findAllOfTypeWithFactoid(FactoidAttributeType.TEXT).associate {
                it.factoid.selector.lowercase() to it.attributeValue?.trim()?.ifBlank { null }
            }
        return FactoidCatalog(
            factoidRepository.findAll().map {
                FactoidCatalogEntry(it.selector, texts[it.selector.lowercase()])
            }
        )
    }

    /** A few real factoids, as the bot says them, to show the voice: those named, else some. */
    private fun examples(catalog: FactoidCatalog): List<String> {
        val chosen =
            exampleSelectors.mapNotNull { catalog.find(it) }.filter { !it.text.isNullOrBlank() }
        val fallback =
            if (chosen.size >= 2) emptyList()
            else
                catalog.entries
                    .filter { (it.text?.length ?: 0) in 100..200 && !it.text!!.startsWith("<") }
                    .sortedBy { it.selector }
                    .take(4 - chosen.size)
        return (chosen + fallback).map { entry ->
            attributeRepository
                .findByFactoidSelectorIgnoreCase(entry.selector)
                .summarize(entry.selector, "")
        }
    }

    private fun userPrompt(
        selector: String,
        context: String,
        examples: List<String>,
        knownTags: List<String>,
        candidates: List<String>,
    ): String = buildString {
        appendLine("Name: $selector")
        appendLine()
        appendLine("Factoids as the bot says them, for their voice and length:")
        examples.forEach { appendLine("- $it") }
        appendLine()
        appendLine("Known tags: ${knownTags.joinToString(", ")}")
        appendLine()
        appendLine(
            "Candidates for seeAlso (existing factoids the writing mentions): " +
                candidates.ifEmpty { listOf("(none)") }.joinToString(", ")
        )
        if (context.isNotBlank()) {
            appendLine()
            appendLine("The writing the name comes from (to tell which sense is meant):")
            append(context)
        }
    }

    /** What the model is asked to return. */
    data class Proposal(
        val text: String = "",
        val urls: List<String> = emptyList(),
        val tags: List<String> = emptyList(),
        val seeAlso: List<String> = emptyList(),
    )

    companion object {
        const val AI_UNAVAILABLE = "Drafting needs AI, which isn't configured."
        val DEFAULT_EXAMPLES = listOf("spring boot", "4gl", "maven", "openapi")
        private const val MAX_SELECTOR = 200
        private const val MAX_CONTEXT = 2000
        private const val MAX_URLS = 2
        private const val MAX_URLS_CHECKED = 4
        private const val MAX_TAGS = 3
        private const val MAX_SEE_ALSO = 3
        private const val PROBE_BYTES = 16 * 1024

        private val SYSTEM =
            """
            You write a factoid for a programming community's knowledge base: a short definition a
            chat bot says on one line as "<name> is <text>".

            text:
            - It continues "<name> is ". Begin with what kind of thing it is ("a build tool
              for ...", "a module system for Java"), never with the name or "is".
            - Then what it covers, in concrete nouns, and then the one thing a reader most needs
              next: an old name, what it's used for, a common gotcha, or a short verdict.
            - One or two sentences, 120 to 180 characters. Direct: no hedging, no hype, no
              "powerful", no "it's important to note". Match the examples' voice.
            - Use the writing it came from only to tell which sense of the name is meant.
            urls: one or two: the project's own home page, and its specification or
              documentation. Never a blog post, tutorial, news article or Wikipedia. Only
              addresses you are confident exist.
            tags: two or three, lowercase, from the known tags where they fit; at most one new.
            seeAlso: up to three of the candidates that are BROADER than this: what it belongs
              to or is a kind of (karaf -> osgi), never peers or alternatives (not felix for
              karaf). Only from the candidates; none if none fits.
            """
                .trimIndent()
    }
}
