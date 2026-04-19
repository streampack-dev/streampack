/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.controller

import dev.streampack.blog.config.BlogProperties
import dev.streampack.blog.model.ContentOperationConfirmation
import dev.streampack.blog.model.HardDeleteCommentRequest
import dev.streampack.blog.model.SoftDeleteCommentRequest
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
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/** HTTP adapter for admin comment deletion endpoints */
@RestController
@RequestMapping("/admin/comments")
@Tag(name = "Admin - Comment Management")
@SecurityRequirement(name = "bearerAuth")
class AdminCommentController(
    private val eventGateway: EventGateway,
    jwtService: JwtService,
    blogProperties: BlogProperties,
) : UserAwareController(jwtService) {
    private val serviceId = blogProperties.serviceId
    private val logger = LoggerFactory.getLogger(AdminCommentController::class.java)

    @Operation(summary = "Delete a comment (soft by default, hard with ?hard=true)")
    @ApiResponse(
        responseCode = "200",
        description = "Comment deleted",
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
        description = "Comment not found",
        content = [Content(schema = Schema(implementation = ProblemDetail::class))],
    )
    @DeleteMapping("/{id}", produces = ["application/json"])
    fun deleteComment(
        @PathVariable id: UUID,
        @RequestParam(defaultValue = "false") hard: Boolean,
        httpRequest: HttpServletRequest,
    ): ResponseEntity<*> {
        val user = resolveUser(httpRequest) ?: return unauthorized("Authentication required")

        val payload = if (hard) HardDeleteCommentRequest(id) else SoftDeleteCommentRequest(id)
        return dispatch(payload, "admin/comments/delete", user) { result -> mapError(result) }
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
        logger.debug("Operation error on admin comment endpoint: {}", message)
        return ResponseEntity.status(status).body(ProblemDetail.forStatusAndDetail(status, message))
    }

    private fun unauthorized(message: String): ResponseEntity<*> {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
            .body(ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, message))
    }
}
