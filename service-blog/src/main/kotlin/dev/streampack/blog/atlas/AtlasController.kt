/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.atlas

import dev.streampack.blog.controller.ConditionalGet
import dev.streampack.core.model.Role
import dev.streampack.core.service.JwtService
import dev.streampack.taxonomy.TagVocabulary
import dev.streampack.web.controller.UserAwareController
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import java.time.Instant
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RestController

/**
 * The Atlas over HTTP (ui-pudl#184): the map of the site's tags every front end draws, the same
 * geography for all. Public and conditional; a full relayout is an admin's.
 */
@RestController
@Tag(name = "Atlas")
class AtlasController(
    private val atlas: Atlas,
    private val conditionalGet: ConditionalGet,
    private val tagVocabulary: TagVocabulary,
    jwtService: JwtService,
) : UserAwareController(jwtService) {

    @Operation(
        summary = "The Atlas: regions and places (tags) with positions, counts and pins",
        description =
            "Laid out on the first request and stored; later requests read the stored map. A new " +
                "tag is placed beside its strongest relative without moving anything; a tag no " +
                "longer used is left out but keeps its place. Conditional (ETag, Last-Modified).",
    )
    @ApiResponse(
        responseCode = "200",
        description = "The map",
        content = [Content(schema = Schema(implementation = AtlasResponse::class))],
    )
    @GetMapping(PATH, produces = ["application/json"])
    fun getAtlas(request: HttpServletRequest, response: HttpServletResponse): ResponseEntity<*> {
        val now = Instant.now()
        val validator = atlas.validator(now)
        return conditionalGet.respond(
            request,
            response,
            personal = false,
            key = listOf("atlas"),
            validator = { validator },
        ) {
            ResponseEntity.ok(atlas.map(now))
        }
    }

    @Operation(
        summary = "One place on the Atlas, with every article and factoid found there",
        description =
            "The tag is matched ignoring case, and an alias finds the place of the tag it means. " +
                "404 when it isn't on the map.",
    )
    @ApiResponse(
        responseCode = "200",
        description = "The place",
        content = [Content(schema = Schema(implementation = AtlasPlaceDetailResponse::class))],
    )
    @ApiResponse(
        responseCode = "404",
        description = "No place on the map has that tag",
        content = [Content(schema = Schema(implementation = ProblemDetail::class))],
    )
    @GetMapping("$PATH/places/{tag}", produces = ["application/json"])
    fun getPlace(
        @PathVariable tag: String,
        request: HttpServletRequest,
        response: HttpServletResponse,
    ): ResponseEntity<*> {
        val now = Instant.now()
        val validator = atlas.validator(now)
        // An alias finds its tag's place (#140).
        val name = tagVocabulary.lookup(tag) ?: tag.trim().lowercase()
        return conditionalGet.respond(
            request,
            response,
            personal = false,
            key = listOf("atlas-place", name),
            validator = { validator },
        ) {
            atlas.place(name, now)?.let { ResponseEntity.ok(it) }
                ?: problem(HttpStatus.NOT_FOUND, "No place on the map is called $tag")
        }
    }

    @Operation(
        summary = "Lay the Atlas out again (admin)",
        description =
            "Recomputes the whole layout from every published post and factoid, stores it and " +
                "returns it. Every place may move, so readers lose the map they've learned; new " +
                "tags are placed without this.",
    )
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponse(
        responseCode = "200",
        description = "The new map",
        content = [Content(schema = Schema(implementation = AtlasResponse::class))],
    )
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
    @PostMapping(RELAYOUT, produces = ["application/json"])
    fun relayout(request: HttpServletRequest): ResponseEntity<*> {
        val user =
            resolveUser(request)
                ?: return problem(HttpStatus.UNAUTHORIZED, "Authentication required")
        if (user.role < Role.ADMIN) return problem(HttpStatus.FORBIDDEN, "Admin access required")
        return ResponseEntity.ok(atlas.relayout())
    }

    private fun problem(status: HttpStatus, detail: String): ResponseEntity<ProblemDetail> =
        ResponseEntity.status(status).body(ProblemDetail.forStatusAndDetail(status, detail))

    companion object {
        const val PATH = "/atlas"
        const val RELAYOUT = "/admin/atlas/relayout"
    }
}
