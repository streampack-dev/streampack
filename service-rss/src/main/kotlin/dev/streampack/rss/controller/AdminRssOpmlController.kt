/* Joseph B. Ottinger (C)2026 */
package dev.streampack.rss.controller

import dev.streampack.core.model.Role
import dev.streampack.core.model.UserPrincipal
import dev.streampack.core.service.JwtService
import dev.streampack.rss.service.RssOpmlService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** Admin endpoints for RSS OPML import and export. */
@RestController
@RequestMapping("/admin/rss")
@Tag(name = "Admin - RSS")
@SecurityRequirement(name = "bearerAuth")
class AdminRssOpmlController(
    private val opmlService: RssOpmlService,
    private val jwtService: JwtService,
) {

    @Operation(summary = "Export active RSS feeds as OPML")
    @ApiResponse(
        responseCode = "200",
        description = "OPML export of active feeds",
        content = [Content(mediaType = MediaType.APPLICATION_XML_VALUE)],
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
    @GetMapping("/opml")
    fun export(httpRequest: HttpServletRequest): ResponseEntity<*> {
        val user = resolveUser(httpRequest) ?: return unauthorized()
        if (user.role < Role.ADMIN) return forbidden()
        return ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_XML)
            .body(opmlService.exportFeedsAsOpml())
    }

    @Operation(
        summary = "Import RSS feeds from OPML or plain text",
        description =
            "Attempts OPML parsing first. If the payload is not valid OPML, falls back to " +
                "line-by-line URL extraction and ignores non-URL lines.",
    )
    @ApiResponse(
        responseCode = "200",
        description = "Import summary",
        content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE)],
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
    @PostMapping(
        "/opml/import",
        consumes =
            [MediaType.TEXT_PLAIN_VALUE, MediaType.APPLICATION_XML_VALUE, MediaType.TEXT_XML_VALUE],
        produces = ["application/json"],
    )
    fun importOpml(@RequestBody body: String, httpRequest: HttpServletRequest): ResponseEntity<*> {
        val user = resolveUser(httpRequest) ?: return unauthorized()
        if (user.role < Role.ADMIN) return forbidden()
        return ResponseEntity.ok(opmlService.importFeeds(body))
    }

    private fun resolveUser(request: HttpServletRequest): UserPrincipal? {
        val header = request.getHeader("Authorization") ?: return null
        if (!header.startsWith("Bearer ")) return null
        return jwtService.validateToken(header.removePrefix("Bearer ").trim())
    }

    private fun unauthorized(): ResponseEntity<ProblemDetail> =
        ResponseEntity.status(HttpStatus.UNAUTHORIZED)
            .body(
                ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, "Authentication required")
            )

    private fun forbidden(): ResponseEntity<ProblemDetail> =
        ResponseEntity.status(HttpStatus.FORBIDDEN)
            .body(
                ProblemDetail.forStatusAndDetail(
                    HttpStatus.FORBIDDEN,
                    "Insufficient privileges: requires ADMIN",
                )
            )
}
