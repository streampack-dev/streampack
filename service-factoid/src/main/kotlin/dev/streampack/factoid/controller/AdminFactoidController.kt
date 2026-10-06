/* Joseph B. Ottinger (C)2026 */
package dev.streampack.factoid.controller

import dev.streampack.core.model.Role
import dev.streampack.core.service.JwtService
import dev.streampack.factoid.service.FactoidGraphReport
import dev.streampack.factoid.service.FactoidGraphReportService
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
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** Admin views of the factoid knowledge base. */
@RestController
@RequestMapping("/admin/factoids")
@Tag(name = "Admin - Factoids")
@SecurityRequirement(name = "bearerAuth")
class AdminFactoidController(
    private val reports: FactoidGraphReportService,
    jwtService: JwtService,
) : UserAwareController(jwtService) {

    @Operation(
        summary = "Where the factoid graph could be better",
        description =
            "Advisory, never edits (#133): factoids naming a parent that lists them without " +
                "linking back; other factoids a factoid names without linking; see-also to " +
                "factoids that don't exist; factoids whose one-line answer leaves parts out, or is " +
                "cut off even so.",
        operationId = "factoidGraphReport",
    )
    @ApiResponse(
        responseCode = "200",
        description = "The report",
        content = [Content(schema = Schema(implementation = FactoidGraphReport::class))],
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
    @GetMapping("/report", produces = ["application/json"])
    fun report(httpRequest: HttpServletRequest): ResponseEntity<*> {
        val user = resolveUser(httpRequest) ?: return unauthorized()
        if (user.role < Role.ADMIN) return forbidden()
        return ResponseEntity.ok(reports.report())
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
