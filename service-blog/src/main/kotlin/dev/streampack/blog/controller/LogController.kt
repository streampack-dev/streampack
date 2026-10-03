/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.controller

import dev.streampack.blog.model.LogDayResponse
import dev.streampack.blog.model.LogEntry
import dev.streampack.blog.model.LogProvenanceListResponse
import dev.streampack.blog.model.LogProvenanceSummary
import dev.streampack.blog.model.LogSearchHit
import dev.streampack.blog.model.LogSearchResponse
import dev.streampack.core.model.Protocol
import dev.streampack.core.model.Provenance
import dev.streampack.core.model.Role
import dev.streampack.core.model.ThrottlePolicy
import dev.streampack.core.model.UserPrincipal
import dev.streampack.core.repository.ChannelControlOptionsRepository
import dev.streampack.core.service.JwtService
import dev.streampack.core.service.MessageLogService
import dev.streampack.core.service.ThrottleService
import dev.streampack.web.controller.UserAwareController
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.servlet.http.HttpServletRequest
import java.time.Duration
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
    private val throttleService: ThrottleService,
    jwtService: JwtService,
) : UserAwareController(jwtService) {

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

    @Operation(
        summary = "Search one channel's logs",
        description =
            "Lines in one channel whose text contains `q`, ignoring case, newest first. Only a " +
                "channel the caller may browse is searched; any other is a 404, as for the day " +
                "logs, so a search can't reveal that a hidden channel exists. `q` is matched as " +
                "written, between $MIN_QUERY and $MAX_QUERY characters.",
    )
    @ApiResponse(
        responseCode = "200",
        description = "A page of matching lines",
        content = [Content(schema = Schema(implementation = LogSearchResponse::class))],
    )
    @ApiResponse(
        responseCode = "400",
        description = "A missing, short or long query, or a bad page or size",
        content = [Content(schema = Schema(implementation = ProblemDetail::class))],
    )
    @ApiResponse(
        responseCode = "404",
        description = "Provenance not found or not authorized",
        content = [Content(schema = Schema(implementation = ProblemDetail::class))],
    )
    @ApiResponse(
        responseCode = "429",
        description = "Too many searches; try again shortly",
        content = [Content(schema = Schema(implementation = ProblemDetail::class))],
    )
    @GetMapping("/search", produces = ["application/json"])
    fun search(
        @RequestParam provenance: String,
        @RequestParam(required = false) q: String?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "50") size: Int,
        httpRequest: HttpServletRequest,
    ): ResponseEntity<*> {
        val user = resolveUser(httpRequest)
        if (provenance !in authorizedChannelProvenances(user)) {
            return notFound("Log provenance not found")
        }
        val query = q?.trim().orEmpty()
        if (query.length < MIN_QUERY || query.length > MAX_QUERY) {
            return badRequest("Search for $MIN_QUERY to $MAX_QUERY characters.")
        }
        if (page < 0) return badRequest("The page can't be negative.")
        if (size < 1 || size > MAX_SIZE) return badRequest("The size is 1 to $MAX_SIZE.")
        if (!searchAllowed(user)) return tooManyRequests("Too many searches; try again shortly.")

        val found = messageLogService.searchMessages(provenance, query, page, size)
        val hits =
            found.content.map {
                LogSearchHit(
                    timestamp = it.timestamp,
                    day = it.timestamp.atZone(ZoneOffset.UTC).toLocalDate().toString(),
                    sender = it.sender,
                    content = it.content,
                    direction = it.direction,
                )
            }
        return ResponseEntity.ok(
            LogSearchResponse(
                provenanceUri = provenance,
                query = query,
                page = page,
                size = size,
                totalCount = found.totalElements,
                totalPages = found.totalPages,
                hits = hits,
            )
        )
    }

    /**
     * A search costs more than a page of a day's logs, so it's limited: a bucket for each signed-in
     * caller, and one shared by everyone signed out. (Behind the proxy every anonymous caller has
     * the same address, so a bucket per address would be the same shared one; per-person anonymous
     * limits need forwarded client addresses, which streampack doesn't read.)
     */
    private fun searchAllowed(user: UserPrincipal?): Boolean =
        if (user != null) {
            throttleService.tryAcquire("log-search:user:${user.id}", PER_USER)
        } else {
            throttleService.tryAcquire("log-search:anonymous", ANONYMOUS)
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

    private fun notFound(message: String): ResponseEntity<*> {
        val pd = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, message)
        pd.title = "Not Found"
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(pd)
    }

    private fun tooManyRequests(message: String): ResponseEntity<*> {
        val pd = ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS, message)
        pd.title = "Too Many Requests"
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(pd)
    }

    private fun badRequest(message: String): ResponseEntity<*> {
        val pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, message)
        pd.title = "Bad Request"
        return ResponseEntity.badRequest().body(pd)
    }

    companion object {
        /** A search's shortest query: a trigram, the least the index can serve. */
        const val MIN_QUERY = 3
        const val MAX_QUERY = 200
        const val MAX_SIZE = 100
        private val PER_USER = ThrottlePolicy(30, Duration.ofMinutes(1))
        private val ANONYMOUS = ThrottlePolicy(60, Duration.ofMinutes(1))
    }
}
