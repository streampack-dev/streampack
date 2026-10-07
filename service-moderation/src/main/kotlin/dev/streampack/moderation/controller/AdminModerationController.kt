/* Joseph B. Ottinger (C)2026 */
package dev.streampack.moderation.controller

import dev.streampack.core.model.Role
import dev.streampack.core.model.UserPrincipal
import dev.streampack.core.service.JwtService
import dev.streampack.moderation.dto.ModerationDismissRequest
import dev.streampack.moderation.dto.ModerationLinesRequest
import dev.streampack.moderation.dto.ModerationPurgeRequest
import dev.streampack.moderation.model.ModerationActionResult
import dev.streampack.moderation.model.ModerationLogDay
import dev.streampack.moderation.model.ReportDetail
import dev.streampack.moderation.model.ReportListResponse
import dev.streampack.moderation.model.ReportStatus
import dev.streampack.moderation.service.ModerationService
import dev.streampack.web.controller.UserAwareController
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.servlet.http.HttpServletRequest
import java.time.LocalDate
import java.util.UUID
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * Abuse reports and what admins do with them (#150), for the Moderation window. Nothing is hidden,
 * purged or dismissed except by a request here, and each is recorded with who made it and when.
 */
@RestController
@RequestMapping("/admin/moderation")
@Tag(name = "Admin - Moderation")
@SecurityRequirement(name = "bearerAuth")
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
class AdminModerationController(
    private val moderation: ModerationService,
    jwtService: JwtService,
) : UserAwareController(jwtService) {

    @Operation(
        summary = "List abuse reports",
        description =
            "Reports from the hourly review, open ones first and newest first within each; or only " +
                "those in `status`. `openCount` is how many are open in all, for a launcher badge.",
        operationId = "listModerationReports",
    )
    @ApiResponse(
        responseCode = "200",
        description = "A page of reports",
        content = [Content(schema = Schema(implementation = ReportListResponse::class))],
    )
    @ApiResponse(
        responseCode = "400",
        description = "A bad status, page or size",
        content = [Content(schema = Schema(implementation = ProblemDetail::class))],
    )
    @GetMapping("/reports", produces = ["application/json"])
    fun list(
        @RequestParam(required = false) status: String?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "25") size: Int,
        httpRequest: HttpServletRequest,
    ): ResponseEntity<*> =
        asAdmin(httpRequest) {
            val wanted = status?.let {
                runCatching { ReportStatus.valueOf(it.uppercase()) }.getOrNull()
                    ?: return@asAdmin badRequest("status is open, dismissed or actioned")
            }
            if (page < 0) return@asAdmin badRequest("The page can't be negative.")
            if (size < 1 || size > MAX_SIZE)
                return@asAdmin badRequest("The size is 1 to $MAX_SIZE.")
            ResponseEntity.ok(moderation.list(wanted, page, size))
        }

    @Operation(
        summary = "One abuse report with its excerpt",
        description =
            "The report, the lines the review read (the person's and those around them, oldest " +
                "first, hidden ones included and marked `hidden`; `flagged` raised a signal, " +
                "`cited` the model pointed at), the ids of excerpt lines since purged, and every " +
                "action taken on it.",
        operationId = "getModerationReport",
    )
    @ApiResponse(
        responseCode = "200",
        description = "The report",
        content = [Content(schema = Schema(implementation = ReportDetail::class))],
    )
    @ApiResponse(
        responseCode = "404",
        description = "No such report",
        content = [Content(schema = Schema(implementation = ProblemDetail::class))],
    )
    @GetMapping("/reports/{id}", produces = ["application/json"])
    fun detail(@PathVariable id: UUID, httpRequest: HttpServletRequest): ResponseEntity<*> =
        asAdmin(httpRequest) { ResponseEntity.ok(moderation.detail(id)) }

    @Operation(
        summary = "Hide lines of a report",
        description =
            "Takes lines of the report's excerpt out of the public log browser, its search and " +
                "everything else that reads the log. They're kept, and an admin can unhide them. " +
                "Marks the report actioned.",
        operationId = "hideModerationReportLines",
    )
    @ApiResponse(
        responseCode = "200",
        description = "The action, as recorded",
        content = [Content(schema = Schema(implementation = ModerationActionResult::class))],
    )
    @ApiResponse(
        responseCode = "400",
        description = "No lines, too many, or lines not in the report's excerpt",
        content = [Content(schema = Schema(implementation = ProblemDetail::class))],
    )
    @ApiResponse(
        responseCode = "404",
        description = "No such report",
        content = [Content(schema = Schema(implementation = ProblemDetail::class))],
    )
    @PostMapping(
        "/reports/{id}/hide",
        produces = ["application/json"],
        consumes = ["application/json"],
    )
    fun hide(
        @PathVariable id: UUID,
        @RequestBody request: ModerationLinesRequest,
        httpRequest: HttpServletRequest,
    ): ResponseEntity<*> =
        asAdmin(httpRequest) { admin ->
            ResponseEntity.ok(moderation.hide(id, request.lineIds, admin, request.note))
        }

    @Operation(
        summary = "Unhide lines of a report",
        description = "Puts hidden lines of the report's excerpt back. The report's status stays.",
        operationId = "unhideModerationReportLines",
    )
    @ApiResponse(
        responseCode = "200",
        description = "The action, as recorded",
        content = [Content(schema = Schema(implementation = ModerationActionResult::class))],
    )
    @ApiResponse(
        responseCode = "400",
        description = "No lines, too many, or lines not in the report's excerpt",
        content = [Content(schema = Schema(implementation = ProblemDetail::class))],
    )
    @ApiResponse(
        responseCode = "404",
        description = "No such report",
        content = [Content(schema = Schema(implementation = ProblemDetail::class))],
    )
    @PostMapping(
        "/reports/{id}/unhide",
        produces = ["application/json"],
        consumes = ["application/json"],
    )
    fun unhide(
        @PathVariable id: UUID,
        @RequestBody request: ModerationLinesRequest,
        httpRequest: HttpServletRequest,
    ): ResponseEntity<*> =
        asAdmin(httpRequest) { admin ->
            ResponseEntity.ok(moderation.unhide(id, request.lineIds, admin, request.note))
        }

    @Operation(
        summary = "Purge lines of a report",
        description =
            "Deletes lines of the report's excerpt for good, for what must not be kept. It can't " +
                "be undone, so `confirm` must be true. The ids stay on the report and the action. " +
                "Marks the report actioned.",
        operationId = "purgeModerationReportLines",
    )
    @ApiResponse(
        responseCode = "200",
        description = "The action, as recorded",
        content = [Content(schema = Schema(implementation = ModerationActionResult::class))],
    )
    @ApiResponse(
        responseCode = "400",
        description = "Not confirmed, no lines, too many, or lines not in the report's excerpt",
        content = [Content(schema = Schema(implementation = ProblemDetail::class))],
    )
    @ApiResponse(
        responseCode = "404",
        description = "No such report",
        content = [Content(schema = Schema(implementation = ProblemDetail::class))],
    )
    @PostMapping(
        "/reports/{id}/purge",
        produces = ["application/json"],
        consumes = ["application/json"],
    )
    fun purge(
        @PathVariable id: UUID,
        @RequestBody request: ModerationPurgeRequest,
        httpRequest: HttpServletRequest,
    ): ResponseEntity<*> =
        asAdmin(httpRequest) { admin ->
            if (!request.confirm) {
                return@asAdmin badRequest("Purging can't be undone: send confirm=true.")
            }
            ResponseEntity.ok(moderation.purge(id, request.lineIds, admin, request.note))
        }

    @Operation(
        summary = "Dismiss a report",
        description = "Closes an open report as not abuse. The report stays readable.",
        operationId = "dismissModerationReport",
    )
    @ApiResponse(
        responseCode = "200",
        description = "The action, as recorded",
        content = [Content(schema = Schema(implementation = ModerationActionResult::class))],
    )
    @ApiResponse(
        responseCode = "400",
        description = "The report isn't open",
        content = [Content(schema = Schema(implementation = ProblemDetail::class))],
    )
    @ApiResponse(
        responseCode = "404",
        description = "No such report",
        content = [Content(schema = Schema(implementation = ProblemDetail::class))],
    )
    @PostMapping("/reports/{id}/dismiss", produces = ["application/json"])
    fun dismiss(
        @PathVariable id: UUID,
        @RequestBody(required = false) request: ModerationDismissRequest?,
        httpRequest: HttpServletRequest,
    ): ResponseEntity<*> =
        asAdmin(httpRequest) { admin ->
            ResponseEntity.ok(moderation.dismiss(id, admin, request?.note))
        }

    @Operation(
        summary = "A day of a channel's log, hidden lines included",
        description =
            "One UTC day of a channel's log as an admin reviews it: every line with its id, " +
                "hidden ones included and marked `hidden`, oldest first, at most 5000. Direct " +
                "conversations are never returned. `day` defaults to today.",
        operationId = "getModerationLogDay",
    )
    @ApiResponse(
        responseCode = "200",
        description = "The day's lines",
        content = [Content(schema = Schema(implementation = ModerationLogDay::class))],
    )
    @ApiResponse(
        responseCode = "400",
        description = "A day that isn't YYYY-MM-DD",
        content = [Content(schema = Schema(implementation = ProblemDetail::class))],
    )
    @GetMapping("/logs", produces = ["application/json"])
    fun logDay(
        @RequestParam provenance: String,
        @RequestParam(required = false) day: String?,
        httpRequest: HttpServletRequest,
    ): ResponseEntity<*> =
        asAdmin(httpRequest) {
            val date =
                if (day.isNullOrBlank()) LocalDate.now(java.time.ZoneOffset.UTC)
                else
                    runCatching { LocalDate.parse(day) }.getOrNull()
                        ?: return@asAdmin badRequest("Invalid day format. Use YYYY-MM-DD")
            ResponseEntity.ok(moderation.logDay(provenance, date))
        }

    @Operation(
        summary = "Hide log lines",
        description =
            "Hides lines found in a log day rather than a report: kept, and out of public view.",
        operationId = "hideModerationLines",
    )
    @ApiResponse(
        responseCode = "200",
        description = "The action, as recorded",
        content = [Content(schema = Schema(implementation = ModerationActionResult::class))],
    )
    @ApiResponse(
        responseCode = "400",
        description = "No lines, too many, or lines that don't exist",
        content = [Content(schema = Schema(implementation = ProblemDetail::class))],
    )
    @PostMapping("/lines/hide", produces = ["application/json"], consumes = ["application/json"])
    fun hideLines(
        @RequestBody request: ModerationLinesRequest,
        httpRequest: HttpServletRequest,
    ): ResponseEntity<*> =
        asAdmin(httpRequest) { admin ->
            ResponseEntity.ok(moderation.setHidden(request.lineIds, true, admin, request.note))
        }

    @Operation(
        summary = "Unhide log lines",
        description = "Puts hidden lines back in public view.",
        operationId = "unhideModerationLines",
    )
    @ApiResponse(
        responseCode = "200",
        description = "The action, as recorded",
        content = [Content(schema = Schema(implementation = ModerationActionResult::class))],
    )
    @ApiResponse(
        responseCode = "400",
        description = "No lines, too many, or lines that don't exist",
        content = [Content(schema = Schema(implementation = ProblemDetail::class))],
    )
    @PostMapping("/lines/unhide", produces = ["application/json"], consumes = ["application/json"])
    fun unhideLines(
        @RequestBody request: ModerationLinesRequest,
        httpRequest: HttpServletRequest,
    ): ResponseEntity<*> =
        asAdmin(httpRequest) { admin ->
            ResponseEntity.ok(moderation.setHidden(request.lineIds, false, admin, request.note))
        }

    /**
     * Runs [action] for an admin: 401 for no one, 403 for anyone else, 404 for a missing report,
     * and 400 for a request the service refuses.
     */
    private fun asAdmin(
        httpRequest: HttpServletRequest,
        action: (UserPrincipal) -> ResponseEntity<*>,
    ): ResponseEntity<*> {
        val user =
            resolveUser(httpRequest)
                ?: return problem(HttpStatus.UNAUTHORIZED, "Authentication required")
        if (user.role < Role.ADMIN) {
            return problem(HttpStatus.FORBIDDEN, "Insufficient privileges: requires ADMIN")
        }
        return try {
            action(user)
        } catch (e: ModerationService.ReportNotFoundException) {
            problem(HttpStatus.NOT_FOUND, "Moderation report not found")
        } catch (e: IllegalArgumentException) {
            badRequest(e.message ?: "Bad request")
        }
    }

    private fun badRequest(message: String) = problem(HttpStatus.BAD_REQUEST, message)

    private fun problem(status: HttpStatus, message: String): ResponseEntity<ProblemDetail> =
        ResponseEntity.status(status).body(ProblemDetail.forStatusAndDetail(status, message))

    companion object {
        const val MAX_SIZE = 100
    }
}
