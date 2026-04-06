/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.controller

import dev.streampack.blog.model.LogDayResponse
import dev.streampack.blog.model.LogEntry
import dev.streampack.blog.model.LogProvenanceListResponse
import dev.streampack.blog.model.LogProvenanceSummary
import dev.streampack.blog.service.CookieService
import dev.streampack.core.model.Protocol
import dev.streampack.core.model.Provenance
import dev.streampack.core.model.Role
import dev.streampack.core.model.UserPrincipal
import dev.streampack.core.repository.ChannelControlOptionsRepository
import dev.streampack.core.service.JwtService
import dev.streampack.core.service.MessageLogService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.servlet.http.HttpServletRequest
import java.time.LocalDate
import java.time.ZoneOffset
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/** Lists and retrieves channel logs with strict server-side provenance authorization filtering. */
@RestController
@RequestMapping("/logs")
@Tag(name = "Logs")
class LogController(
    private val channelOptionsRepository: ChannelControlOptionsRepository,
    private val messageLogService: MessageLogService,
    private val jwtService: JwtService,
) {

    @Operation(summary = "List browseable log provenances for the current user")
    @ApiResponse(
        responseCode = "200",
        description = "Filtered provenance list",
        content = [Content(schema = Schema(implementation = LogProvenanceListResponse::class))],
    )
    @GetMapping("/provenances", produces = ["application/json"])
    fun listProvenances(httpRequest: HttpServletRequest): ResponseEntity<*> {
        val user = resolveUser(httpRequest)
        val visible = authorizedChannelProvenances(user)

        val items =
            visible
                .mapNotNull { provenanceUri ->
                    val parsed =
                        runCatching { Provenance.decode(provenanceUri) }.getOrNull()
                            ?: return@mapNotNull null
                    val latest = messageLogService.findLatestMessage(provenanceUri)
                    LogProvenanceSummary(
                        provenanceUri = provenanceUri,
                        protocol = parsed.protocol.name.lowercase(),
                        serviceId = parsed.serviceId,
                        replyTo = parsed.replyTo,
                        latestTimestamp = latest?.timestamp,
                        latestSender = latest?.sender,
                        latestContentPreview = latest?.content?.replace("\n", " ")?.take(140),
                    )
                }
                .sortedByDescending { it.latestTimestamp ?: java.time.Instant.EPOCH }

        return ResponseEntity.ok(LogProvenanceListResponse(items))
    }

    @Operation(summary = "Get one day of logs for a provenance")
    @ApiResponse(
        responseCode = "200",
        description = "Log entries for day",
        content = [Content(schema = Schema(implementation = LogDayResponse::class))],
    )
    @ApiResponse(
        responseCode = "404",
        description = "Provenance not found or not authorized",
        content = [Content(schema = Schema(implementation = ProblemDetail::class))],
    )
    @GetMapping(produces = ["application/json"])
    fun getDayLogs(
        @RequestParam provenance: String,
        @RequestParam(required = false) day: String?,
        httpRequest: HttpServletRequest,
    ): ResponseEntity<*> {
        val user = resolveUser(httpRequest)
        val authorized = authorizedChannelProvenances(user)
        if (provenance !in authorized) {
            return notFound("Log provenance not found")
        }

        val targetDay =
            if (day.isNullOrBlank()) {
                LocalDate.now(ZoneOffset.UTC)
            } else {
                runCatching { LocalDate.parse(day) }
                    .getOrElse {
                        return badRequest("Invalid day format. Use YYYY-MM-DD")
                    }
            }

        val start = targetDay.atStartOfDay().toInstant(ZoneOffset.UTC)
        val end = targetDay.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC)
        val entries =
            messageLogService.findMessages(provenance, start, end, 5000).map {
                LogEntry(
                    timestamp = it.timestamp,
                    sender = it.sender,
                    content = it.content,
                    direction = it.direction,
                )
            }
        return ResponseEntity.ok(LogDayResponse(provenance, targetDay.toString(), entries))
    }

    private fun authorizedChannelProvenances(user: UserPrincipal?): Set<String> {
        val isAdmin = user?.role == Role.ADMIN || user?.role == Role.SUPER_ADMIN
        val options =
            if (isAdmin) {
                channelOptionsRepository.findBrowsableChannelsForAdmin()
            } else {
                channelOptionsRepository.findBrowsableChannelsForUser()
            }
        return options.map { it.provenanceUri }.filter { isChannelProvenance(it) }.toSet()
    }

    private fun isChannelProvenance(uri: String): Boolean {
        val provenance = runCatching { Provenance.decode(uri) }.getOrNull() ?: return false
        return when (provenance.protocol) {
            Protocol.IRC -> provenance.replyTo.startsWith("#")
            Protocol.DISCORD,
            Protocol.SLACK,
            Protocol.MATTERMOST -> true
            else -> false
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

    private fun notFound(message: String): ResponseEntity<*> {
        val pd = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, message)
        pd.title = "Not Found"
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(pd)
    }

    private fun badRequest(message: String): ResponseEntity<*> {
        val pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, message)
        pd.title = "Bad Request"
        return ResponseEntity.badRequest().body(pd)
    }
}
