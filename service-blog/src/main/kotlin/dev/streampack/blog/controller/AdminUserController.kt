/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.controller

import dev.streampack.blog.config.BlogProperties
import dev.streampack.blog.model.DeleteAccountRequest
import dev.streampack.blog.model.PurgeErasedContentRequest
import dev.streampack.blog.model.RoleUpdateRequest
import dev.streampack.blog.model.SuspendAccountRequest
import dev.streampack.blog.model.UnsuspendAccountRequest
import dev.streampack.blog.service.CookieService
import dev.streampack.core.integration.EventGateway
import dev.streampack.core.model.AlterUserRequest
import dev.streampack.core.model.OperationResult
import dev.streampack.core.model.Protocol
import dev.streampack.core.model.Provenance
import dev.streampack.core.model.UserPrincipal
import dev.streampack.core.model.UserStatus
import dev.streampack.core.repository.UserRepository
import dev.streampack.core.service.JwtService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.messaging.support.MessageBuilder
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/** HTTP adapter for admin user management endpoints */
@RestController
@RequestMapping("/admin/users")
@Tag(name = "Admin - User Management")
@SecurityRequirement(name = "bearerAuth")
class AdminUserController(
    private val eventGateway: EventGateway,
    private val jwtService: JwtService,
    private val userRepository: UserRepository,
    blogProperties: BlogProperties,
) {
    private val serviceId = blogProperties.serviceId
    private val logger = LoggerFactory.getLogger(AdminUserController::class.java)

    @Operation(summary = "Change a user's role")
    @ApiResponse(
        responseCode = "200",
        description = "Role updated",
        content = [Content(schema = Schema(implementation = UserPrincipal::class))],
    )
    @ApiResponse(
        responseCode = "401",
        description = "Not authenticated",
        content = [Content(schema = Schema(implementation = ProblemDetail::class))],
    )
    @ApiResponse(
        responseCode = "403",
        description = "Insufficient privileges",
        content = [Content(schema = Schema(implementation = ProblemDetail::class))],
    )
    @ApiResponse(
        responseCode = "400",
        description = "Invalid request",
        content = [Content(schema = Schema(implementation = ProblemDetail::class))],
    )
    @PutMapping(
        "/{username}/role",
        produces = ["application/json"],
        consumes = ["application/json"],
    )
    fun changeRole(
        @PathVariable username: String,
        @RequestBody request: RoleUpdateRequest,
        httpRequest: HttpServletRequest,
    ): ResponseEntity<*> {
        val user = resolveUser(httpRequest) ?: return unauthorized("Not authenticated")

        val payload = AlterUserRequest(username = username, role = request.newRole)
        return dispatch(payload, "admin/users/role", user) { result -> mapError(result) }
    }

    @Operation(summary = "Suspend a user account")
    @ApiResponse(responseCode = "200", description = "Account suspended")
    @PutMapping("/{username}/suspend", produces = ["application/json"])
    fun suspendAccount(
        @PathVariable username: String,
        httpRequest: HttpServletRequest,
    ): ResponseEntity<*> {
        val user = resolveUser(httpRequest) ?: return unauthorized("Not authenticated")
        return dispatch(SuspendAccountRequest(username), "admin/users/suspend", user) { result ->
            mapError(result)
        }
    }

    @Operation(summary = "Unsuspend a user account")
    @ApiResponse(responseCode = "200", description = "Account unsuspended")
    @PutMapping("/{username}/unsuspend", produces = ["application/json"])
    fun unsuspendAccount(
        @PathVariable username: String,
        httpRequest: HttpServletRequest,
    ): ResponseEntity<*> {
        val user = resolveUser(httpRequest) ?: return unauthorized("Not authenticated")
        return dispatch(UnsuspendAccountRequest(username), "admin/users/unsuspend", user) { result
            ->
            mapError(result)
        }
    }

    @Operation(summary = "Erase a user account (admin-initiated)")
    @ApiResponse(responseCode = "200", description = "Account erased")
    @DeleteMapping("/{username}", produces = ["application/json"])
    fun eraseAccount(
        @PathVariable username: String,
        httpRequest: HttpServletRequest,
    ): ResponseEntity<*> {
        val user = resolveUser(httpRequest) ?: return unauthorized("Not authenticated")
        return dispatch(DeleteAccountRequest(username), "admin/users/erase", user) { result ->
            mapError(result)
        }
    }

    @Operation(summary = "Purge all content from an erased user sentinel")
    @ApiResponse(responseCode = "200", description = "Content purged")
    @DeleteMapping("/{username}/purge", produces = ["application/json"])
    fun purgeErasedContent(
        @PathVariable username: String,
        httpRequest: HttpServletRequest,
    ): ResponseEntity<*> {
        val user = resolveUser(httpRequest) ?: return unauthorized("Not authenticated")
        val targetUser =
            userRepository.findByUsername(username)
                ?: return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "User not found"))
        return dispatch(PurgeErasedContentRequest(targetUser.id), "admin/users/purge", user) {
            result ->
            mapError(result)
        }
    }

    @Operation(summary = "List erased user sentinels")
    @ApiResponse(responseCode = "200", description = "Erased user sentinels")
    @GetMapping(params = ["status"], produces = ["application/json"])
    fun listByStatus(
        @RequestParam status: UserStatus,
        httpRequest: HttpServletRequest,
    ): ResponseEntity<*> {
        val user = resolveUser(httpRequest) ?: return unauthorized("Not authenticated")
        if (user.role < dev.streampack.core.model.Role.ADMIN) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(
                    ProblemDetail.forStatusAndDetail(
                        HttpStatus.FORBIDDEN,
                        "Insufficient privileges",
                    )
                )
        }
        val users = userRepository.findByStatus(status).map { it.toUserPrincipal() }
        return ResponseEntity.ok(users)
    }

    @Operation(summary = "Export a user's data for admin review")
    @ApiResponse(responseCode = "200", description = "User data export")
    @GetMapping("/{username}/export", produces = ["application/json"])
    fun exportUserData(
        @PathVariable username: String,
        httpRequest: HttpServletRequest,
    ): ResponseEntity<*> {
        val user = resolveUser(httpRequest) ?: return unauthorized("Not authenticated")
        return dispatch(
            dev.streampack.blog.model.ExportUserDataRequest(username),
            "admin/users/export",
            user,
        ) { result ->
            mapError(result)
        }
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
                message.contains("Not authenticated") -> HttpStatus.UNAUTHORIZED
                message.contains("Insufficient privileges") -> HttpStatus.FORBIDDEN
                else -> HttpStatus.BAD_REQUEST
            }
        logger.debug("Operation error on admin endpoint: {}", message)
        return ResponseEntity.status(status).body(ProblemDetail.forStatusAndDetail(status, message))
    }

    private fun unauthorized(message: String): ResponseEntity<*> {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
            .body(ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, message))
    }
}
