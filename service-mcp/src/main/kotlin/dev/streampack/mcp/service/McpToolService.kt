/* Joseph B. Ottinger (C)2026 */
package dev.streampack.mcp.service

import dev.streampack.blog.model.ContentDetail
import dev.streampack.blog.model.ContentListResponse
import dev.streampack.blog.model.FindContentRequest
import dev.streampack.core.integration.EventGateway
import dev.streampack.core.model.OperationResult
import dev.streampack.core.model.Protocol
import dev.streampack.core.model.Provenance
import dev.streampack.factoid.model.FactoidAttributeType
import dev.streampack.factoid.service.FactoidService
import dev.streampack.taxonomy.model.FindTaxonomySnapshotRequest
import dev.streampack.taxonomy.model.TaxonomySnapshot
import java.net.URI
import java.util.*
import org.springframework.data.domain.PageRequest
import org.springframework.messaging.support.MessageBuilder
import org.springframework.stereotype.Service

/**
 * Read-only MCP tool backend.
 *
 * This service never writes to persistence and always dispatches with unauthenticated provenance.
 */
@Service
class McpToolService(
    private val eventGateway: EventGateway,
    private val factoidService: FactoidService,
) {
    private val maxPageSize = 100

    fun searchPosts(query: String, page: Int, size: Int): ToolResult {
        val payload =
            FindContentRequest.Search(
                query = query,
                page = page.coerceAtLeast(0),
                size = size.coerceIn(1, maxPageSize),
            )
        return when (val result = dispatch(payload, "mcp/search_posts")) {
            is OperationResult.Success -> {
                val response = result.payload as? ContentListResponse
                if (response == null) {
                    ToolResult.error("Unexpected response for search_posts")
                } else {
                    ToolResult.ok(
                        mapOf(
                            "query" to query,
                            "page" to response.page,
                            "totalPages" to response.totalPages,
                            "totalCount" to response.totalCount,
                            "posts" to response.posts,
                        )
                    )
                }
            }

            is OperationResult.Error -> ToolResult.error(result.message)
            OperationResult.NotHandled -> ToolResult.error("search_posts was not handled")
        }
    }

    fun getPost(postRef: String): ToolResult {
        val normalized = normalizePostRef(postRef)
        val payload =
            normalized.uuid?.let { FindContentRequest.FindById(it) }
                ?: normalized.slugPath?.let { FindContentRequest.FindBySlug(it) }
                ?: return ToolResult.error("Invalid postRef. Use UUID or slug path (YYYY/MM/slug).")

        return when (val result = dispatch(payload, "mcp/get_post")) {
            is OperationResult.Success -> {
                val detail = result.payload as? ContentDetail
                if (detail == null) {
                    ToolResult.error("Unexpected response for get_post")
                } else {
                    ToolResult.ok(
                        mapOf(
                            "id" to detail.id,
                            "title" to detail.title,
                            "slug" to detail.slug,
                            "excerpt" to detail.excerpt,
                            "authorDisplayName" to detail.authorDisplayName,
                            "publishedAt" to detail.publishedAt,
                            "commentCount" to detail.commentCount,
                            "tags" to detail.tags,
                            "categories" to detail.categories,
                            "renderedHtml" to detail.renderedHtml,
                        )
                    )
                }
            }

            is OperationResult.Error -> ToolResult.error(result.message)
            OperationResult.NotHandled -> ToolResult.error("get_post was not handled")
        }
    }

    fun listFactoids(page: Int, size: Int): ToolResult {
        val pageable = PageRequest.of(page.coerceAtLeast(0), size.coerceIn(1, maxPageSize))
        val results = factoidService.findAll(pageable)
        return ToolResult.ok(
            mapOf(
                "page" to results.number,
                "totalPages" to results.totalPages,
                "totalCount" to results.totalElements,
                "factoids" to
                    results.content.map {
                        mapOf(
                            "selector" to it.selector,
                            "locked" to it.locked,
                            "updatedBy" to it.updatedBy,
                            "updatedAt" to it.updatedAt,
                            "lastAccessedAt" to it.lastAccessedAt,
                            "accessCount" to it.accessCount,
                        )
                    },
            )
        )
    }

    fun searchFactoids(query: String, page: Int, size: Int): ToolResult {
        val pageable = PageRequest.of(page.coerceAtLeast(0), size.coerceIn(1, maxPageSize))
        val results = factoidService.searchPaginated(query, pageable)
        return ToolResult.ok(
            mapOf(
                "query" to query,
                "page" to results.number,
                "totalPages" to results.totalPages,
                "totalCount" to results.totalElements,
                "factoids" to
                    results.content.map {
                        mapOf(
                            "selector" to it.selector,
                            "locked" to it.locked,
                            "updatedBy" to it.updatedBy,
                            "updatedAt" to it.updatedAt,
                            "lastAccessedAt" to it.lastAccessedAt,
                            "accessCount" to it.accessCount,
                        )
                    },
            )
        )
    }

    fun getFactoid(selector: String): ToolResult {
        val attributes = factoidService.findBySelector(selector)
        if (attributes.isEmpty()) {
            return ToolResult.error("Factoid not found")
        }

        val factoid =
            factoidService.findFactoid(selector) ?: return ToolResult.error("Factoid not found")
        val values =
            attributes.associate { attribute ->
                attribute.attributeType to (attribute.attributeValue ?: "")
            }

        val rawAttributes =
            attributes
                .filter { !it.attributeValue.isNullOrBlank() }
                .associate { it.attributeType.name.lowercase() to it.attributeValue!!.trim() }

        return ToolResult.ok(
            mapOf(
                "selector" to factoid.selector,
                "locked" to factoid.locked,
                "updatedBy" to factoid.updatedBy,
                "updatedAt" to factoid.updatedAt,
                "lastAccessedAt" to factoid.lastAccessedAt,
                "accessCount" to factoid.accessCount,
                "text" to values[FactoidAttributeType.TEXT],
                "urls" to csv(values[FactoidAttributeType.URLS]),
                "tags" to csv(values[FactoidAttributeType.TAGS]),
                "languages" to csv(values[FactoidAttributeType.LANGUAGES]),
                "type" to csv(values[FactoidAttributeType.TYPE]),
                "seeAlso" to csv(values[FactoidAttributeType.SEEALSO]),
                "see" to values[FactoidAttributeType.SEE],
                "maven" to values[FactoidAttributeType.MAVEN],
                "rawAttributes" to rawAttributes,
            )
        )
    }

    fun listTaxonomy(): ToolResult {
        return when (val result = dispatch(FindTaxonomySnapshotRequest, "mcp/list_taxonomy")) {
            is OperationResult.Success -> {
                val snapshot = result.payload as? TaxonomySnapshot
                if (snapshot == null) {
                    ToolResult.error("Unexpected response for list_taxonomy")
                } else {
                    ToolResult.ok(
                        mapOf(
                            "tags" to snapshot.tags,
                            "categories" to snapshot.categories,
                            "aggregate" to snapshot.aggregate,
                        )
                    )
                }
            }

            is OperationResult.Error -> ToolResult.error(result.message)
            OperationResult.NotHandled -> ToolResult.error("list_taxonomy was not handled")
        }
    }

    fun factoidWriteReference(): ToolResult {
        return ToolResult.ok(
            mapOf(
                "description" to
                    """
                    Factoids are created via bot command operations, not via MCP mutations.
                    MCP is read-only and provides this reference so agents can generate
                    valid command syntax for human review or execution.
                    """
                        .trimIndent(),
                "examples" to
                    listOf(
                        "!webmention is a web standard for cross-site mentions.",
                        "!webmention.url=https://www.w3.org/TR/webmention/",
                        "!webmention.tags=web, standards, social-media",
                        "!webmention.seealso=activitypub, indieweb",
                    ),
                "attributeKeys" to
                    listOf("text", "url", "tags", "languages", "type", "seealso", "see", "maven"),
                "attributeSemantics" to
                    mapOf(
                        "see" to
                            "Redirect alias for canonical selector resolution (not a reference list).",
                        "seealso" to "Cross-reference list of related factoid selectors.",
                    ),
                "notes" to
                    listOf(
                        "Use '!selector is <reply>text' for literal text replies.",
                        "Use '.url', '.tags', '.languages', '.type', '.seealso', '.see', or '.maven' to set attributes.",
                        "Important: '.see' means redirect/alias behavior, not 'related reference'.",
                        "Comma-delimit list attributes such as tags and urls.",
                        "Selectors are typically normalized to lowercase.",
                        "Generated output is a draft for human review — omit attributes you are uncertain about rather than guessing.",
                    ),
                "agentGuidance" to
                    listOf(
                        "Generate lean drafts: text and url at minimum, tags sparingly.",
                        "Only populate 'see' when you intend a strict redirect alias.",
                        "Do not use post URLs in 'seealso' — only factoid selectors belong there.",
                        "Leave ambiguous attributes for human review.",
                    ),
            )
        )
    }

    private fun dispatch(payload: Any, replyTo: String): OperationResult {
        val message =
            MessageBuilder.withPayload(payload)
                .setHeader(
                    Provenance.HEADER,
                    Provenance(
                        protocol = Protocol.HTTP,
                        serviceId = "mcp-service",
                        replyTo = replyTo,
                    ),
                )
                .build()
        return eventGateway.process(message)
    }

    private fun csv(value: String?): List<String> =
        value?.split(',')?.map { it.trim() }?.filter { it.isNotBlank() }.orEmpty()

    private fun normalizePostRef(postRef: String): NormalizedPostRef {
        val trimmed = postRef.trim()
        if (trimmed.isEmpty()) return NormalizedPostRef(null, null)

        runCatching { UUID.fromString(trimmed) }
            .getOrNull()
            ?.let {
                return NormalizedPostRef(it, null)
            }

        val rawPath =
            if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
                runCatching { URI(trimmed).path }.getOrNull() ?: trimmed
            } else {
                trimmed
            }

        val path = rawPath.removePrefix("/posts/").removePrefix("/")
        if (path.isBlank()) return NormalizedPostRef(null, null)

        runCatching { UUID.fromString(path) }
            .getOrNull()
            ?.let {
                return NormalizedPostRef(it, null)
            }
        return NormalizedPostRef(null, path)
    }

    private data class NormalizedPostRef(val uuid: UUID?, val slugPath: String?)
}

data class ToolResult(
    val ok: Boolean,
    val payload: Map<String, Any?> = emptyMap(),
    val error: String? = null,
) {
    companion object {
        fun ok(payload: Map<String, Any?>): ToolResult = ToolResult(ok = true, payload = payload)

        fun error(message: String): ToolResult = ToolResult(ok = false, error = message)
    }
}
