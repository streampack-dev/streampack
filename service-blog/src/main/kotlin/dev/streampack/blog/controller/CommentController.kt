/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.controller

import dev.streampack.blog.config.BlogProperties
import dev.streampack.blog.model.CommentDetail
import dev.streampack.blog.model.CommentThreadResponse
import dev.streampack.blog.model.CreateCommentHttpRequest
import dev.streampack.blog.model.CreateCommentRequest
import dev.streampack.blog.model.EditCommentHttpRequest
import dev.streampack.blog.model.EditCommentRequest
import dev.streampack.blog.model.FindCommentsRequest
import dev.streampack.blog.repository.SlugRepository
import dev.streampack.core.integration.EventGateway
import dev.streampack.core.model.OperationResult
import dev.streampack.core.model.Protocol
import dev.streampack.core.model.Provenance
import dev.streampack.core.model.UserPrincipal
import dev.streampack.core.service.JwtService
import dev.streampack.web.controller.UserAwareController
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
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

/** HTTP adapter for comment read, create, and edit endpoints */
@RestController
@Tag(name = "Comments")
class CommentController(
    private val eventGateway: EventGateway,
    jwtService: JwtService,
    private val slugRepository: SlugRepository,
    blogProperties: BlogProperties,
) : UserAwareController(jwtService) {
    private val serviceId = blogProperties.serviceId
    private val logger = LoggerFactory.getLogger(CommentController::class.java)

    @Operation(summary = "Get threaded comments for a post")
    @ApiResponse(
        responseCode = "200",
        description = "Comment thread",
        content = [Content(schema = Schema(implementation = CommentThreadResponse::class))],
    )
    @ApiResponse(
        responseCode = "404",
        description = "Post not found",
        content = [Content(schema = Schema(implementation = ProblemDetail::class))],
    )
    @GetMapping("/posts/{year}/{month}/{slug}/comments", produces = ["application/json"])
    fun getComments(
        @PathVariable @Schema(minimum = "2007", maximum = "3000") year: Int,
        @PathVariable @Schema(minimum = "1", maximum = "12") month: Int,
        @PathVariable slug: String,
        httpRequest: HttpServletRequest,
    ): ResponseEntity<*> {
        val postId =
            resolvePostId("$year/${"%02d".format(month)}/$slug")
                ?: return notFound("Post not found")
        val user = resolveUser(httpRequest)
        val payload = FindCommentsRequest(postId)
        return dispatch(payload, "posts/comments", user) { result -> mapError(result) }
    }

    @Operation(summary = "Add a comment to a post")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponse(
        responseCode = "201",
        description = "Comment created",
        content = [Content(schema = Schema(implementation = CommentDetail::class))],
    )
    @ApiResponse(
        responseCode = "401",
        description = "Not authenticated",
        content = [Content(schema = Schema(implementation = ProblemDetail::class))],
    )
    @ApiResponse(
        responseCode = "404",
        description = "Post or parent comment not found",
        content = [Content(schema = Schema(implementation = ProblemDetail::class))],
    )
    @PostMapping(
        "/posts/{year}/{month}/{slug}/comments",
        produces = ["application/json"],
        consumes = ["application/json"],
    )
    fun createComment(
        @PathVariable @Schema(minimum = "2007", maximum = "3000") year: Int,
        @PathVariable @Schema(minimum = "1", maximum = "12") month: Int,
        @PathVariable slug: String,
        @RequestBody request: CreateCommentHttpRequest,
        httpRequest: HttpServletRequest,
    ): ResponseEntity<*> {
        val user = resolveUser(httpRequest) ?: return unauthorized("Authentication required")
        val postId =
            resolvePostId("$year/${"%02d".format(month)}/$slug")
                ?: return notFound("Post not found")
        val payload =
            CreateCommentRequest(
                postId = postId,
                parentCommentId = request.parentCommentId,
                markdownSource = request.markdownSource,
            )
        return dispatchCreated(payload, "posts/comments", user) { result -> mapError(result) }
    }

    @Operation(summary = "Edit a comment within the edit window")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponse(
        responseCode = "200",
        description = "Comment updated",
        content = [Content(schema = Schema(implementation = CommentDetail::class))],
    )
    @ApiResponse(
        responseCode = "401",
        description = "Not authenticated",
        content = [Content(schema = Schema(implementation = ProblemDetail::class))],
    )
    @ApiResponse(
        responseCode = "403",
        description = "Not authorized or edit window expired",
        content = [Content(schema = Schema(implementation = ProblemDetail::class))],
    )
    @ApiResponse(
        responseCode = "404",
        description = "Comment not found",
        content = [Content(schema = Schema(implementation = ProblemDetail::class))],
    )
    @PutMapping("/comments/{id}", produces = ["application/json"], consumes = ["application/json"])
    fun editComment(
        @PathVariable id: UUID,
        @RequestBody request: EditCommentHttpRequest,
        httpRequest: HttpServletRequest,
    ): ResponseEntity<*> {
        val user = resolveUser(httpRequest) ?: return unauthorized("Authentication required")
        val payload = EditCommentRequest(id = id, markdownSource = request.markdownSource)
        return dispatch(payload, "comments/edit", user) { result -> mapError(result) }
    }

    /** Resolves slug path to a post ID via the slug repository */
    private fun resolvePostId(slugPath: String): UUID? {
        val resolved = slugRepository.resolve(slugPath) ?: return null
        return resolved.post.id
    }

    /** Sends a payload through the event system and maps the result to an HTTP response */
    private fun dispatch(
        payload: Any,
        replyTo: String,
        user: UserPrincipal? = null,
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

    /** Sends a payload and returns 201 Created on success */
    private fun dispatchCreated(
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
            is OperationResult.Success ->
                ResponseEntity.status(HttpStatus.CREATED).body(result.payload)
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
                message.contains("Not authorized") -> HttpStatus.FORBIDDEN
                message.contains("Insufficient privileges") -> HttpStatus.FORBIDDEN
                message.contains("Edit window has expired") -> HttpStatus.FORBIDDEN
                message.contains("not found", ignoreCase = true) -> HttpStatus.NOT_FOUND
                else -> HttpStatus.BAD_REQUEST
            }
        logger.debug("Operation error on comment endpoint: {}", message)
        return ResponseEntity.status(status).body(ProblemDetail.forStatusAndDetail(status, message))
    }

    private fun unauthorized(message: String): ResponseEntity<*> {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
            .body(ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, message))
    }

    private fun notFound(message: String): ResponseEntity<*> {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
            .body(ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, message))
    }
}
