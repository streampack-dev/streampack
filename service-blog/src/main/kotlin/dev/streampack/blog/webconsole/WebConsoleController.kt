/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.webconsole

import dev.streampack.core.integration.EventGateway
import dev.streampack.core.json.JacksonMappers
import dev.streampack.core.model.Protocol
import dev.streampack.core.model.Provenance
import dev.streampack.core.model.ThrottlePolicy
import dev.streampack.core.service.JwtService
import dev.streampack.core.service.ThrottleService
import dev.streampack.web.controller.UserAwareController
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.enums.ParameterIn
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.servlet.http.HttpServletRequest
import java.net.URI
import java.time.Duration
import java.util.UUID
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.messaging.support.MessageBuilder
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter

/**
 * The admin web console (#115): streampack's text commands, typed in a browser, answered over a
 * server-sent event stream. A command goes in as at the stdin console, addressed and without a
 * prefix, from the admin's own console destination, `webconsole://web/users/<id>`, so all their
 * windows share one context. Its output, and anything else addressed to that destination, comes
 * back on every stream they have open.
 *
 * Both endpoints are for active ADMIN and SUPER_ADMIN accounts, checked against the user store on
 * every request (and on every heartbeat, for streams); what each command may do is still decided by
 * its operation, against the account as it stands when the command runs.
 */
@RestController
@RequestMapping("/admin/console")
@Tag(name = "Web console")
class WebConsoleController(
    jwtService: JwtService,
    private val access: WebConsoleAccess,
    private val streams: WebConsoleStreams,
    private val eventGateway: EventGateway,
    private val throttleService: ThrottleService,
    private val properties: WebConsoleProperties,
    @Value("\${CORS_ORIGINS:http://localhost:3000,http://localhost:3003,https://bytecode.news}")
    corsOrigins: String,
) : UserAwareController(jwtService) {
    private val mapper = JacksonMappers.standard()
    private val trustedOrigins =
        corsOrigins.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    private val commands = ThrottlePolicy(properties.commandsPerMinute, Duration.ofMinutes(1))
    private val opens = ThrottlePolicy(properties.streamOpensPerMinute, Duration.ofMinutes(1))

    @Operation(
        summary = "Run a console command",
        description =
            "Submits one line, as typed at the stdin console (`aho-corasick`, `foo is bar`, " +
                "`calc 2+2`), and returns at once with its correlation id: 202 means it was " +
                "accepted, not that it succeeded. Its output arrives on GET /admin/console/stream " +
                "as `result` events carrying that id, possibly before this answer does; a command " +
                "may produce none, one or several. Open the stream first; output with no stream " +
                "open is dropped. Requires an active admin, a JSON body, and the header " +
                "`X-Web-Console: 1`; a request authenticated by the access-token cookie must also " +
                "come from a trusted Origin (or Referer). Not retried automatically: a lost answer " +
                "doesn't mean the command didn't run.",
    )
    @Parameter(
        name = "X-Web-Console",
        `in` = ParameterIn.HEADER,
        required = true,
        schema = Schema(type = "string", allowableValues = ["1"]),
    )
    @ApiResponse(
        responseCode = "202",
        description = "Accepted for processing",
        content = [Content(schema = Schema(implementation = WebConsoleAccepted::class))],
    )
    @ApiResponse(
        responseCode = "400",
        description = "No line, or one with newlines or too long",
        content = [Content(schema = Schema(implementation = ProblemDetail::class))],
    )
    @ApiResponse(
        responseCode = "401",
        description = "Not signed in",
        content = [Content(schema = Schema(implementation = ProblemDetail::class))],
    )
    @ApiResponse(
        responseCode = "403",
        description = "Not an active admin, or the request's source isn't trusted",
        content = [Content(schema = Schema(implementation = ProblemDetail::class))],
    )
    @ApiResponse(
        responseCode = "413",
        description = "The body is too large",
        content = [Content(schema = Schema(implementation = ProblemDetail::class))],
    )
    @ApiResponse(responseCode = "415", description = "The body isn't JSON")
    @ApiResponse(
        responseCode = "429",
        description = "Too many commands; try again shortly",
        content = [Content(schema = Schema(implementation = ProblemDetail::class))],
    )
    @io.swagger.v3.oas.annotations.parameters.RequestBody(
        content = [Content(schema = Schema(implementation = WebConsoleCommand::class))]
    )
    @PostMapping(
        consumes = [MediaType.APPLICATION_JSON_VALUE],
        produces = [MediaType.APPLICATION_JSON_VALUE],
    )
    fun command(request: HttpServletRequest): ResponseEntity<*> {
        val credential =
            resolveCredential(request)
                ?: return problem(HttpStatus.UNAUTHORIZED, "Sign in to use the console.")
        val admin =
            access.currentAdmin(credential.principal.id)
                ?: return problem(HttpStatus.FORBIDDEN, "The console is for administrators.")
        if (request.getHeader(HEADER) != "1") {
            return problem(HttpStatus.FORBIDDEN, "Console commands need the $HEADER header.")
        }
        if (!trustedSource(request, credential.fromCookie)) {
            return problem(HttpStatus.FORBIDDEN, "This request's source isn't trusted.")
        }
        val body =
            readBody(request)
                ?: return problem(HttpStatus.PAYLOAD_TOO_LARGE, "That request is too large.")
        val line = runCatching {
            mapper.readValue(body, WebConsoleCommand::class.java).line
        }
            .getOrNull()
        val refusal = invalid(line)
        if (refusal != null) return problem(HttpStatus.BAD_REQUEST, refusal)
        if (!throttleService.tryAcquire("webconsole:commands:${admin.id}", commands)) {
            return problem(HttpStatus.TOO_MANY_REQUESTS, "Too many commands; try again shortly.")
        }

        val correlationId = UUID.randomUUID().toString()
        val provenance =
            Provenance(
                protocol = Protocol.WEBCONSOLE,
                serviceId = WebConsoleAddress.SERVICE,
                replyTo = WebConsoleAddress.replyTo(admin.id),
                user = admin.toUserPrincipal(),
                correlationId = correlationId,
            )
        eventGateway.send(
            MessageBuilder.withPayload(line!!)
                .setHeader(Provenance.HEADER, provenance)
                .setHeader(Provenance.ADDRESSED, true)
                .setHeader(Provenance.LIVE_AUTHORITY, true)
                .setHeader(Provenance.REPORT_FAILURES, true)
                .setHeader("nick", admin.username)
                .build()
        )
        return ResponseEntity.status(HttpStatus.ACCEPTED)
            .cacheControl(CacheControl.noStore())
            .body(WebConsoleAccepted(correlationId))
    }

    @Operation(
        summary = "The console's output, as server-sent events",
        description =
            "A stream of this admin's console output, shared by all their windows. Events: " +
                "`ready` (once, when the stream is registered: wait for it before submitting), " +
                "`result` (data: {correlationId, status, text}; status is success, error or " +
                "unhandled, and text is absent when unhandled; correlationId is null for output " +
                "nobody asked for here, such as a notification or a tell from elsewhere), and " +
                "heartbeat comments. Several results may share a correlation id, and none marks a " +
                "command finished. The stream closes when the credential expires, the account is " +
                "no longer an admin, the client falls too far behind, or after an hour; reconnect " +
                "for a fresh stream. Nothing missed while disconnected is replayed. Authenticates " +
                "by the access-token cookie (as a browser's EventSource sends it) or a bearer " +
                "token. Single backend instance only. Behind nginx, turn off buffering for this " +
                "path (the response says `X-Accel-Buffering: no`) and allow a long read timeout.",
    )
    @ApiResponse(
        responseCode = "200",
        description = "The stream (text/event-stream); `result` events carry WebConsoleResult",
        content =
            [
                Content(
                    mediaType = MediaType.TEXT_EVENT_STREAM_VALUE,
                    schema = Schema(implementation = WebConsoleResult::class),
                )
            ],
    )
    @ApiResponse(
        responseCode = "401",
        description = "Not signed in",
        content = [Content(schema = Schema(implementation = ProblemDetail::class))],
    )
    @ApiResponse(
        responseCode = "403",
        description = "Not an active admin",
        content = [Content(schema = Schema(implementation = ProblemDetail::class))],
    )
    @ApiResponse(
        responseCode = "429",
        description = "Too many streams open, or opened too often",
        content = [Content(schema = Schema(implementation = ProblemDetail::class))],
    )
    @GetMapping("/stream")
    fun stream(request: HttpServletRequest): ResponseEntity<*> {
        val credential =
            resolveCredential(request)
                ?: return problem(HttpStatus.UNAUTHORIZED, "Sign in to use the console.")
        val admin =
            access.currentAdmin(credential.principal.id)
                ?: return problem(HttpStatus.FORBIDDEN, "The console is for administrators.")
        if (!throttleService.tryAcquire("webconsole:streams:${admin.id}", opens)) {
            return problem(
                HttpStatus.TOO_MANY_REQUESTS,
                "Too many reconnections; try again shortly.",
            )
        }
        val emitter: SseEmitter =
            streams.open(admin.id, admin.username, credential.expiresAt)
                ?: return problem(
                    HttpStatus.TOO_MANY_REQUESTS,
                    "Too many console windows open; close one and try again.",
                )
        return ResponseEntity.ok()
            .cacheControl(CacheControl.noStore())
            .header("X-Accel-Buffering", "no")
            .body(emitter)
    }

    /**
     * Whether a POST can be trusted not to be a cross-site forgery. A browser sends the cookie on
     * its own, so a cookie-authenticated request must say where it came from, and somewhere
     * trusted; any request that names an untrusted origin is refused. (The required header makes a
     * cross-origin browser request preflight, which CORS refuses for untrusted origins, too.)
     */
    private fun trustedSource(request: HttpServletRequest, fromCookie: Boolean): Boolean {
        val origin = request.getHeader("Origin")
        if (origin != null) return origin in trustedOrigins
        val referer = request.getHeader("Referer")
        if (referer != null) return originOf(referer) in trustedOrigins
        return !fromCookie
    }

    private fun originOf(url: String): String? = runCatching {
        val uri = URI(url)
        val port = if (uri.port == -1) "" else ":${uri.port}"
        "${uri.scheme}://${uri.host}$port"
    }
        .getOrNull()

    /** The body, or null if it's larger than allowed (read no further than that). */
    private fun readBody(request: HttpServletRequest): ByteArray? {
        if (request.contentLengthLong > properties.maxBody) return null
        val bytes = request.inputStream.readNBytes(properties.maxBody + 1)
        return bytes.takeIf { it.size <= properties.maxBody }
    }

    /** Why [line] can't be run, or null if it can. */
    private fun invalid(line: String?): String? =
        when {
            line == null -> "Send {\"line\": \"...\"}."
            line.isBlank() -> "There's no command to run."
            line.any { it == '\r' || it == '\n' || it == '\u0000' } -> "A command is one line."
            line.length > properties.maxLine ->
                "A command is at most ${properties.maxLine} characters."
            else -> null
        }

    private fun problem(status: HttpStatus, detail: String): ResponseEntity<ProblemDetail> {
        val body = ProblemDetail.forStatusAndDetail(status, detail)
        body.title = status.reasonPhrase
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(body)
    }

    companion object {
        /** The header a console command must carry: a request a cross-site form can't make. */
        const val HEADER = "X-Web-Console"
    }
}
