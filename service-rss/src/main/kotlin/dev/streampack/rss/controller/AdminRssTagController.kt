/* Joseph B. Ottinger (C)2026 */
package dev.streampack.rss.controller

import dev.streampack.core.model.Role
import dev.streampack.core.model.UserPrincipal
import dev.streampack.core.service.JwtService
import dev.streampack.rss.model.FeedTagEntry
import dev.streampack.rss.model.FeedTagListResponse
import dev.streampack.rss.model.FeedTagMapRequest
import dev.streampack.rss.model.FeedTagRequest
import dev.streampack.rss.model.FeedTagStatus
import dev.streampack.rss.service.FeedTagService
import dev.streampack.taxonomy.TagCuration
import dev.streampack.web.controller.UserAwareController
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * Feed tags the tag vocabulary didn't know (#139), for the pudl tag window: the waiting ones, with
 * their counts and example entries, and an admin's decision on one: map it to a tag, ignore it, or
 * create it. Next to `/admin/tags` (the vocabulary's own aliases, stoplist and review queue).
 *
 * Feed tags are given in bodies, never in the path, since a tag may hold `/` or `#`.
 */
@RestController
@RequestMapping("/admin/rss/tags")
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
class AdminRssTagController(private val feedTags: FeedTagService, jwtService: JwtService) :
    UserAwareController(jwtService) {

    @Operation(
        summary = "Feed tags the tag vocabulary didn't know",
        description =
            "Waiting feed tags by default, the most carried first: counted, and created as tags " +
                "once on `promoteEntries` entries across `promoteFeeds` feeds. `status=all` " +
                "includes the decided ones (PROMOTED, CREATED, MAPPED, IGNORED). Feed tags that " +
                "are tags, aliases or stoplisted already never wait, and aren't listed.",
        operationId = "listFeedTags",
    )
    @ApiResponse(
        responseCode = "200",
        description = "A page of feed tags",
        content = [Content(schema = Schema(implementation = FeedTagListResponse::class))],
    )
    @ApiResponse(
        responseCode = "400",
        description = "A bad status, page or size",
        content = [Content(schema = Schema(implementation = ProblemDetail::class))],
    )
    @GetMapping(produces = ["application/json"])
    fun list(
        @RequestParam(defaultValue = "waiting") status: String,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "50") size: Int,
        httpRequest: HttpServletRequest,
    ): ResponseEntity<*> =
        asAdmin(httpRequest) {
            val wanted =
                if (status.equals("all", ignoreCase = true)) null
                else
                    runCatching { FeedTagStatus.valueOf(status.uppercase()) }.getOrNull()
                        ?: return@asAdmin badRequest(
                            "status is all, waiting, promoted, created, mapped or ignored"
                        )
            if (page < 0) return@asAdmin badRequest("The page can't be negative.")
            if (size < 1 || size > MAX_SIZE)
                return@asAdmin badRequest("The size is 1 to $MAX_SIZE.")
            ResponseEntity.ok(feedTags.list(wanted, page, size))
        }

    @Operation(
        summary = "Map a feed tag to an existing tag",
        description =
            "`name` becomes an alias of the existing tag `tag` (as `POST /admin/tags/aliases` " +
                "makes one, taken off the stoplist first if it was there), so feed entries " +
                "carrying it carry `tag`, and so does anything written with it. Marked MAPPED.",
        operationId = "mapFeedTag",
    )
    @ApiResponse(
        responseCode = "200",
        description = "The feed tag as decided",
        content = [Content(schema = Schema(implementation = FeedTagEntry::class))],
    )
    @ApiResponse(
        responseCode = "400",
        description = "The target isn't an existing tag, or is the feed tag itself",
        content = [Content(schema = Schema(implementation = ProblemDetail::class))],
    )
    @ApiResponse(
        responseCode = "404",
        description = "No feed has carried that tag",
        content = [Content(schema = Schema(implementation = ProblemDetail::class))],
    )
    @PostMapping("/map", produces = ["application/json"], consumes = ["application/json"])
    fun map(
        @RequestBody request: FeedTagMapRequest,
        httpRequest: HttpServletRequest,
    ): ResponseEntity<*> =
        asAdmin(httpRequest) { admin ->
            ResponseEntity.ok(feedTags.map(request.name, request.tag, admin.username))
        }

    @Operation(
        summary = "Ignore a feed tag",
        description =
            "`name` is stoplisted (its alias removed first if it had one): dropped from feed " +
                "entries' tags, and from every tag list written from now on. Marked IGNORED.",
        operationId = "ignoreFeedTag",
    )
    @ApiResponse(
        responseCode = "200",
        description = "The feed tag as decided",
        content = [Content(schema = Schema(implementation = FeedTagEntry::class))],
    )
    @ApiResponse(
        responseCode = "400",
        description = "No name given",
        content = [Content(schema = Schema(implementation = ProblemDetail::class))],
    )
    @ApiResponse(
        responseCode = "404",
        description = "No feed has carried that tag",
        content = [Content(schema = Schema(implementation = ProblemDetail::class))],
    )
    @PostMapping("/ignore", produces = ["application/json"], consumes = ["application/json"])
    fun ignore(
        @RequestBody request: FeedTagRequest,
        httpRequest: HttpServletRequest,
    ): ResponseEntity<*> =
        asAdmin(httpRequest) { admin ->
            ResponseEntity.ok(feedTags.ignore(request.name, admin.username))
        }

    @Operation(
        summary = "Create a feed tag as a tag now",
        description =
            "`name` is created as a tag without waiting for the threshold, through the " +
                "vocabulary's create rule: a doubtful one goes to the review queue, and the AI " +
                "near-miss checks it, as for any new tag. Marked CREATED.",
        operationId = "createFeedTag",
    )
    @ApiResponse(
        responseCode = "200",
        description = "The feed tag as decided",
        content = [Content(schema = Schema(implementation = FeedTagEntry::class))],
    )
    @ApiResponse(
        responseCode = "400",
        description = "No name given",
        content = [Content(schema = Schema(implementation = ProblemDetail::class))],
    )
    @ApiResponse(
        responseCode = "404",
        description = "No feed has carried that tag",
        content = [Content(schema = Schema(implementation = ProblemDetail::class))],
    )
    @PostMapping("/create", produces = ["application/json"], consumes = ["application/json"])
    fun create(
        @RequestBody request: FeedTagRequest,
        httpRequest: HttpServletRequest,
    ): ResponseEntity<*> =
        asAdmin(httpRequest) { admin ->
            ResponseEntity.ok(feedTags.create(request.name, admin.username))
        }

    /**
     * Runs [action] for an admin: 401 for no one, 403 for anyone else, 404 for a feed tag no feed
     * has carried, and 400 for a request the vocabulary refuses.
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
        } catch (e: FeedTagService.NotFoundException) {
            problem(HttpStatus.NOT_FOUND, e.message ?: "Not found")
        } catch (e: TagCuration.NotFoundException) {
            problem(HttpStatus.NOT_FOUND, e.message ?: "Not found")
        } catch (e: IllegalArgumentException) {
            badRequest(e.message ?: "Bad request")
        }
    }

    private fun badRequest(message: String) = problem(HttpStatus.BAD_REQUEST, message)

    private fun problem(status: HttpStatus, message: String): ResponseEntity<ProblemDetail> =
        ResponseEntity.status(status).body(ProblemDetail.forStatusAndDetail(status, message))

    companion object {
        const val MAX_SIZE = 100
    }
}
