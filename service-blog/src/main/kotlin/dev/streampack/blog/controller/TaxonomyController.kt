/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.controller

import dev.streampack.core.integration.EventGateway
import dev.streampack.core.model.OperationResult
import dev.streampack.core.model.Protocol
import dev.streampack.core.model.Provenance
import dev.streampack.taxonomy.model.FindTaxonomySnapshotRequest
import dev.streampack.taxonomy.model.TaxonomySnapshot
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity
import org.springframework.messaging.support.MessageBuilder
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

/** Public taxonomy endpoint for tags/categories and aggregate union counts. */
@RestController
@Tag(name = "Taxonomy")
class TaxonomyController(private val eventGateway: EventGateway) {

    @Operation(summary = "List taxonomy tags, categories, and aggregate union counts")
    @ApiResponse(
        responseCode = "200",
        description = "Taxonomy snapshot",
        content = [Content(schema = Schema(implementation = TaxonomySnapshot::class))],
    )
    @GetMapping("/taxonomy", produces = ["application/json"])
    fun getTaxonomy(): ResponseEntity<TaxonomySnapshot> {
        val message =
            MessageBuilder.withPayload(FindTaxonomySnapshotRequest as Any)
                .setHeader(
                    Provenance.HEADER,
                    Provenance(
                        protocol = Protocol.HTTP,
                        serviceId = "blog-service",
                        replyTo = "taxonomy",
                    ),
                )
                .build()

        return when (val result = eventGateway.process(message)) {
            is OperationResult.Success -> {
                val snapshot =
                    result.payload as? TaxonomySnapshot
                        ?: TaxonomySnapshot(
                            tags = emptyMap(),
                            categories = emptyMap(),
                            aggregate = emptyMap(),
                        )
                ResponseEntity.ok(snapshot)
            }
            else ->
                ResponseEntity.ok(
                    TaxonomySnapshot(
                        tags = emptyMap(),
                        categories = emptyMap(),
                        aggregate = emptyMap(),
                    )
                )
        }
    }
}
