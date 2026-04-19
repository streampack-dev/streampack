/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.controller

import dev.streampack.blog.config.BlogProperties
import dev.streampack.blog.model.DeleteAccountRequest
import dev.streampack.blog.model.ExportUserDataRequest
import dev.streampack.blog.model.LoginResponse
import dev.streampack.blog.model.OtpRequest
import dev.streampack.blog.model.OtpVerifyRequest
import dev.streampack.blog.service.CookieService
import dev.streampack.core.integration.EventGateway
import dev.streampack.core.model.EditProfileRequest
import dev.streampack.core.model.OperationResult
import dev.streampack.core.model.Protocol
import dev.streampack.core.model.Provenance
import dev.streampack.core.model.UserPrincipal
import dev.streampack.core.repository.UserRepository
import dev.streampack.core.service.JwtService
import dev.streampack.core.service.RefreshTokenService
import dev.streampack.web.controller.UserAwareController
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.messaging.support.MessageBuilder
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** HTTP adapter for authentication and account management endpoints */
@RestController
@RequestMapping("/auth")
@Tag(name = "Authentication")
class AuthController(
    private val eventGateway: EventGateway,
    private val jwtService: JwtService,
    private val refreshTokenService: RefreshTokenService,
    private val cookieService: CookieService,
    private val userRepository: UserRepository,
    blogProperties: BlogProperties,
) : UserAwareController(jwtService) {
    private val serviceId = blogProperties.serviceId
    private val logger = LoggerFactory.getLogger(AuthController::class.java)

    @Operation(summary = "Request a one-time sign-in code")
    @ApiResponse(responseCode = "202", description = "Code sent if email is valid")
    @PostMapping("/otp/request", produces = ["application/json"], consumes = ["application/json"])
    fun requestOtp(@RequestBody request: OtpRequest): ResponseEntity<*> {
        return dispatch(request, "auth/otp/request", successStatus = HttpStatus.ACCEPTED) { result
            ->
            mapError(result, HttpStatus.BAD_REQUEST)
        }
    }

    @Operation(summary = "Verify a one-time sign-in code")
    @ApiResponse(
        responseCode = "200",
        description = "Authentication successful",
        content = [Content(schema = Schema(implementation = LoginResponse::class))],
    )
    @ApiResponse(
        responseCode = "401",
        description = "Invalid or expired code",
        content = [Content(schema = Schema(implementation = ProblemDetail::class))],
    )
    @PostMapping("/otp/verify", produces = ["application/json"], consumes = ["application/json"])
    fun verifyOtp(
        @RequestBody request: OtpVerifyRequest,
        httpResponse: HttpServletResponse,
    ): ResponseEntity<*> {
        val entity =
            dispatch(request, "auth/otp/verify") { result ->
                mapError(result, HttpStatus.UNAUTHORIZED)
            }
        if (entity.statusCode == HttpStatus.OK && entity.body is LoginResponse) {
            val loginResponse = entity.body as LoginResponse
            httpResponse.addHeader(
                HttpHeaders.SET_COOKIE,
                cookieService.createAccessTokenCookie(loginResponse.token).toString(),
            )
            loginResponse.refreshToken?.let {
                httpResponse.addHeader(
                    HttpHeaders.SET_COOKIE,
                    cookieService.createRefreshTokenCookie(it).toString(),
                )
            }
        }
        return entity
    }

    @Operation(summary = "Log out the current session")
    @ApiResponse(responseCode = "204", description = "Logged out")
    @PostMapping("/logout")
    fun logout(
        httpRequest: HttpServletRequest,
        httpResponse: HttpServletResponse,
    ): ResponseEntity<Void> {
        val user = resolveUser(httpRequest)
        if (user != null) {
            refreshTokenService.revokeAllForUser(user.id)
        }
        httpResponse.addHeader(
            HttpHeaders.SET_COOKIE,
            cookieService.clearAccessTokenCookie().toString(),
        )
        httpResponse.addHeader(
            HttpHeaders.SET_COOKIE,
            cookieService.clearRefreshTokenCookie().toString(),
        )
        return ResponseEntity.noContent().build()
    }

    @Operation(summary = "Refresh an expired JWT token using a refresh token cookie")
    @ApiResponse(
        responseCode = "200",
        description = "Token refreshed",
        content = [Content(schema = Schema(implementation = LoginResponse::class))],
    )
    @ApiResponse(
        responseCode = "401",
        description = "Invalid or expired refresh token",
        content = [Content(schema = Schema(implementation = ProblemDetail::class))],
    )
    @PostMapping("/refresh", produces = ["application/json"])
    fun refresh(
        httpRequest: HttpServletRequest,
        httpResponse: HttpServletResponse,
    ): ResponseEntity<*> {
        val rawRefreshToken =
            extractRefreshToken(httpRequest) ?: return unauthorized("Missing refresh token")
        val (userId, newRawToken) =
            refreshTokenService.rotateToken(rawRefreshToken)
                ?: return unauthorized("Invalid or expired refresh token")
        val user =
            userRepository.findActiveById(userId)
                ?: return unauthorized("Invalid or expired refresh token")
        val principal = user.toUserPrincipal()
        val newJwt = jwtService.generateToken(principal)
        httpResponse.addHeader(
            HttpHeaders.SET_COOKIE,
            cookieService.createAccessTokenCookie(newJwt).toString(),
        )
        httpResponse.addHeader(
            HttpHeaders.SET_COOKIE,
            cookieService.createRefreshTokenCookie(newRawToken).toString(),
        )
        return ResponseEntity.ok(LoginResponse(newJwt, principal))
    }

    @Operation(summary = "Erase the authenticated user's account")
    @SecurityRequirement(name = "bearerAuth")
    @DeleteMapping("/account", produces = ["application/json"], consumes = ["application/json"])
    fun deleteAccount(
        @RequestBody request: DeleteAccountRequest,
        httpRequest: HttpServletRequest,
    ): ResponseEntity<*> {
        val user = resolveUser(httpRequest) ?: return unauthorized("Not authenticated")

        return dispatch(request, "auth/account", user) { result ->
            mapError(result) { message ->
                when {
                    message.contains("Not authenticated") -> HttpStatus.UNAUTHORIZED
                    message.contains("Insufficient privileges") -> HttpStatus.FORBIDDEN
                    else -> HttpStatus.BAD_REQUEST
                }
            }
        }
    }

    @Operation(summary = "Export the authenticated user's data")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponse(responseCode = "200", description = "User data export")
    @GetMapping("/export", produces = ["application/json"])
    fun exportUserData(httpRequest: HttpServletRequest): ResponseEntity<*> {
        val user = resolveUser(httpRequest) ?: return unauthorized("Not authenticated")

        return dispatch(ExportUserDataRequest(), "auth/export", user) { result ->
            mapError(result, HttpStatus.BAD_REQUEST)
        }
    }

    @Operation(summary = "Validate the current session token")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponse(
        responseCode = "200",
        description = "Authenticated user principal",
        content = [Content(schema = Schema(implementation = UserPrincipal::class))],
    )
    @ApiResponse(
        responseCode = "401",
        description = "Not authenticated",
        content = [Content(schema = Schema(implementation = ProblemDetail::class))],
    )
    @GetMapping("/session", produces = ["application/json"])
    fun session(httpRequest: HttpServletRequest): ResponseEntity<*> {
        val user = resolveUser(httpRequest) ?: return unauthorized("Not authenticated")
        return ResponseEntity.ok(user)
    }

    @Operation(summary = "Update the authenticated user's profile")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponse(
        responseCode = "200",
        description = "Updated user principal",
        content = [Content(schema = Schema(implementation = UserPrincipal::class))],
    )
    @PutMapping("/profile", produces = ["application/json"], consumes = ["application/json"])
    fun editProfile(
        @RequestBody request: EditProfileRequest,
        httpRequest: HttpServletRequest,
    ): ResponseEntity<*> {
        val user = resolveUser(httpRequest) ?: return unauthorized("Not authenticated")

        return dispatch(request, "auth/profile", user) { result ->
            mapError(result, HttpStatus.BAD_REQUEST)
        }
    }

    /** Extracts the refresh token from the cookie */
    private fun extractRefreshToken(request: HttpServletRequest): String? {
        return request.cookies?.find { it.name == CookieService.REFRESH_TOKEN_COOKIE }?.value
    }

    /** Sends a payload through the event system and maps the result to an HTTP response */
    private fun dispatch(
        payload: Any,
        replyTo: String,
        user: UserPrincipal? = null,
        successStatus: HttpStatus = HttpStatus.OK,
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
            is OperationResult.Success -> ResponseEntity.status(successStatus).body(result.payload)
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

    /** Maps an error result to a ProblemDetail response with a fixed status */
    private fun mapError(result: OperationResult, status: HttpStatus): ResponseEntity<*> {
        val message = (result as OperationResult.Error).message
        logger.debug("Operation error on auth endpoint: {}", message)
        return ResponseEntity.status(status).body(ProblemDetail.forStatusAndDetail(status, message))
    }

    /**
     * Maps an error result to a ProblemDetail response with status determined by message content
     */
    private fun mapError(
        result: OperationResult,
        statusMapper: (String) -> HttpStatus,
    ): ResponseEntity<*> {
        val message = (result as OperationResult.Error).message
        val status = statusMapper(message)
        logger.debug("Operation error on auth endpoint: {}", message)
        return ResponseEntity.status(status).body(ProblemDetail.forStatusAndDetail(status, message))
    }

    private fun unauthorized(message: String): ResponseEntity<*> {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
            .body(ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, message))
    }
}
