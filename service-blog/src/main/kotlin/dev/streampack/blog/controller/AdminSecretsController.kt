/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.controller

import dev.streampack.blog.config.BlogProperties
import dev.streampack.blog.model.SecretRequirementsResponse
import dev.streampack.core.integration.EventGateway
import dev.streampack.core.model.OperationResult
import dev.streampack.core.model.Protocol
import dev.streampack.core.model.Provenance
import dev.streampack.core.model.SecretRequirement
import dev.streampack.core.model.SecretRequirementsRequest
import dev.streampack.core.service.JwtService
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
import org.springframework.messaging.support.MessageBuilder
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * The environment variables the server needs set before its next start (#209): credentials given at
 * the console, stored in the database until their variables exist, and references whose variables
 * aren't set. A setup tool reads it to know what to ask for; it never returns a value.
 */
@RestController
@RequestMapping("/admin/secrets")
@Tag(name = "Admin - Secrets")
@SecurityRequirement(name = "bearerAuth")
class AdminSecretsController(
    private val eventGateway: EventGateway,
    jwtService: JwtService,
    blogProperties: BlogProperties,
) : UserAwareController(jwtService) {
    private val serviceId = blogProperties.serviceId

    @Operation(summary = "List the environment variables the next start needs")
    @ApiResponse(
        responseCode = "200",
        description = "The variables, by name, with what each is for; never their values",
        content = [Content(schema = Schema(implementation = SecretRequirementsResponse::class))],
    )
    @ApiResponse(
        responseCode = "401",
        description = "Not authenticated",
        content = [Content(schema = Schema(implementation = ProblemDetail::class))],
    )
    @ApiResponse(
        responseCode = "403",
        description = "Not a super-admin",
        content = [Content(schema = Schema(implementation = ProblemDetail::class))],
    )
    @GetMapping(produces = ["application/json"])
    fun required(httpRequest: HttpServletRequest): ResponseEntity<*> {
        val user =
            resolveUser(httpRequest) ?: return problem(HttpStatus.UNAUTHORIZED, "Not authenticated")
        val message =
            MessageBuilder.withPayload(SecretRequirementsRequest)
                .setHeader(
                    Provenance.HEADER,
                    Provenance(
                        protocol = Protocol.HTTP,
                        serviceId = serviceId,
                        replyTo = "admin/secrets",
                        user = user,
                    ),
                )
                .build()
        return when (val result = eventGateway.process(message)) {
            is OperationResult.Success -> {
                @Suppress("UNCHECKED_CAST") val required = result.payload as List<SecretRequirement>
                ResponseEntity.ok(SecretRequirementsResponse(required))
            }
            is OperationResult.Error ->
                if (result.message.contains("Insufficient privileges")) {
                    problem(HttpStatus.FORBIDDEN, result.message)
                } else {
                    problem(HttpStatus.BAD_REQUEST, result.message)
                }
            is OperationResult.NotHandled ->
                problem(HttpStatus.INTERNAL_SERVER_ERROR, "Unhandled request")
        }
    }

    private fun problem(status: HttpStatus, detail: String): ResponseEntity<*> =
        ResponseEntity.status(status).body(ProblemDetail.forStatusAndDetail(status, detail))
}
