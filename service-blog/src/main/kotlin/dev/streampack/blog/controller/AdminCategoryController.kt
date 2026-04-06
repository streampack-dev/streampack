/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.controller

import dev.streampack.blog.config.BlogProperties
import dev.streampack.blog.model.ContentOperationConfirmation
import dev.streampack.blog.model.CreateCategoryRequest
import dev.streampack.blog.model.CreateCategoryResponse
import dev.streampack.blog.model.SoftDeleteCategoryRequest
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
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** HTTP adapter for admin category management endpoints */
@RestController
@RequestMapping("/admin/categories")
@Tag(name = "Admin - Category Management")
@SecurityRequirement(name = "bearerAuth")
class AdminCategoryController(
    private val eventGateway: EventGateway,
    private val jwtService: JwtService,
    blogProperties: BlogProperties,
) {
    private val serviceId = blogProperties.serviceId
    private val logger = LoggerFactory.getLogger(AdminCategoryController::class.java)

    @Operation(summary = "Create a new category")
    @ApiResponse(
        responseCode = "201",
        description = "Category created",
        content = [Content(schema = Schema(implementation = CreateCategoryResponse::class))],
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
    @PostMapping(produces = ["application/json"], consumes = ["application/json"])
    fun createCategory(
        @RequestBody request: CreateCategoryRequest,
        httpRequest: HttpServletRequest,
    ): ResponseEntity<*> {
        val user = resolveUser(httpRequest) ?: return unauthorized("Authentication required")
        return dispatchCreated(request, "admin/categories/create", user) { result ->
            mapError(result)
        }
    }

    @Operation(summary = "Soft-delete a category")
    @ApiResponse(
        responseCode = "200",
        description = "Category deleted",
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
        description = "Category not found",
        content = [Content(schema = Schema(implementation = ProblemDetail::class))],
    )
    @DeleteMapping("/{id}", produces = ["application/json"])
    fun deleteCategory(@PathVariable id: UUID, httpRequest: HttpServletRequest): ResponseEntity<*> {
        val user = resolveUser(httpRequest) ?: return unauthorized("Authentication required")
        val payload = SoftDeleteCategoryRequest(id)
        return dispatch(payload, "admin/categories/delete", user) { result -> mapError(result) }
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
                message.contains("Insufficient privileges") -> HttpStatus.FORBIDDEN
                message.contains("not found", ignoreCase = true) -> HttpStatus.NOT_FOUND
                else -> HttpStatus.BAD_REQUEST
            }
        logger.debug("Operation error on admin category endpoint: {}", message)
        return ResponseEntity.status(status).body(ProblemDetail.forStatusAndDetail(status, message))
    }

    private fun unauthorized(message: String): ResponseEntity<*> {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
            .body(ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, message))
    }
}
