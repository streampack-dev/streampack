/* Joseph B. Ottinger (C)2026 */
package dev.streampack.ideas.operation

import dev.streampack.ai.service.AiService
import dev.streampack.core.extensions.compress
import dev.streampack.core.integration.EventGateway
import dev.streampack.core.json.JacksonMappers
import dev.streampack.core.model.OperationOutcome
import dev.streampack.core.model.OperationResult
import dev.streampack.core.model.Protocol
import dev.streampack.core.model.Provenance
import dev.streampack.core.model.UserPrincipal
import dev.streampack.core.service.MessageLogService
import dev.streampack.core.service.ProvenanceStateService
import dev.streampack.core.service.TypedOperation
import dev.streampack.ideas.model.IdeaSessionState
import dev.streampack.ideas.service.IdeaAuthorResolver
import dev.streampack.ideas.service.IdeaTimerService
import dev.streampack.taxonomy.model.FindTaxonomySnapshotRequest
import dev.streampack.taxonomy.model.TaxonomySnapshot
import java.time.Duration
import java.time.Instant
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.messaging.Message
import org.springframework.messaging.support.MessageBuilder
import org.springframework.stereotype.Component
import tools.jackson.module.kotlin.convertValue

/** Captures article ideas through a stateful conversation flow across all protocols */
@Component
class ArticleOperation(
    private val stateService: ProvenanceStateService,
    private val timerService: IdeaTimerService,
    private val eventGateway: EventGateway,
    private val messageLogService: MessageLogService,
    private val ideaAuthorResolver: IdeaAuthorResolver,
    private val aiServiceProvider: ObjectProvider<AiService>,
    @Value("\${streampack.ideas.max-log-duration:60}") private val maxLogDurationMinutes: Long = 60,
    @Value("\${streampack.ideas.max-log-messages:100}") private val maxLogMessages: Int = 100,
) : TypedOperation<String>(String::class) {

    private val objectMapper = JacksonMappers.standard()

    override val priority: Int = 50
    override val addressed: Boolean = true
    override val operationGroup: String = "ideas"

    override fun canHandle(payload: String, message: Message<*>): Boolean {
        val cmd = payload.compress().lowercase()
        if (cmd == "article" || cmd.startsWith("article ")) return true

        if (!timerService.hasActiveSession(userStateKey(message))) return false

        return cmd.startsWith("content ") ||
            cmd.startsWith("logs ") ||
            cmd == "includeai" ||
            cmd == "noai" ||
            cmd == "done" ||
            cmd == "cancel"
    }

    override fun handle(payload: String, message: Message<*>): OperationOutcome? {
        val provenance =
            message.headers[Provenance.HEADER] as? Provenance
                ?: return OperationResult.Error("No provenance available.")
        val userKey = userStateKey(message)
        val compressed = payload.compress()
        val cmd = compressed.lowercase()

        return when {
            cmd == "article" || cmd.startsWith("article ") -> {
                val args = compressed.substringAfter("article", "").trim()
                startSession(args, userKey, provenance.encode(), message)
            }
            cmd.startsWith("content ") -> {
                val text = compressed.substringAfter("content", "").trim()
                addContent(text, userKey)
            }
            cmd.startsWith("logs ") -> {
                val args = compressed.substringAfter("logs", "").trim()
                addLogs(args, userKey, provenance)
            }
            cmd == "includeai" -> toggleAi(userKey, true)
            cmd == "noai" -> toggleAi(userKey, false)
            cmd == "done" -> finalize(userKey, message)
            cmd == "cancel" -> cancel(userKey)
            else -> null
        }
    }

    private fun startSession(
        args: String,
        userKey: String,
        channelUri: String,
        message: Message<*>,
    ): OperationOutcome {
        val existing = stateService.getState(userKey, IdeaSessionState.STATE_KEY)
        if (existing != null) {
            val state = objectMapper.convertValue<IdeaSessionState>(existing)
            return OperationResult.Error(
                "An idea session is already active: \"${state.title}\". " +
                    "Use '{{ref:done}}' to save or '{{ref:cancel}}' to discard it first."
            )
        }

        val title = parseTitle(args)
        if (title.isBlank()) {
            return OperationResult.Error(
                "Title is required. Usage: '{{ref:article \"My Article Title\"}}' or '{{ref:article My Title}}'"
            )
        }

        val playerName = senderName(message)
        val now = Instant.now()

        val state =
            IdeaSessionState(
                title = title,
                submitterName = playerName,
                sourceProvenance = channelUri,
                startedAt = now.epochSecond,
            )

        stateService.setState(
            userKey,
            IdeaSessionState.STATE_KEY,
            objectMapper.convertValue<Map<String, Any>>(state),
        )
        timerService.registerSession(userKey, now)

        return OperationResult.Success(
            "Idea session started: \"$title\". " +
                "Use '{{ref:content <text>}}' to add body paragraphs, " +
                "'{{ref:includeai}}' to enable AI summary/tags, " +
                "'{{ref:done}}' to save, or '{{ref:cancel}}' to discard."
        )
    }

    private fun addContent(text: String, userKey: String): OperationOutcome {
        val data =
            stateService.getState(userKey, IdeaSessionState.STATE_KEY)
                ?: return OperationResult.Error(
                    "No idea session in progress. Use '{{ref:article \"title\"}}' to start one."
                )

        if (text.isBlank()) {
            return OperationResult.Error("Content text is required after '{{ref:content}}'.")
        }

        val state = objectMapper.convertValue<IdeaSessionState>(data)
        val updated = state.copy(contentBlocks = state.contentBlocks + text)

        stateService.setState(
            userKey,
            IdeaSessionState.STATE_KEY,
            objectMapper.convertValue<Map<String, Any>>(updated),
        )
        timerService.resetSession(userKey)

        val count = updated.contentBlocks.size
        return OperationResult.Success(
            "Content block #$count added to \"${state.title}\". " +
                "Use '{{ref:content <text>}}' to add more, or '{{ref:done}}' to save."
        )
    }

    private fun addLogs(args: String, userKey: String, provenance: Provenance): OperationOutcome {
        val data =
            stateService.getState(userKey, IdeaSessionState.STATE_KEY)
                ?: return OperationResult.Error(
                    "No idea session in progress. Use '{{ref:article \"title\"}}' to start one."
                )

        val duration =
            parseDuration(args)
                ?: return OperationResult.Error(
                    "Invalid duration. Use a format like '10m', '30m', or '1h'."
                )

        val maxDuration = Duration.ofMinutes(maxLogDurationMinutes)
        val capped = if (duration > maxDuration) maxDuration else duration

        val now = Instant.now()
        val from = now.minus(capped)
        val channelUri = provenance.encode()

        val messages = messageLogService.findMessages(channelUri, from, to = now, maxLogMessages)

        if (messages.isEmpty()) {
            return OperationResult.Error(
                "No channel logs found for the last ${formatDuration(capped)}."
            )
        }

        val formatted = messages.joinToString("\n") { msg -> "> <${msg.sender}> ${msg.content}" }

        val state = objectMapper.convertValue<IdeaSessionState>(data)
        val updated = state.copy(contentBlocks = state.contentBlocks + formatted, hasLogs = true)

        stateService.setState(
            userKey,
            IdeaSessionState.STATE_KEY,
            objectMapper.convertValue<Map<String, Any>>(updated),
        )
        timerService.resetSession(userKey)

        val count = updated.contentBlocks.size
        return OperationResult.Success(
            "Added ${messages.size} log messages (last ${formatDuration(capped)}) as content block #$count."
        )
    }

    private fun toggleAi(userKey: String, enabled: Boolean): OperationOutcome {
        val data =
            stateService.getState(userKey, IdeaSessionState.STATE_KEY)
                ?: return OperationResult.Error(
                    "No idea session in progress. Use '{{ref:article \"title\"}}' to start one."
                )

        val state = objectMapper.convertValue<IdeaSessionState>(data)
        val updated = state.copy(includeAi = enabled)
        stateService.setState(
            userKey,
            IdeaSessionState.STATE_KEY,
            objectMapper.convertValue<Map<String, Any>>(updated),
        )
        timerService.resetSession(userKey)

        return if (enabled) {
            OperationResult.Success(
                "AI summary enabled for \"${state.title}\". " +
                    "On '{{ref:done}}', a generated summary and suggested tags will be appended to the draft."
            )
        } else {
            OperationResult.Success("AI summary disabled for \"${state.title}\".")
        }
    }

    /** Parses a duration string like "10m", "30m", "1h" into a Duration */
    private fun parseDuration(input: String): Duration? {
        val match =
            Regex("""^(\d+)\s*([mh])$""").matchEntire(input.trim().lowercase()) ?: return null
        val amount = match.groupValues[1].toLongOrNull() ?: return null
        if (amount <= 0) return null
        return when (match.groupValues[2]) {
            "m" -> Duration.ofMinutes(amount)
            "h" -> Duration.ofHours(amount)
            else -> null
        }
    }

    private fun formatDuration(duration: Duration): String {
        val minutes = duration.toMinutes()
        return if (minutes >= 60 && minutes % 60 == 0L) {
            "${minutes / 60}h"
        } else {
            "${minutes}m"
        }
    }

    private fun finalize(userKey: String, message: Message<*>): OperationOutcome {
        val data =
            stateService.getState(userKey, IdeaSessionState.STATE_KEY)
                ?: return OperationResult.Error(
                    "No idea session in progress. Use '{{ref:article \"title\"}}' to start one."
                )

        val state = objectMapper.convertValue<IdeaSessionState>(data)
        val aiResult = buildAiSummary(state)
        val currentPrincipal = (message.headers[Provenance.HEADER] as? Provenance)?.user
        dispatchDraftPost(state, aiResult.summary, aiResult.tags, currentPrincipal)

        stateService.clearState(userKey, IdeaSessionState.STATE_KEY)
        timerService.unregisterSession(userKey)

        val blockCount = state.contentBlocks.size
        val aiNote = aiResult.note?.let { " $it" } ?: ""
        return OperationResult.Success(
            "Idea saved as draft: \"${state.title}\" " +
                "($blockCount content block${if (blockCount != 1) "s" else ""}).$aiNote"
        )
    }

    private fun buildAiSummary(state: IdeaSessionState): AiDraftResult {
        if (!state.includeAi) return AiDraftResult(null, emptyList(), null)

        val aiService = aiServiceProvider.ifAvailable
        if (aiService == null) {
            logger.info(
                "ArticleOperation: includeai requested but AI unavailable title='{}'",
                state.title.take(120),
            )
            return AiDraftResult(
                null,
                emptyList(),
                "AI summary requested but AI is unavailable; saved without AI summary.",
            )
        }

        val taxonomy = findTaxonomySnapshot(state.sourceProvenance)
        val knownTags = taxonomy?.tags?.keys?.take(200).orEmpty()
        logger.info(
            "ArticleOperation: includeai start title='{}' contentBlocks={} hasLogs={} knownTags={}",
            state.title.take(120),
            state.contentBlocks.size,
            state.hasLogs,
            knownTags.size,
        )
        val systemPrompt =
            """
            You summarize article drafts for editorial review.
            Return ONLY valid JSON with this exact schema:
            {"summary":"string","tags":["tag1","tag2"]}

            Rules:
            - summary: produce a focused editorial draft with strong signal-to-noise.
            - For simple submissions, keep it concise.
            - For deep technical submissions, a substantially longer blog-style summary is preferred.
            - Preserve important technical details, tradeoffs, and key conclusions.
            - tags: 3-8 lowercase tags, no leading '#', no underscores.
            - If you are unsure, use fewer tags rather than inventing many.
            - Use provided known tags where appropriate.
            - Do not include markdown fences.
            """
                .trimIndent()

        val knownTagsSection =
            if (knownTags.isNotEmpty()) {
                "Known tags: ${knownTags.joinToString(", ")}".trim()
            } else {
                "Known tags: (none supplied)"
            }

        val userPrompt =
            """
            Title: ${state.title}
            Submitter: ${state.submitterName}
            Source provenance: ${state.sourceProvenance}
            Logs included: ${state.hasLogs}

            ${knownTagsSection}

            Content:
            ${state.contentBlocks.joinToString("\n\n")}
            """
                .trimIndent()

        val aiResponse =
            aiService.promptForObjectWithRaw(
                systemPrompt,
                userPrompt,
                AiSummaryResponse::class.java,
            )
        logger.info(
            "ArticleOperation: includeai response structured={} rawChars={}",
            aiResponse.value != null,
            aiResponse.raw?.length ?: 0,
        )

        val structured = aiResponse.value
        if (structured != null) {
            val summary = structured.summary?.trim().orEmpty().ifBlank { null }
            val tags =
                structured.tags
                    .mapNotNull { raw -> raw.trim().lowercase().ifBlank { null } }
                    .map { it.removePrefix("#") }
                    .filter { !it.startsWith("_") }
                    .distinct()
            if (summary == null && tags.isEmpty()) {
                logger.info("ArticleOperation: includeai structured response empty")
                return AiDraftResult(
                    null,
                    emptyList(),
                    "AI summary requested but structured response was empty; saved without AI summary.",
                )
            }
            logger.info(
                "ArticleOperation: includeai structured success summaryChars={} tags={}",
                summary?.length ?: 0,
                tags.size,
            )
            return AiDraftResult(summary, tags, "AI summary appended for admin review.")
        }

        val raw = aiResponse.raw
        if (raw.isNullOrBlank()) {
            logger.info("ArticleOperation: includeai generation failed with empty raw response")
            return AiDraftResult(
                null,
                emptyList(),
                "AI summary requested but generation failed; saved without AI summary.",
            )
        }

        return try {
            val node = parseAiJson(raw)
            if (node == null) {
                logger.info("ArticleOperation: includeai raw response invalid JSON")
                return AiDraftResult(
                    null,
                    emptyList(),
                    "AI summary requested but response was invalid JSON; saved without AI summary.",
                )
            }
            val summary = node.path("summary").asString("").trim().ifBlank { null }
            val tags =
                node
                    .path("tags")
                    .takeIf { it.isArray }
                    ?.mapNotNull { child -> child.asString("").trim().lowercase().ifBlank { null } }
                    ?.map { it.removePrefix("#") }
                    ?.filter { !it.startsWith("_") }
                    ?.distinct()
                    .orEmpty()

            if (summary == null && tags.isEmpty()) {
                logger.info("ArticleOperation: includeai JSON response empty")
                AiDraftResult(
                    null,
                    emptyList(),
                    "AI summary requested but response was empty; saved without AI summary.",
                )
            } else {
                logger.info(
                    "ArticleOperation: includeai JSON fallback success summaryChars={} tags={}",
                    summary?.length ?: 0,
                    tags.size,
                )
                AiDraftResult(summary, tags, "AI summary appended for admin review.")
            }
        } catch (_: Exception) {
            logger.info("ArticleOperation: includeai JSON parsing exception")
            AiDraftResult(
                null,
                emptyList(),
                "AI summary requested but response was invalid JSON; saved without AI summary.",
            )
        }
    }

    private fun parseAiJson(response: String): tools.jackson.databind.JsonNode? {
        val raw = response.trim()
        if (raw.isBlank()) return null

        val candidates =
            buildList {
                    add(raw)
                    add(raw.removeSurrounding("```json", "```").trim())
                    add(raw.removeSurrounding("```", "```").trim())
                    val firstBrace = raw.indexOf('{')
                    val lastBrace = raw.lastIndexOf('}')
                    if (firstBrace >= 0 && lastBrace > firstBrace) {
                        add(raw.substring(firstBrace, lastBrace + 1).trim())
                    }
                }
                .distinct()
                .filter { it.startsWith("{") && it.endsWith("}") }

        for (candidate in candidates) {
            try {
                return objectMapper.readTree(candidate)
            } catch (_: Exception) {}
        }
        return null
    }

    private fun findTaxonomySnapshot(sourceProvenance: String): TaxonomySnapshot? {
        val decoded = runCatching { Provenance.decode(sourceProvenance) }.getOrNull()
        val provenance =
            Provenance(
                protocol = Protocol.HTTP,
                serviceId = "ideas",
                replyTo = "taxonomy",
                user = decoded?.user,
            )

        val message =
            MessageBuilder.withPayload(FindTaxonomySnapshotRequest as Any)
                .setHeader(Provenance.HEADER, provenance)
                .build()

        return when (val result = eventGateway.process(message)) {
            is OperationResult.Success -> result.payload as? TaxonomySnapshot
            else -> null
        }
    }

    private fun cancel(userKey: String): OperationOutcome {
        val data = stateService.getState(userKey, IdeaSessionState.STATE_KEY)
        if (data == null) {
            return OperationResult.Success("No idea session in progress.")
        }

        val state = objectMapper.convertValue<IdeaSessionState>(data)
        stateService.clearState(userKey, IdeaSessionState.STATE_KEY)
        timerService.unregisterSession(userKey)

        return OperationResult.Success("Idea session cancelled. \"${state.title}\" was discarded.")
    }

    /** Dispatches a CreateContentRequest through EventGateway to create a draft post */
    private fun dispatchDraftPost(
        state: IdeaSessionState,
        aiSummary: String?,
        aiTags: List<String>,
        preferredUser: UserPrincipal? = null,
    ) {
        val resolvedUser = ideaAuthorResolver.resolve(state, preferredUser)
        val request =
            state.toCreateContentRequest(
                includeAttribution = resolvedUser == null,
                aiSummary = aiSummary,
                aiTags = aiTags,
            )
        val provenance =
            Provenance(
                protocol = Protocol.HTTP,
                serviceId = "ideas",
                replyTo = "",
                user = resolvedUser,
            )
        val message =
            MessageBuilder.withPayload(request as Any)
                .setHeader(Provenance.HEADER, provenance)
                .build()
        eventGateway.send(message)
    }

    /** Extracts the title, stripping surrounding quotes if present */
    private fun parseTitle(args: String): String {
        val trimmed = args.trim()
        if (trimmed.startsWith("\"") && trimmed.endsWith("\"") && trimmed.length > 1) {
            return trimmed.substring(1, trimmed.length - 1).trim()
        }
        return trimmed
    }

    /** Derives a per-user state key by appending the sender nick to the channel provenance */
    private fun userStateKey(message: Message<*>): String {
        val provenance = message.headers[Provenance.HEADER] as? Provenance
        val channelUri = provenance?.encode() ?: "unknown"
        val nick =
            message.headers["nick"] as? String ?: provenance?.user?.username ?: return channelUri
        return "$channelUri/$nick"
    }

    private data class AiDraftResult(
        val summary: String?,
        val tags: List<String>,
        val note: String?,
    )

    private data class AiSummaryResponse(
        val summary: String? = null,
        val tags: List<String> = emptyList(),
    )
}
