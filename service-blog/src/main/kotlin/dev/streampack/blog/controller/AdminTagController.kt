/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.controller

import dev.streampack.core.model.Role
import dev.streampack.core.model.UserPrincipal
import dev.streampack.core.service.JwtService
import dev.streampack.taxonomy.TagCuration
import dev.streampack.taxonomy.model.TagActionEntry
import dev.streampack.taxonomy.model.TagAliasEntry
import dev.streampack.taxonomy.model.TagAliasRequest
import dev.streampack.taxonomy.model.TagChangeResult
import dev.streampack.taxonomy.model.TagReviewAliasRequest
import dev.streampack.taxonomy.model.TagReviewEntry
import dev.streampack.taxonomy.model.TagReviewListResponse
import dev.streampack.taxonomy.model.TagReviewStatus
import dev.streampack.taxonomy.model.TagSplitRequest
import dev.streampack.taxonomy.model.TagStopEntry
import dev.streampack.taxonomy.model.TagStopRequest
import dev.streampack.web.controller.UserAwareController
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.ArraySchema
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.servlet.http.HttpServletRequest
import java.util.UUID
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * The tag vocabulary's admin side (#140), for the pudl tag window: the review queue of doubtful new
 * tags, aliases and the stoplist. Every change is recorded with who made it and when.
 *
 * Tags and terms are given in bodies and query parameters, never in the path, since a tag may hold
 * `/` or `#`.
 */
@RestController
@RequestMapping("/admin/tags")
@Tag(name = "Admin - Tags")
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
class AdminTagController(private val curation: TagCuration, jwtService: JwtService) :
    UserAwareController(jwtService) {

    @Operation(
        summary = "The tag review queue",
        description =
            "Doubtful new tags: a trailing-s pair with an existing tag (PLURAL), several words " +
                "that are each a tag (MISSING_COMMA), or a near-duplicate only the AI found (AI). " +
                "Open entries by default, the AI's most confident first, then newest. `openCount` " +
                "is how many are open in all, for a launcher badge; `totalCount` and " +
                "`totalPages` are for the status asked for, so any view can show page N of M.",
        operationId = "listTagReviews",
    )
    @ApiResponse(
        responseCode = "200",
        description = "A page of the queue",
        content = [Content(schema = Schema(implementation = TagReviewListResponse::class))],
    )
    @ApiResponse(
        responseCode = "400",
        description = "A bad status, page or size",
        content = [Content(schema = Schema(implementation = ProblemDetail::class))],
    )
    @GetMapping("/review", produces = ["application/json"])
    fun queue(
        @RequestParam(defaultValue = "open") status: String,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "50") size: Int,
        httpRequest: HttpServletRequest,
    ): ResponseEntity<*> =
        asAdmin(httpRequest) {
            val wanted =
                if (status.equals("all", ignoreCase = true)) null
                else
                    runCatching { TagReviewStatus.valueOf(status.uppercase()) }.getOrNull()
                        ?: return@asAdmin badRequest(
                            "status is all, open, aliased, split, kept or dismissed"
                        )
            if (page < 0) return@asAdmin badRequest("The page can't be negative.")
            if (size < 1 || size > MAX_SIZE)
                return@asAdmin badRequest("The size is 1 to $MAX_SIZE.")
            ResponseEntity.ok(curation.queue(wanted, page, size))
        }

    @Operation(
        summary = "One entry in the tag review queue",
        description = "The entry, open or decided, as the queue lists it.",
        operationId = "getTagReview",
    )
    @ApiResponse(
        responseCode = "200",
        description = "The entry",
        content = [Content(schema = Schema(implementation = TagReviewEntry::class))],
    )
    @ApiResponse(
        responseCode = "404",
        description = "No such entry",
        content = [Content(schema = Schema(implementation = ProblemDetail::class))],
    )
    @GetMapping("/review/{id}", produces = ["application/json"])
    fun review(@PathVariable id: UUID, httpRequest: HttpServletRequest): ResponseEntity<*> =
        asAdmin(httpRequest) { ResponseEntity.ok(curation.review(id)) }

    @Operation(
        summary = "Alias a queued tag to an existing one",
        description =
            "Every post and factoid carrying the queued tag is re-pointed to `tag`, in one " +
                "transaction; the queued tag's own row goes, and it becomes an alias of `tag`. " +
                "The entry is marked ALIASED. With `dryRun=true` nothing changes: the answer is " +
                "what the alias would do, the posts and factoids it would re-point.",
        operationId = "aliasTagReview",
    )
    @ApiResponse(
        responseCode = "200",
        description = "What changed",
        content = [Content(schema = Schema(implementation = TagChangeResult::class))],
    )
    @ApiResponse(
        responseCode = "400",
        description = "The target isn't an existing tag, or is the same tag",
        content = [Content(schema = Schema(implementation = ProblemDetail::class))],
    )
    @ApiResponse(
        responseCode = "404",
        description = "No such entry",
        content = [Content(schema = Schema(implementation = ProblemDetail::class))],
    )
    @PostMapping(
        "/review/{id}/alias",
        produces = ["application/json"],
        consumes = ["application/json"],
    )
    fun aliasReview(
        @PathVariable id: UUID,
        @RequestBody request: TagReviewAliasRequest,
        @Parameter(description = DRY_RUN) @RequestParam(defaultValue = "false") dryRun: Boolean,
        httpRequest: HttpServletRequest,
    ): ResponseEntity<*> =
        asAdmin(httpRequest) { admin ->
            val tag = curation.reviewTag(id)
            ResponseEntity.ok(
                if (dryRun) curation.previewAlias(tag, request.tag, admin.username)
                else curation.alias(tag, request.tag, admin.username)
            )
        }

    @Operation(
        summary = "Split a queued tag into its parts",
        description =
            "For a missing comma: every post and factoid carrying the queued tag carries the " +
                "parts instead, in one transaction, and the queued tag's row goes. The parts " +
                "default to the entry's hint tags. The entry is marked SPLIT. With `dryRun=true` " +
                "nothing changes: the answer is what the split would do.",
        operationId = "splitTagReview",
    )
    @ApiResponse(
        responseCode = "200",
        description = "What changed",
        content = [Content(schema = Schema(implementation = TagChangeResult::class))],
    )
    @ApiResponse(
        responseCode = "400",
        description = "Fewer than two parts, or no parts and no hint to take them from",
        content = [Content(schema = Schema(implementation = ProblemDetail::class))],
    )
    @ApiResponse(
        responseCode = "404",
        description = "No such entry",
        content = [Content(schema = Schema(implementation = ProblemDetail::class))],
    )
    @PostMapping("/review/{id}/split", produces = ["application/json"])
    fun splitReview(
        @PathVariable id: UUID,
        @RequestBody(required = false) request: TagSplitRequest?,
        @Parameter(description = DRY_RUN) @RequestParam(defaultValue = "false") dryRun: Boolean,
        httpRequest: HttpServletRequest,
    ): ResponseEntity<*> =
        asAdmin(httpRequest) { admin ->
            val tag = curation.reviewTag(id)
            ResponseEntity.ok(
                if (dryRun) curation.previewSplit(tag, request?.parts, admin.username)
                else curation.split(tag, request?.parts, admin.username)
            )
        }

    @Operation(
        summary = "Keep a queued tag as a real tag",
        description = "The tag stays as it is; the entry is marked KEPT.",
        operationId = "keepTagReview",
    )
    @ApiResponse(
        responseCode = "200",
        description = "The tag kept",
        content = [Content(schema = Schema(implementation = TagChangeResult::class))],
    )
    @ApiResponse(
        responseCode = "400",
        description = "The entry was already decided",
        content = [Content(schema = Schema(implementation = ProblemDetail::class))],
    )
    @ApiResponse(
        responseCode = "404",
        description = "No such entry",
        content = [Content(schema = Schema(implementation = ProblemDetail::class))],
    )
    @PostMapping("/review/{id}/keep", produces = ["application/json"])
    fun keepReview(@PathVariable id: UUID, httpRequest: HttpServletRequest): ResponseEntity<*> =
        asAdmin(httpRequest) { admin ->
            ResponseEntity.ok(curation.keep(curation.reviewTag(id), admin.username))
        }

    @Operation(
        summary = "Dismiss a queued tag",
        description = "The tag stays as it is; the entry is marked DISMISSED.",
        operationId = "dismissTagReview",
    )
    @ApiResponse(
        responseCode = "200",
        description = "The tag dismissed",
        content = [Content(schema = Schema(implementation = TagChangeResult::class))],
    )
    @ApiResponse(
        responseCode = "400",
        description = "The entry was already decided",
        content = [Content(schema = Schema(implementation = ProblemDetail::class))],
    )
    @ApiResponse(
        responseCode = "404",
        description = "No such entry",
        content = [Content(schema = Schema(implementation = ProblemDetail::class))],
    )
    @PostMapping("/review/{id}/dismiss", produces = ["application/json"])
    fun dismissReview(@PathVariable id: UUID, httpRequest: HttpServletRequest): ResponseEntity<*> =
        asAdmin(httpRequest) { admin ->
            ResponseEntity.ok(curation.dismiss(curation.reviewTag(id), admin.username))
        }

    @Operation(summary = "Every tag alias", operationId = "listTagAliases")
    @ApiResponse(
        responseCode = "200",
        description = "The aliases, by alias",
        content =
            [Content(array = ArraySchema(schema = Schema(implementation = TagAliasEntry::class)))],
    )
    @GetMapping("/aliases", produces = ["application/json"])
    fun aliases(httpRequest: HttpServletRequest): ResponseEntity<*> =
        asAdmin(httpRequest) { ResponseEntity.ok(curation.aliases()) }

    @Operation(
        summary = "Make a tag an alias of another",
        description =
            "`alias` becomes an alias of the existing tag `tag`. Any posts and factoids carrying " +
                "`alias` are re-pointed to `tag` in one transaction, its row goes, and its own " +
                "aliases move to `tag`. Writing or looking up `alias` then finds `tag`. With " +
                "`dryRun=true` nothing changes: the answer is what the alias would do.",
        operationId = "createTagAlias",
    )
    @ApiResponse(
        responseCode = "200",
        description = "What changed",
        content = [Content(schema = Schema(implementation = TagChangeResult::class))],
    )
    @ApiResponse(
        responseCode = "400",
        description = "No alias, a target that isn't a tag, a system tag, or the same tag",
        content = [Content(schema = Schema(implementation = ProblemDetail::class))],
    )
    @PostMapping("/aliases", produces = ["application/json"], consumes = ["application/json"])
    fun createAlias(
        @RequestBody request: TagAliasRequest,
        @Parameter(description = DRY_RUN) @RequestParam(defaultValue = "false") dryRun: Boolean,
        httpRequest: HttpServletRequest,
    ): ResponseEntity<*> =
        asAdmin(httpRequest) { admin ->
            if (request.alias.isBlank()) return@asAdmin badRequest("alias is required")
            ResponseEntity.ok(
                if (dryRun) curation.previewAlias(request.alias, request.tag, admin.username)
                else curation.alias(request.alias, request.tag, admin.username)
            )
        }

    @Operation(
        summary = "Remove a tag alias",
        description =
            "Nothing is re-pointed back: what was merged stays merged, and the name is free to be " +
                "a tag of its own again.",
        operationId = "deleteTagAlias",
    )
    @ApiResponse(
        responseCode = "200",
        description = "The alias removed",
        content = [Content(schema = Schema(implementation = TagAliasEntry::class))],
    )
    @ApiResponse(
        responseCode = "404",
        description = "No such alias",
        content = [Content(schema = Schema(implementation = ProblemDetail::class))],
    )
    @DeleteMapping("/aliases", produces = ["application/json"])
    fun deleteAlias(
        @RequestParam alias: String,
        httpRequest: HttpServletRequest,
    ): ResponseEntity<*> =
        asAdmin(httpRequest) { admin ->
            ResponseEntity.ok(curation.removeAlias(alias, admin.username))
        }

    @Operation(summary = "The tag stoplist", operationId = "listTagStops")
    @ApiResponse(
        responseCode = "200",
        description = "The stoplisted terms, alphabetically",
        content =
            [Content(array = ArraySchema(schema = Schema(implementation = TagStopEntry::class)))],
    )
    @GetMapping("/stoplist", produces = ["application/json"])
    fun stoplist(httpRequest: HttpServletRequest): ResponseEntity<*> =
        asAdmin(httpRequest) { ResponseEntity.ok(curation.stoplist()) }

    @Operation(
        summary = "Stoplist a term",
        description =
            "The term is dropped, silently, from every tag list written from now on. Tags " +
                "already stored are left alone.",
        operationId = "createTagStop",
    )
    @ApiResponse(
        responseCode = "200",
        description = "The term as stoplisted",
        content = [Content(schema = Schema(implementation = TagStopEntry::class))],
    )
    @ApiResponse(
        responseCode = "400",
        description = "No term, a system tag, or an alias",
        content = [Content(schema = Schema(implementation = ProblemDetail::class))],
    )
    @PostMapping("/stoplist", produces = ["application/json"], consumes = ["application/json"])
    fun stop(
        @RequestBody request: TagStopRequest,
        httpRequest: HttpServletRequest,
    ): ResponseEntity<*> =
        asAdmin(httpRequest) { admin ->
            ResponseEntity.ok(curation.stop(request.term, admin.username))
        }

    @Operation(summary = "Take a term off the stoplist", operationId = "deleteTagStop")
    @ApiResponse(
        responseCode = "200",
        description = "The term removed",
        content = [Content(schema = Schema(implementation = TagStopEntry::class))],
    )
    @ApiResponse(
        responseCode = "404",
        description = "The term isn't stoplisted",
        content = [Content(schema = Schema(implementation = ProblemDetail::class))],
    )
    @DeleteMapping("/stoplist", produces = ["application/json"])
    fun unstop(@RequestParam term: String, httpRequest: HttpServletRequest): ResponseEntity<*> =
        asAdmin(httpRequest) { admin -> ResponseEntity.ok(curation.unstop(term, admin.username)) }

    @Operation(
        summary = "Recent changes to the tag vocabulary",
        description = "Aliases, splits, keeps, dismissals and stoplist changes: who and when.",
        operationId = "listTagActions",
    )
    @ApiResponse(
        responseCode = "200",
        description = "The latest changes, newest first",
        content =
            [Content(array = ArraySchema(schema = Schema(implementation = TagActionEntry::class)))],
    )
    @GetMapping("/actions", produces = ["application/json"])
    fun actions(
        @RequestParam(defaultValue = "50") limit: Int,
        httpRequest: HttpServletRequest,
    ): ResponseEntity<*> =
        asAdmin(httpRequest) {
            if (limit < 1 || limit > MAX_SIZE)
                return@asAdmin badRequest("The limit is 1 to $MAX_SIZE.")
            ResponseEntity.ok(curation.actions(limit))
        }

    /**
     * Runs [action] for an admin: 401 for no one, 403 for anyone else, 404 for what isn't there,
     * and 400 for a request the vocabulary refuses.
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
        const val DRY_RUN =
            "true to preview: the answer is what the action would do, and nothing is changed"
    }
}
