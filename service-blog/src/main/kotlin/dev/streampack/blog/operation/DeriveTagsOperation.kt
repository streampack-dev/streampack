/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.operation

import dev.streampack.ai.service.AiService
import dev.streampack.blog.model.DeriveTagsRequest
import dev.streampack.blog.model.DeriveTagsResponse
import dev.streampack.core.integration.EventGateway
import dev.streampack.core.json.JacksonMappers
import dev.streampack.core.model.OperationOutcome
import dev.streampack.core.model.OperationResult
import dev.streampack.core.model.Provenance
import dev.streampack.core.model.Role
import dev.streampack.core.service.TypedOperation
import dev.streampack.taxonomy.model.FindTaxonomySnapshotRequest
import dev.streampack.taxonomy.model.TaxonomySnapshot
import org.springframework.beans.factory.ObjectProvider
import org.springframework.messaging.Message
import org.springframework.messaging.support.MessageBuilder
import org.springframework.stereotype.Component

/** Non-persistent admin operation that derives AI tag suggestions from editor content. */
@Component
class DeriveTagsOperation(
    private val eventGateway: EventGateway,
    private val aiServiceProvider: ObjectProvider<AiService>,
) : TypedOperation<DeriveTagsRequest>(DeriveTagsRequest::class) {

    private val objectMapper = JacksonMappers.standard()

    override fun handle(payload: DeriveTagsRequest, message: Message<*>): OperationOutcome {
        requireRole(message, Role.ADMIN)?.let {
            return it
        }

        val title = payload.title.trim()
        val markdown = payload.markdownSource.trim()
        if (title.isBlank()) return OperationResult.Error("Title is required")
        if (markdown.isBlank()) return OperationResult.Error("Content is required")

        val aiService = aiServiceProvider.ifAvailable
        if (aiService == null) {
            logger.info("DeriveTagsOperation: AI unavailable for title='{}'", title.take(120))
            return OperationResult.Error("AI service unavailable")
        }

        val provenance = message.headers[Provenance.HEADER] as? Provenance
        val knownTags = findKnownTags(provenance)

        val systemPrompt =
            """
            You derive editorial tags for a technical article draft.
            Return ONLY valid JSON with this exact schema:
            {"tags":["tag1","tag2"]}

            Rules:
            - 3-5 tags.
            - lowercase tags, no leading '#', no underscores.
            - prefer precise technical terms over broad generic words.
            - prioritize known tags when they fit the content.
            - include at most one new tag that is not in known tags.
            - if unsure, return fewer tags.
            - do not include markdown fences or prose.
            """
                .trimIndent()

        val prompt =
            """
            Title: $title

            Existing tags: ${payload.existingTags.joinToString(", ")}
            Known tags: ${knownTags.joinToString(", ")}

            Draft content:
            $markdown
            """
                .trimIndent()

        logger.info(
            "DeriveTagsOperation: requesting AI tags title='{}' markdownChars={} existingTags={} knownTags={}",
            title.take(120),
            markdown.length,
            payload.existingTags.size,
            knownTags.size,
        )
        val aiResponse =
            aiService.promptForObjectWithRaw(systemPrompt, prompt, AiTagsResponse::class.java)
        logger.info(
            "DeriveTagsOperation: AI response structured={} rawChars={}",
            aiResponse.value != null,
            aiResponse.raw?.length ?: 0,
        )

        val structuredTags = aiResponse.value?.tags?.mapNotNull(::normalizeTag).orEmpty()
        val rawResponse = aiResponse.raw
        if (structuredTags.isEmpty() && rawResponse.isNullOrBlank()) {
            logger.warn("DeriveTagsOperation: AI returned empty structured and raw response")
            return OperationResult.Error("Failed to derive tags: AI returned empty response")
        }

        val rawTags =
            if (structuredTags.isEmpty() && !rawResponse.isNullOrBlank()) {
                logger.info(
                    "DeriveTagsOperation: structured response empty, trying raw parse fallback"
                )
                parseTags(rawResponse)
            } else {
                emptyList()
            }

        val candidateTags = if (structuredTags.isNotEmpty()) structuredTags else rawTags
        if (candidateTags.isEmpty()) {
            logger.warn(
                "DeriveTagsOperation: no parsable tag candidates. raw='{}'",
                rawResponse?.take(400),
            )
            return OperationResult.Error(
                "Failed to derive tags: AI response had no parsable tag candidates"
            )
        }

        val tags =
            filterSignificantTags(candidateTags, title, markdown, knownTags, payload.existingTags)
        if (tags.isEmpty()) {
            logger.warn(
                "DeriveTagsOperation: candidates filtered out as low confidence. candidates={}",
                candidateTags,
            )
            return OperationResult.Error(
                "Failed to derive tags: suggestions were low-confidence after filtering"
            )
        }

        logger.info(
            "DeriveTagsOperation: selected {} tags from {} candidates: {}",
            tags.size,
            candidateTags.size,
            tags.joinToString(", "),
        )
        return OperationResult.Success(DeriveTagsResponse(tags))
    }

    private fun findKnownTags(provenance: Provenance?): List<String> {
        if (provenance == null) return emptyList()
        val message =
            MessageBuilder.withPayload(FindTaxonomySnapshotRequest as Any)
                .setHeader(Provenance.HEADER, provenance)
                .build()

        return when (val result = eventGateway.process(message)) {
            is OperationResult.Success -> {
                val snapshot = result.payload as? TaxonomySnapshot
                snapshot?.aggregate?.keys?.take(250).orEmpty()
            }
            else -> emptyList()
        }
    }

    private fun parseTags(response: String): List<String> {
        val node = parseAiJson(response)
        val parsed =
            if (node != null) {
                val tagNode = if (node.has("tags")) node.path("tags") else node
                if (tagNode.isArray) {
                    tagNode.mapNotNull { child -> normalizeTag(child.asString("")) }.distinct()
                } else if (tagNode.isString) {
                    tagNode.asString("").split(',').mapNotNull(::normalizeTag).distinct()
                } else {
                    emptyList()
                }
            } else {
                response.split(',').mapNotNull(::normalizeTag).distinct()
            }

        return parsed
    }

    private fun parseAiJson(response: String): tools.jackson.databind.JsonNode? {
        val raw = response.trim()
        if (raw.isBlank()) return null

        val strippedPrefix = raw.removePrefix("json").trim()
        val candidates =
            buildList {
                    add(raw)
                    add(strippedPrefix)
                    add(raw.removeSurrounding("```json", "```").trim())
                    add(raw.removeSurrounding("```", "```").trim())
                    val firstBrace = raw.indexOf('{')
                    val lastBrace = raw.lastIndexOf('}')
                    if (firstBrace >= 0 && lastBrace > firstBrace) {
                        add(raw.substring(firstBrace, lastBrace + 1).trim())
                    }
                }
                .distinct()

        for (candidate in candidates) {
            if (candidate.isBlank()) continue
            try {
                return objectMapper.readTree(candidate)
            } catch (_: Exception) {}
        }
        return null
    }

    private fun normalizeTag(raw: String): String? {
        val cleaned = raw.trim().lowercase().removePrefix("#")
        if (cleaned.isBlank()) return null
        if (cleaned.startsWith("_")) return null
        return cleaned
    }

    private fun filterSignificantTags(
        tags: List<String>,
        title: String,
        markdown: String,
        knownTags: List<String>,
        existingTags: List<String>,
    ): List<String> {
        val titleLower = title.lowercase()
        val bodyLower = markdown.lowercase()
        val known = knownTags.map { it.lowercase() }.toSet()
        val existing = existingTags.mapNotNull(::normalizeTag).toSet()

        val scored =
            tags
                .distinct()
                .map { tag ->
                    val inKnown = tag in known || tag in existing
                    val titleHit = containsExact(titleLower, tag)
                    val bodyHit = containsExact(bodyLower, tag)
                    val bodyRepeats = occurrences(bodyLower, tag)

                    var score = 0
                    if (inKnown) score += 40
                    if (titleHit) score += 50
                    if (bodyHit) score += 20
                    if (bodyRepeats >= 2) score += 20
                    if (tag in GENERIC_LOW_SIGNAL && !titleHit) score -= 45
                    if (!inKnown && !titleHit && bodyRepeats < 2) score -= 100
                    tag to score
                }
                .sortedWith(
                    compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first }
                )

        val selected = mutableListOf<String>()
        var newTagCount = 0
        for ((tag, score) in scored) {
            if (selected.size >= 5) break
            val inKnown = tag in known || tag in existing
            if (inKnown) {
                if (score >= 30) selected += tag
                continue
            }
            if (newTagCount >= 1) continue
            if (score >= 80) {
                selected += tag
                newTagCount++
            }
        }
        return selected
    }

    private fun containsExact(text: String, token: String): Boolean {
        val escaped = Regex.escape(token)
        return Regex("""(?<![a-z0-9])$escaped(?![a-z0-9])""").containsMatchIn(text)
    }

    private fun occurrences(text: String, token: String): Int {
        val escaped = Regex.escape(token)
        return Regex("""(?<![a-z0-9])$escaped(?![a-z0-9])""").findAll(text).count()
    }

    private companion object {
        private val GENERIC_LOW_SIGNAL =
            setOf(
                "development",
                "design",
                "testing",
                "software",
                "code",
                "programming",
                "architecture",
                "tools",
                "tooling",
                "tutorial",
                "guide",
            )
    }

    private data class AiTagsResponse(val tags: List<String> = emptyList())
}
