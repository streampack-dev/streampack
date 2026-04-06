/* Joseph B. Ottinger (C)2026 */
package dev.streampack.factoid.controller

import dev.streampack.factoid.dto.FactoidAttributeResponse
import dev.streampack.factoid.dto.FactoidDetailResponse
import dev.streampack.factoid.dto.FactoidListResponse
import dev.streampack.factoid.dto.FactoidSummaryResponse
import dev.streampack.factoid.entity.Factoid
import dev.streampack.factoid.service.FactoidService
import org.springframework.data.domain.PageRequest
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/** Read-only REST endpoints for factoid browsing and search */
@RestController
@RequestMapping("/factoids")
class FactoidController(private val factoidService: FactoidService) {

    /** Paginated listing with optional search: GET /factoids?q=term&page=0&size=20 */
    @GetMapping(produces = ["application/json"])
    fun list(
        @RequestParam(required = false) q: String?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
    ): FactoidListResponse {
        val pageable = PageRequest.of(page, size.coerceAtMost(100))
        val results =
            if (q.isNullOrBlank()) {
                factoidService.findAll(pageable)
            } else {
                factoidService.searchPaginated(q, pageable)
            }
        val summaries = factoidService.summarizeFor(results.content)
        return FactoidListResponse(
            factoids = results.content.map { it.toSummary(summaries) },
            page = results.number,
            totalPages = results.totalPages,
            totalCount = results.totalElements,
        )
    }

    /** Single factoid with all rendered attributes: GET /factoids/{selector} */
    @GetMapping("/{selector}", produces = ["application/json"])
    fun get(@PathVariable selector: String): ResponseEntity<FactoidDetailResponse> {
        val factoid =
            factoidService.findFactoid(selector) ?: return ResponseEntity.notFound().build()
        val attributes = factoidService.findBySelector(selector)
        if (attributes.isEmpty()) {
            return ResponseEntity.notFound().build()
        }
        factoidService.recordAccess(selector)

        val attributeResponses =
            attributes
                .filter { !it.attributeValue.isNullOrEmpty() }
                .filter { it.attributeType.includeInSummary }
                .sortedBy { it.attributeType.ordinal }
                .map { attr ->
                    FactoidAttributeResponse(
                        type = attr.attributeType.name.lowercase(),
                        value = attr.attributeValue,
                        rendered = attr.attributeType.render(selector, attr.attributeValue),
                    )
                }

        return ResponseEntity.ok(
            FactoidDetailResponse(
                selector = factoid.selector,
                locked = factoid.locked,
                updatedBy = factoid.updatedBy,
                updatedAt = factoid.updatedAt,
                lastAccessedAt = factoid.lastAccessedAt,
                accessCount = factoid.accessCount,
                attributes = attributeResponses,
            )
        )
    }

    private fun Factoid.toSummary(
        summaries: Map<String, dev.streampack.factoid.service.FactoidService.FactoidListSummary>
    ) =
        FactoidSummaryResponse(
            selector = selector,
            locked = locked,
            updatedBy = updatedBy,
            updatedAt = updatedAt,
            lastAccessedAt = lastAccessedAt,
            accessCount = accessCount,
            text = summaries[selector]?.text,
            tags = summaries[selector]?.tags ?: emptyList(),
        )
}
