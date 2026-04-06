/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.controller

import dev.streampack.blog.config.BlogProperties
import dev.streampack.blog.model.ApproveContentHttpRequest
import dev.streampack.blog.model.ApproveContentRequest
import dev.streampack.blog.model.ContentDetail
import dev.streampack.blog.model.ContentListResponse
import dev.streampack.blog.model.ContentOperationConfirmation
import dev.streampack.blog.model.DeriveTagsHttpRequest
import dev.streampack.blog.model.DeriveTagsRequest
import dev.streampack.blog.model.DeriveTagsResponse
import dev.streampack.blog.model.EditContentHttpRequest
import dev.streampack.blog.model.EditContentRequest
import dev.streampack.blog.model.FindDraftsRequest
import dev.streampack.blog.model.RemoveContentRequest
import dev.streampack.blog.model.SoftDeleteContentRequest
import dev.streampack.blog.service.CookieService
import dev.streampack.core.integration.EventGateway
import dev.streampack.core.model.OperationResult
import dev.streampack.core.model.Protocol
import dev.streampack.core.model.Provenance
import dev.streampack.core.model.UserPrincipal
import dev.streampack.core.service.JwtService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.servlet.http.HttpServletRequest
import java.util.UUID
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.messaging.support.MessageBuilder
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/** HTTP adapter for admin post management endpoints */
@RestController
@RequestMapping("/admin/posts")
@Tag(name = "Admin - Post Management")
@SecurityRequirement(name = "bearerAuth")
class AdminPostController(
    private val eventGateway: EventGateway,
    private val jwtService: JwtService,
    blogProperties: BlogProperties,
) {
    private val serviceId = blogProperties.serviceId
    private val logger = LoggerFactory.getLogger(AdminPostController::class.java)

    @Operation(summary = "List pending draft posts for review")
    @ApiResponse(
        responseCode = "200",
        description = "Paginated list of draft posts",
        content = [Content(schema = Schema(implementation = ContentListResponse::class))],
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
    @GetMapping("/pending", produces = ["application/json"])
    fun listPending(
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
        @RequestParam(defaultValue = "false") deleted: Boolean,
        httpRequest: HttpServletRequest,
    ): ResponseEntity<*> {
        val user = resolveUser(httpRequest) ?: return unauthorized("Authentication required")
        val payload = FindDraftsRequest(page, size, deleted)
        return dispatch(payload, "admin/posts/pending", user) { result -> mapError(result) }
    }

    @Operation(summary = "Approve a draft post and set publication date")
    @ApiResponse(
        responseCode = "200",
        description = "Post approved",
        content = [Content(schema = Schema(implementation = ContentDetail::class))],
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
    @ApiResponse(
        responseCode = "404",
        description = "Post not found",
        content = [Content(schema = Schema(implementation = ProblemDetail::class))],
    )
    @PutMapping("/{id}/approve", produces = ["application/json"], consumes = ["application/json"])
    fun approvePost(
        @PathVariable id: UUID,
        @RequestBody request: ApproveContentHttpRequest,
        httpRequest: HttpServletRequest,
    ): ResponseEntity<*> {
        val user = resolveUser(httpRequest) ?: return unauthorized("Authentication required")
        val payload = ApproveContentRequest(id = id, publishedAt = request.publishedAt)
        return dispatch(payload, "admin/posts/approve", user) { result -> mapError(result) }
    }

    @Operation(summary = "Admin edit a post")
    @ApiResponse(
        responseCode = "200",
        description = "Post updated",
        content = [Content(schema = Schema(implementation = ContentDetail::class))],
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
    @ApiResponse(
        responseCode = "404",
        description = "Post not found",
        content = [Content(schema = Schema(implementation = ProblemDetail::class))],
    )
    @PutMapping("/{id}", produces = ["application/json"], consumes = ["application/json"])
    fun editPost(
        @PathVariable id: UUID,
        @RequestBody request: EditContentHttpRequest,
        httpRequest: HttpServletRequest,
    ): ResponseEntity<*> {
        val user = resolveUser(httpRequest) ?: return unauthorized("Authentication required")
        val payload =
            EditContentRequest(
                id = id,
                title = request.title,
                markdownSource = request.markdownSource,
                tags = request.tags,
                categoryIds = request.categoryIds,
                summary = request.summary,
                publishedAt = request.publishedAt,
                sortOrder = request.sortOrder,
            )
        return dispatch(payload, "admin/posts/edit", user) { result -> mapError(result) }
    }

    @Operation(summary = "Derive AI tag suggestions from unsaved editor content")
    @ApiResponse(
        responseCode = "200",
        description = "Derived tags",
        content = [Content(schema = Schema(implementation = DeriveTagsResponse::class))],
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
    @ApiResponse(
        responseCode = "400",
        description = "Invalid input or AI unavailable",
        content = [Content(schema = Schema(implementation = ProblemDetail::class))],
    )
    @PostMapping(
        "/{id}/derive-tags",
        produces = ["application/json"],
        consumes = ["application/json"],
    )
    fun deriveTags(
        @PathVariable id: UUID,
        @RequestBody request: DeriveTagsHttpRequest,
        httpRequest: HttpServletRequest,
    ): ResponseEntity<*> {
        val user = resolveUser(httpRequest) ?: return unauthorized("Authentication required")
        val payload =
            DeriveTagsRequest(
                title = request.title ?: "",
                markdownSource = request.markdownSource ?: "",
                existingTags = request.existingTags ?: emptyList(),
            )
        return dispatch(payload, "admin/posts/derive-tags", user) { result -> mapError(result) }
    }

    @Operation(summary = "Delete a post (soft by default, hard with ?hard=true)")
    @ApiResponse(
        responseCode = "200",
        description = "Post deleted",
        content = [Content(schema = Schema(implementation = ContentOperationConfirmation::class))],
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
    @ApiResponse(
        responseCode = "404",
        description = "Post not found",
        content = [Content(schema = Schema(implementation = ProblemDetail::class))],
    )
    @DeleteMapping("/{id}", produces = ["application/json"])
    fun deletePost(
        @PathVariable id: UUID,
        @RequestParam(defaultValue = "false") hard: Boolean,
        httpRequest: HttpServletRequest,
    ): ResponseEntity<*> {
        val user = resolveUser(httpRequest) ?: return unauthorized("Authentication required")
        val payload = if (hard) RemoveContentRequest(id) else SoftDeleteContentRequest(id)
        return dispatch(payload, "admin/posts/delete", user) { result -> mapError(result) }
    }

    /** Extracts and validates the JWT from cookies first, then the Authorization header */
    private fun resolveUser(request: HttpServletRequest): UserPrincipal? {
        val cookieToken =
            request.cookies?.find { it.name == CookieService.ACCESS_TOKEN_COOKIE }?.value
        if (cookieToken != null) {
            val principal = jwtService.validateToken(cookieToken)
            if (principal != null) return principal
        }
        val header = request.getHeader("Authorization") ?: return null
        if (!header.startsWith("Bearer ")) return null
        val token = header.substring(7)
        return jwtService.validateToken(token)
    }

    /** Sends a payload through the event system and maps the result to an HTTP response */
    private fun dispatch(
        payload: Any,
        replyTo: String,
        user: UserPrincipal,
        onError: (OperationResult) -> ResponseEntity<*>,
    ): ResponseEntity<*> {
        val provenance =
            Provenance(
                protocol = Protocol.HTTP,
                serviceId = serviceId,
                replyTo = replyTo,
                user = user,
            )
        val message =
            MessageBuilder.withPayload(payload).setHeader(Provenance.HEADER, provenance).build()

        return when (val result = eventGateway.process(message)) {
            is OperationResult.Success -> ResponseEntity.ok(result.payload)
            is OperationResult.Error -> onError(result)
            is OperationResult.NotHandled -> {
                logger.warn("Request to {} was not handled by any operation", replyTo)
                ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(
                        ProblemDetail.forStatusAndDetail(
                            HttpStatus.INTERNAL_SERVER_ERROR,
                            "Unhandled request",
                        )
                    )
            }
        }
    }

    /** Maps an error to the appropriate HTTP status based on error message content */
    private fun mapError(result: OperationResult): ResponseEntity<*> {
        val message = (result as OperationResult.Error).message
        val status =
            when {
                message.contains("Authentication required") -> HttpStatus.UNAUTHORIZED
                message.contains("Insufficient privileges") -> HttpStatus.FORBIDDEN
                message.contains("not found", ignoreCase = true) -> HttpStatus.NOT_FOUND
                else -> HttpStatus.BAD_REQUEST
            }
        logger.debug("Operation error on admin post endpoint: {}", message)
        return ResponseEntity.status(status).body(ProblemDetail.forStatusAndDetail(status, message))
    }

    private fun unauthorized(message: String): ResponseEntity<*> {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
            .body(ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, message))
    }
}
