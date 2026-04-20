/* Joseph B. Ottinger (C)2026 */
package dev.streampack.rss.controller

import dev.streampack.core.integration.EventGateway
import dev.streampack.rss.model.RecordRssEntryAccessRequest
import dev.streampack.rss.model.RssAggregatedItemsResponse
import dev.streampack.rss.model.RssFeedSourcesResponse
import dev.streampack.rss.service.RssAggregatorService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
import java.util.UUID
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.messaging.support.MessageBuilder
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/** Public read-only endpoints for aggregated stored RSS entries. */
@RestController
@RequestMapping("/rss")
@Tag(name = "RSS Aggregator")
class RssAggregatorController(
    private val rssAggregatorService: RssAggregatorService,
    private val eventGateway: EventGateway,
) {

    @Operation(summary = "List stored RSS items")
    @ApiResponse(
        responseCode = "200",
        description = "Paginated stored RSS items",
        content = [Content(schema = Schema(implementation = RssAggregatedItemsResponse::class))],
    )
    @GetMapping("/items", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun items(
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
        @RequestParam(required = false) feed: String?,
        @RequestParam(required = false) title: String?,
    ): RssAggregatedItemsResponse = rssAggregatorService.listItems(page, size, feed, title)

    @Operation(summary = "List active RSS feed sources")
    @ApiResponse(
        responseCode = "200",
        description = "Active RSS feed sources available to the aggregator UI",
        content = [Content(schema = Schema(implementation = RssFeedSourcesResponse::class))],
    )
    @GetMapping("/feeds", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun feeds(): RssFeedSourcesResponse = rssAggregatorService.listFeeds()

    @Operation(summary = "Record a UI-driven access of a stored RSS item")
    @ApiResponse(responseCode = "202", description = "Access tracking accepted")
    @PostMapping("/items/{id}/access")
    fun recordAccess(@PathVariable id: UUID): ResponseEntity<Void> {
        eventGateway.send(MessageBuilder.withPayload(RecordRssEntryAccessRequest(id)).build())
        return ResponseEntity.accepted().build()
    }
}
