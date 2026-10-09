/* Joseph B. Ottinger (C)2026 */
package dev.streampack.rss.controller

import dev.streampack.core.model.Role
import dev.streampack.core.model.UserPrincipal
import dev.streampack.core.service.JwtService
import dev.streampack.rss.model.AdminRssItemsResponse
import dev.streampack.rss.model.RssItemRatingRequest
import dev.streampack.rss.model.RssItemRatingResponse
import dev.streampack.rss.model.RssRatingGuessRunResponse
import dev.streampack.rss.model.RssRatingStatsResponse
import dev.streampack.rss.service.RssRatingGuessService
import dev.streampack.rss.service.RssRatingService
import dev.streampack.web.controller.UserAwareController
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.servlet.http.HttpServletRequest
import java.util.UUID
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * Admins rate feed items (#187): RATES, MIGHT or DULL, to learn what's worth writing about. The
 * item list here carries ratings and never the model's guess, so the guess can't bias them; the
 * guess is read only through the CSV export and the stats. None of it is public: `/rss/items` is
 * unchanged.
 */
@RestController
@RequestMapping("/admin/rss")
@Tag(name = "Admin - RSS")
@SecurityRequirement(name = "bearerAuth")
@ApiResponse(
    responseCode = "401",
    description = "Not authenticated",
    content = [Content(schema = Schema(implementation = ProblemDetail::class))],
)
@ApiResponse(
    responseCode = "403",
    description = "Admin access required",
    content = [Content(schema = Schema(implementation = ProblemDetail::class))],
)
class AdminRssRatingController(
    private val ratings: RssRatingService,
    private val guesses: RssRatingGuessService,
    jwtService: JwtService,
) : UserAwareController(jwtService) {

    @Operation(
        summary = "Rate a feed item",
        description =
            "`RATES` (worth writing about), `MIGHT` or `DULL`. Replaces any rating the item had; " +
                "every change is kept in the rating history. The same rating again changes " +
                "nothing.",
        operationId = "rateRssItem",
    )
    @ApiResponse(
        responseCode = "200",
        description = "The item's rating",
        content = [Content(schema = Schema(implementation = RssItemRatingResponse::class))],
    )
    @ApiResponse(
        responseCode = "400",
        description = "No rating, or not RATES, MIGHT or DULL",
        content = [Content(schema = Schema(implementation = ProblemDetail::class))],
    )
    @ApiResponse(
        responseCode = "404",
        description = "No such item",
        content = [Content(schema = Schema(implementation = ProblemDetail::class))],
    )
    @PutMapping(
        "/items/{id}/rating",
        produces = ["application/json"],
        consumes = ["application/json"],
    )
    fun rate(
        @PathVariable id: UUID,
        @RequestBody request: RssItemRatingRequest,
        httpRequest: HttpServletRequest,
    ): ResponseEntity<*> =
        asAdmin(httpRequest) { admin ->
            ResponseEntity.ok(ratings.rate(id, request.rating, admin.username))
        }

    @Operation(
        summary = "Clear a feed item's rating",
        description = "Kept in the rating history. An item with no rating is left as it is.",
        operationId = "clearRssItemRating",
    )
    @ApiResponse(responseCode = "204", description = "The item has no rating")
    @ApiResponse(
        responseCode = "404",
        description = "No such item",
        content = [Content(schema = Schema(implementation = ProblemDetail::class))],
    )
    @DeleteMapping("/items/{id}/rating")
    fun clear(@PathVariable id: UUID, httpRequest: HttpServletRequest): ResponseEntity<*> =
        asAdmin(httpRequest) { admin ->
            ratings.clear(id, admin.username)
            ResponseEntity.noContent().build<Void>()
        }

    @Operation(
        summary = "Feed items with their ratings",
        description =
            "Stored items as `/rss/items` lists them (active feeds, newest first, `feed` and " +
                "`title` filters alike), each with its `rating`, `ratedBy` and `ratedAt` (null " +
                "when unrated). `rating` is `all` (default), `rates`, `might`, `dull` or " +
                "`unrated`; `size` is 1 to 100. `totalCount` and `totalPages` are for the " +
                "filter asked for. Never carries the model's guess.",
        operationId = "listRatedRssItems",
    )
    @ApiResponse(
        responseCode = "200",
        description = "A page of items",
        content = [Content(schema = Schema(implementation = AdminRssItemsResponse::class))],
    )
    @ApiResponse(
        responseCode = "400",
        description = "A bad rating filter, page or size",
        content = [Content(schema = Schema(implementation = ProblemDetail::class))],
    )
    @GetMapping("/items", produces = ["application/json"])
    fun items(
        @RequestParam(defaultValue = "all") rating: String,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "50") size: Int,
        @RequestParam(required = false) feed: String?,
        @RequestParam(required = false) title: String?,
        httpRequest: HttpServletRequest,
    ): ResponseEntity<*> =
        asAdmin(httpRequest) {
            val filter =
                RssRatingService.Filter.parse(rating)
                    ?: return@asAdmin badRequest("rating is all, rates, might, dull or unrated")
            if (page < 0) return@asAdmin badRequest("The page can't be negative.")
            if (size < 1 || size > MAX_SIZE)
                return@asAdmin badRequest("The size is 1 to $MAX_SIZE.")
            ResponseEntity.ok(ratings.list(filter, page, size, feed, title))
        }

    @Operation(
        summary = "Export ratings and guesses as CSV",
        description =
            "Every item with a rating or a guess: item_id, feed, title, link, published, " +
                "rating, rated_by, rated_at, guess, guess_confidence, guess_reason, guess_model, " +
                "guess_source, guessed_at. Empty where there's no rating or no guess.",
        operationId = "exportRssRatings",
    )
    @ApiResponse(
        responseCode = "200",
        description = "The CSV",
        content = [Content(mediaType = "text/csv", schema = Schema(type = "string"))],
    )
    // No `produces`: a refusal is a ProblemDetail, which text/csv couldn't carry
    @GetMapping("/ratings.csv")
    fun export(httpRequest: HttpServletRequest): ResponseEntity<*> =
        asAdmin(httpRequest) {
            ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"rss-ratings.csv\"")
                .body(ratings.exportCsv())
        }

    @Operation(
        summary = "How the model's guess agrees with the ratings",
        description =
            "Counts per rating; agreement overall and per rating, and the confusion matrix, " +
                "over the items with both a rating and a guess; and the items guessed RATES but " +
                "rated DULL, and rated RATES but guessed DULL.",
        operationId = "rssRatingStats",
    )
    @ApiResponse(
        responseCode = "200",
        description = "The stats",
        content = [Content(schema = Schema(implementation = RssRatingStatsResponse::class))],
    )
    @GetMapping("/rating-stats", produces = ["application/json"])
    fun stats(httpRequest: HttpServletRequest): ResponseEntity<*> =
        asAdmin(httpRequest) { ResponseEntity.ok(ratings.stats()) }

    @Operation(
        summary = "Run a guess pass now",
        description =
            "Guesses the items received in the lookback that have no guess yet, as the daily " +
                "pass does, and answers what it did. Only while the guess is on " +
                "(`RSS_RATING_MODEL_GUESS`).",
        operationId = "runRssRatingGuesses",
    )
    @ApiResponse(
        responseCode = "200",
        description = "What the pass did",
        content = [Content(schema = Schema(implementation = RssRatingGuessRunResponse::class))],
    )
    @ApiResponse(
        responseCode = "409",
        description = "The guess is off, or a pass is running already",
        content = [Content(schema = Schema(implementation = ProblemDetail::class))],
    )
    @PostMapping("/rating-guesses/run", produces = ["application/json"])
    fun runGuesses(httpRequest: HttpServletRequest): ResponseEntity<*> =
        asAdmin(httpRequest) { ResponseEntity.ok(guesses.run()) }

    /**
     * Runs [action] for an admin: 401 for no one, 403 for anyone else, 404 for an unknown item, and
     * 409 when the guess can't run.
     */
    private fun asAdmin(
        httpRequest: HttpServletRequest,
        action: (UserPrincipal) -> ResponseEntity<*>,
    ): ResponseEntity<*> {
        val user =
            resolveUser(httpRequest)
                ?: return problem(HttpStatus.UNAUTHORIZED, "Authentication required")
        if (user.role < Role.ADMIN) {
            return problem(HttpStatus.FORBIDDEN, "Insufficient privileges: requires ADMIN")
        }
        return try {
            action(user)
        } catch (e: RssRatingService.NotFoundException) {
            problem(HttpStatus.NOT_FOUND, e.message ?: "Not found")
        } catch (e: RssRatingGuessService.GuessOffException) {
            problem(HttpStatus.CONFLICT, e.message ?: "The guess is off")
        } catch (e: RssRatingGuessService.BusyException) {
            problem(HttpStatus.CONFLICT, e.message ?: "A pass is running")
        }
    }

    private fun badRequest(message: String) = problem(HttpStatus.BAD_REQUEST, message)

    private fun problem(status: HttpStatus, message: String): ResponseEntity<ProblemDetail> =
        ResponseEntity.status(status).body(ProblemDetail.forStatusAndDetail(status, message))

    companion object {
        const val MAX_SIZE = 100
    }
}
