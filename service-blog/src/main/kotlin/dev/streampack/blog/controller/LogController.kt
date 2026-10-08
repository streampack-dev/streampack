/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.controller

import dev.streampack.blog.model.LogChannelResponse
import dev.streampack.blog.model.LogDayResponse
import dev.streampack.blog.model.LogEntry
import dev.streampack.blog.model.LogProvenanceListResponse
import dev.streampack.blog.model.LogProvenanceSummary
import dev.streampack.blog.model.LogSearchHit
import dev.streampack.blog.model.LogSearchResponse
import dev.streampack.blog.service.LogChannelPaths
import dev.streampack.core.model.MessageKind
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
import io.swagger.v3.oas.annotations.Parameter
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
import org.springframework.web.bind.annotation.PathVariable
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
    private val channelPaths: LogChannelPaths,
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
                        path = channelPaths.path(provenanceUri, visible),
                    )
                }
                .sortedByDescending { it.latestTimestamp ?: java.time.Instant.EPOCH }

        return ResponseEntity.ok(LogProvenanceListResponse(items))
    }

    @Operation(
        summary = "Find a channel by its readable address",
        description =
            "The channel `/logs/{protocol}/{service}/{name}` names, among those the caller may " +
                "browse: `irc/libera/primate`. An IRC name is matched as written, then with `#`, " +
                "then with `##`; other protocols' channels by their names, or their ids. Its " +
                "`provenanceUri` is what the day view and search take. Any other name is a 404, " +
                "as a hidden channel is, so an address can't reveal one.",
    )
    @ApiResponse(
        responseCode = "200",
        description = "The channel",
        content = [Content(schema = Schema(implementation = LogChannelResponse::class))],
    )
    @ApiResponse(
        responseCode = "404",
        description = "No such channel, or not one the caller may browse",
        content = [Content(schema = Schema(implementation = ProblemDetail::class))],
    )
    @GetMapping("/channels/{protocol}/{service}/{name}", produces = ["application/json"])
    fun channel(
        @PathVariable protocol: String,
        @PathVariable service: String,
        @PathVariable name: String,
        httpRequest: HttpServletRequest,
    ): ResponseEntity<*> {
        val visible = authorizedChannelProvenances(resolveUser(httpRequest))
        val uri =
            channelPaths.resolve(protocol, service, name, visible)
                ?: return notFound("Log channel not found")
        val path = channelPaths.path(uri, visible) ?: return notFound("Log channel not found")
        return ResponseEntity.ok(LogChannelResponse(uri, path))
    }

    @Operation(
        summary = "Get one day of logs for a provenance",
        description =
            "Every line of one UTC day, oldest first, each with its `kind`: `MESSAGE`, `JOIN`, " +
                "`PART`, `QUIT`, `NICK` or `TOPIC`. $EVENTS_DESCRIPTION",
    )
    @ApiResponse(
        responseCode = "200",
        description = "Log entries for day",
        content = [Content(schema = Schema(implementation = LogDayResponse::class))],
    )
    @ApiResponse(
        responseCode = "400",
        description = "A bad day, or an unknown `events` or `kinds` value",
        content = [Content(schema = Schema(implementation = ProblemDetail::class))],
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
        @Parameter(description = EVENTS_PARAM) @RequestParam(required = false) events: String?,
        @Parameter(description = KINDS_PARAM) @RequestParam(required = false) kinds: String?,
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

        val selected =
            selectKinds(events, kinds).getOrElse {
                return badRequest(it.message ?: "Unknown events or kinds")
            }

        val start = targetDay.atStartOfDay().toInstant(ZoneOffset.UTC)
        val end = targetDay.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC)
        val entries =
            messageLogService.findMessages(provenance, start, end, 5000, selected).map {
                LogEntry(
                    timestamp = it.timestamp,
                    sender = it.sender,
                    content = it.content,
                    direction = it.direction,
                    kind = it.kind,
                )
            }
        return ResponseEntity.ok(LogDayResponse(provenance, targetDay.toString(), entries))
    }

    @Operation(
        summary = "Search one channel's logs",
        description =
            "Lines in one channel whose text contains `q`, ignoring case, and written by " +
                "`sender` (a nick, ignoring case), newest first. Give `q`, `sender`, or both: " +
                "`sender` alone lists everything that person said there. Only a channel the " +
                "caller may browse is searched; any other is a 404, as for the day logs, so a " +
                "search can't reveal that a hidden channel exists. `q` is matched as written, " +
                "between $MIN_QUERY and $MAX_QUERY characters. Each hit carries its `kind`. " +
                EVENTS_DESCRIPTION,
    )
    @ApiResponse(
        responseCode = "200",
        description = "A page of matching lines",
        content = [Content(schema = Schema(implementation = LogSearchResponse::class))],
    )
    @ApiResponse(
        responseCode = "400",
        description =
            "Neither a query nor a sender, a short or long query, a bad page or size, or an " +
                "unknown `events` or `kinds` value",
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
        @RequestParam(required = false) sender: String?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "50") size: Int,
        @Parameter(description = EVENTS_PARAM) @RequestParam(required = false) events: String?,
        @Parameter(description = KINDS_PARAM) @RequestParam(required = false) kinds: String?,
        httpRequest: HttpServletRequest,
    ): ResponseEntity<*> {
        val user = resolveUser(httpRequest)
        if (provenance !in authorizedChannelProvenances(user)) {
            return notFound("Log provenance not found")
        }
        val query = q?.trim()?.takeIf { it.isNotEmpty() }
        val nick = sender?.trim()?.takeIf { it.isNotEmpty() }
        if (query == null && nick == null)
            return badRequest("Search for some text, a sender, or both.")
        if (query != null && (query.length < MIN_QUERY || query.length > MAX_QUERY)) {
            return badRequest("Search for $MIN_QUERY to $MAX_QUERY characters.")
        }
        if (nick != null && nick.length > MAX_SENDER)
            return badRequest("A sender is at most $MAX_SENDER characters.")
        if (page < 0) return badRequest("The page can't be negative.")
        if (size < 1 || size > MAX_SIZE) return badRequest("The size is 1 to $MAX_SIZE.")
        val selected =
            selectKinds(events, kinds).getOrElse {
                return badRequest(it.message ?: "Unknown events or kinds")
            }
        if (!searchAllowed(user)) return tooManyRequests("Too many searches; try again shortly.")

        val found = messageLogService.searchMessages(provenance, query, nick, page, size, selected)
        val hits =
            found.content.map {
                LogSearchHit(
                    timestamp = it.timestamp,
                    day = it.timestamp.atZone(ZoneOffset.UTC).toLocalDate().toString(),
                    sender = it.sender,
                    content = it.content,
                    direction = it.direction,
                    kind = it.kind,
                )
            }
        return ResponseEntity.ok(
            LogSearchResponse(
                provenanceUri = provenance,
                query = query,
                sender = nick,
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
        private const val EVENTS_PARAM =
            "`all` (the default) for every line, `none` for what was said only, without joins, " +
                "parts, quits, nick changes and topics"
        private const val KINDS_PARAM =
            "Only these kinds, comma-separated: `MESSAGE`, `JOIN`, `PART`, `QUIT`, `NICK`, " +
                "`TOPIC` (any case). With `events`, a line must pass both."
        private const val EVENTS_DESCRIPTION =
            "`events=none` leaves out channel events (joins, parts, quits, nick changes and " +
                "topics); `kinds` picks the kinds to include. Without either, every line is " +
                "returned."

        /**
         * The kinds `events` and `kinds` select (#174): every kind when neither is given; with
         * both, those both allow. An unknown value of either is a failure, for a 400.
         */
        fun selectKinds(events: String?, kinds: String?): Result<Set<MessageKind>> {
            val byEvents =
                when (events?.trim()?.lowercase()) {
                    null,
                    "",
                    "all" -> MessageKind.entries.toSet()
                    "none" -> setOf(MessageKind.MESSAGE)
                    else ->
                        return Result.failure(
                            IllegalArgumentException("events is all or none, not '$events'.")
                        )
                }
            val names = kinds?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }
            if (names.isNullOrEmpty()) return Result.success(byEvents)
            val byKinds = names.map { name ->
                MessageKind.entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
                    ?: return Result.failure(
                        IllegalArgumentException(
                            "Unknown kind '$name'; the kinds are " +
                                MessageKind.entries.joinToString(", ") +
                                "."
                        )
                    )
            }
            return Result.success(byEvents intersect byKinds.toSet())
        }

        /** A search's shortest query: a trigram, the least the index can serve. */
        const val MIN_QUERY = 3
        const val MAX_QUERY = 200
        const val MAX_SIZE = 100
        /** A sender's longest, as the log holds it. */
        const val MAX_SENDER = 255
        private val PER_USER = ThrottlePolicy(30, Duration.ofMinutes(1))
        private val ANONYMOUS = ThrottlePolicy(60, Duration.ofMinutes(1))
    }
}
