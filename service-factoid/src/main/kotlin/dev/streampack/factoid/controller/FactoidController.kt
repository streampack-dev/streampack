/* Joseph B. Ottinger (C)2026 */
package dev.streampack.factoid.controller

import dev.streampack.core.integration.EventGateway
import dev.streampack.core.model.OperationResult
import dev.streampack.core.model.Protocol
import dev.streampack.core.model.Provenance
import dev.streampack.core.model.UserPrincipal
import dev.streampack.core.service.JwtService
import dev.streampack.factoid.dto.DeriveFactoidHttpRequest
import dev.streampack.factoid.dto.FactoidAttributeResponse
import dev.streampack.factoid.dto.FactoidDetailResponse
import dev.streampack.factoid.dto.FactoidListResponse
import dev.streampack.factoid.dto.FactoidSetHttpRequest
import dev.streampack.factoid.dto.FactoidSummaryResponse
import dev.streampack.factoid.entity.Factoid
import dev.streampack.factoid.model.DeriveFactoidRequest
import dev.streampack.factoid.model.FactoidAttributeType
import dev.streampack.factoid.model.FactoidDraft
import dev.streampack.factoid.model.FactoidForgetRequest
import dev.streampack.factoid.model.FactoidQueryRequest
import dev.streampack.factoid.model.FactoidSetRequest
import dev.streampack.factoid.operation.DeriveFactoidOperation
import dev.streampack.factoid.service.FactoidService
import dev.streampack.web.controller.UserAwareController
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.data.domain.PageRequest
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.messaging.support.MessageBuilder
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * Factoids over HTTP: browsing and search for anyone, and setting and forgetting for a signed-in
 * user, as chat allows (#99). Writes go through the same operations chat uses (as typed requests,
 * so no selector is ever misread as a command), so their rules are chat's: a locked factoid is
 * refused, and only an admin may lock or unlock one.
 */
@RestController
@RequestMapping("/factoids")
@Tag(name = "Factoids")
class FactoidController(
    private val factoidService: FactoidService,
    private val eventGateway: EventGateway,
    jwtService: JwtService,
) : UserAwareController(jwtService) {
    private val logger = LoggerFactory.getLogger(FactoidController::class.java)

    /** Paginated listing with optional search: GET /factoids?q=term&page=0&size=20 */
    @GetMapping(produces = ["application/json"])
    fun list(
        @RequestParam(required = false) q: String?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
    ): FactoidListResponse {
        val pageable = PageRequest.of(page, size.coerceAtMost(100))
        val results =
            if (q.isNullOrBlank()) {
                factoidService.findAll(pageable)
            } else {
                factoidService.searchPaginated(q, pageable)
            }
        val summaries = factoidService.summarizeFor(results.content)
        return FactoidListResponse(
            factoids = results.content.map { it.toSummary(summaries) },
            page = results.number,
            totalPages = results.totalPages,
            totalCount = results.totalElements,
        )
    }

    /** Single factoid with all rendered attributes: GET /factoids/{selector} */
    @GetMapping("/{selector}", produces = ["application/json"])
    fun get(@PathVariable selector: String): ResponseEntity<FactoidDetailResponse> {
        val detail = detail(selector) ?: return ResponseEntity.notFound().build()
        factoidService.recordAccess(selector)
        return ResponseEntity.ok(detail)
    }

    @Operation(
        summary = "Set a factoid's attribute, creating the factoid if it's new",
        description =
            "Sets one attribute (the text, unless another is named) as chat's `selector=value` " +
                "does, as the signed-in user. A locked factoid is refused.",
    )
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponse(
        responseCode = "200",
        description = "Updated; the factoid as it now stands",
        content = [Content(schema = Schema(implementation = FactoidDetailResponse::class))],
    )
    @ApiResponse(
        responseCode = "201",
        description = "Created; the new factoid",
        content = [Content(schema = Schema(implementation = FactoidDetailResponse::class))],
    )
    @ApiResponse(
        responseCode = "400",
        description = "No value, or an attribute that can't be set",
        content = [Content(schema = Schema(implementation = ProblemDetail::class))],
    )
    @ApiResponse(
        responseCode = "401",
        description = "Not signed in",
        content = [Content(schema = Schema(implementation = ProblemDetail::class))],
    )
    @ApiResponse(
        responseCode = "403",
        description = "Factoid changes aren't enabled for the web",
        content = [Content(schema = Schema(implementation = ProblemDetail::class))],
    )
    @ApiResponse(
        responseCode = "409",
        description = "The factoid is locked",
        content = [Content(schema = Schema(implementation = ProblemDetail::class))],
    )
    @PutMapping("/{selector}", produces = ["application/json"], consumes = ["application/json"])
    fun set(
        @PathVariable selector: String,
        @RequestBody request: FactoidSetHttpRequest,
        httpRequest: HttpServletRequest,
    ): ResponseEntity<*> {
        val user =
            resolveUser(httpRequest)
                ?: return problem(HttpStatus.UNAUTHORIZED, "Authentication required")
        val name = selector.trim()
        if (name.isEmpty()) return problem(HttpStatus.BAD_REQUEST, "Give the factoid a name.")
        if (name.length > MAX_SELECTOR)
            return problem(
                HttpStatus.BAD_REQUEST,
                "A factoid's name is at most $MAX_SELECTOR characters.",
            )
        if (request.value.isBlank())
            return problem(HttpStatus.BAD_REQUEST, "Give the factoid a value.")
        val attribute =
            settable(request.attribute)
                ?: return problem(
                    HttpStatus.BAD_REQUEST,
                    "'${request.attribute}' isn't an attribute that can be set.",
                )

        val existed = factoidService.findFactoid(name) != null
        return when (
            val result =
                dispatch(FactoidSetRequest(name, attribute, request.value.trim()), "set", user)
        ) {
            is OperationResult.Success -> {
                val detail =
                    detail(name)
                        ?: return problem(
                            HttpStatus.INTERNAL_SERVER_ERROR,
                            "The factoid was set but can't be read back.",
                        )
                ResponseEntity.status(if (existed) HttpStatus.OK else HttpStatus.CREATED)
                    .body(detail)
            }
            else -> failure(result)
        }
    }

    @Operation(
        summary = "Draft a factoid for a name, for its author to edit and save",
        description =
            "Writes a draft (text, URLs, tags, see-also) in the knowledge base's style, with the " +
                "line the bot would say and whether it fits. URLs are checked to answer, tags " +
                "limited to those in use (one new at most), see-also to existing factoids. Nothing " +
                "is stored: save with PUT /factoids/{selector}. Needs AI; signed-in readers only.",
        operationId = "deriveFactoid",
    )
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponse(
        responseCode = "200",
        description = "The draft",
        content = [Content(schema = Schema(implementation = FactoidDraft::class))],
    )
    @ApiResponse(
        responseCode = "400",
        description = "No name, or one too long",
        content = [Content(schema = Schema(implementation = ProblemDetail::class))],
    )
    @ApiResponse(
        responseCode = "401",
        description = "Not signed in",
        content = [Content(schema = Schema(implementation = ProblemDetail::class))],
    )
    @ApiResponse(
        responseCode = "409",
        description = "A factoid by that name exists",
        content = [Content(schema = Schema(implementation = ProblemDetail::class))],
    )
    @ApiResponse(
        responseCode = "503",
        description = "AI isn't configured, or the draft couldn't be written",
        content = [Content(schema = Schema(implementation = ProblemDetail::class))],
    )
    @PostMapping("/derive", produces = ["application/json"], consumes = ["application/json"])
    fun derive(
        @RequestBody request: DeriveFactoidHttpRequest,
        httpRequest: HttpServletRequest,
    ): ResponseEntity<*> {
        val user =
            resolveUser(httpRequest)
                ?: return problem(HttpStatus.UNAUTHORIZED, "Authentication required")
        val result =
            dispatch(
                DeriveFactoidRequest(request.selector.orEmpty(), request.context.orEmpty()),
                "derive",
                user,
            )
        return when {
            result is OperationResult.Success -> ResponseEntity.ok(result.payload)
            result is OperationResult.Error && result.message.contains("already exists") ->
                problem(HttpStatus.CONFLICT, result.message)
            result is OperationResult.Error &&
                (result.message == DeriveFactoidOperation.AI_UNAVAILABLE ||
                    result.message.startsWith("The draft couldn't")) ->
                problem(HttpStatus.SERVICE_UNAVAILABLE, result.message)
            else -> failure(result)
        }
    }

    @Operation(
        summary = "Forget a factoid, or one of its attributes",
        description =
            "As chat's `forget selector` (or `forget selector.attribute`), as the signed-in user. A locked factoid is refused.",
    )
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponse(responseCode = "204", description = "Forgotten")
    @ApiResponse(
        responseCode = "400",
        description = "An attribute that can't be forgotten",
        content = [Content(schema = Schema(implementation = ProblemDetail::class))],
    )
    @ApiResponse(
        responseCode = "401",
        description = "Not signed in",
        content = [Content(schema = Schema(implementation = ProblemDetail::class))],
    )
    @ApiResponse(
        responseCode = "403",
        description = "Factoid changes aren't enabled for the web",
        content = [Content(schema = Schema(implementation = ProblemDetail::class))],
    )
    @ApiResponse(
        responseCode = "404",
        description = "No such factoid, or no such attribute on it",
        content = [Content(schema = Schema(implementation = ProblemDetail::class))],
    )
    @ApiResponse(
        responseCode = "409",
        description = "The factoid is locked",
        content = [Content(schema = Schema(implementation = ProblemDetail::class))],
    )
    @DeleteMapping("/{selector}", produces = ["application/json"])
    fun forget(
        @PathVariable selector: String,
        @RequestParam(required = false) attribute: String?,
        httpRequest: HttpServletRequest,
    ): ResponseEntity<*> {
        val user =
            resolveUser(httpRequest)
                ?: return problem(HttpStatus.UNAUTHORIZED, "Authentication required")
        val type =
            if (attribute == null) {
                null
            } else {
                settable(attribute)
                    ?: return problem(
                        HttpStatus.BAD_REQUEST,
                        "'$attribute' isn't an attribute that can be forgotten.",
                    )
            }
        return when (
            val result = dispatch(FactoidForgetRequest(selector.trim(), type), "forget", user)
        ) {
            is OperationResult.Success -> ResponseEntity.noContent().build<Any>()
            else -> failure(result)
        }
    }

    @Operation(summary = "Lock a factoid against changes (admins)")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponse(responseCode = "204", description = "Locked")
    @ApiResponse(
        responseCode = "401",
        description = "Not signed in",
        content = [Content(schema = Schema(implementation = ProblemDetail::class))],
    )
    @ApiResponse(
        responseCode = "403",
        description = "Not an admin",
        content = [Content(schema = Schema(implementation = ProblemDetail::class))],
    )
    @ApiResponse(
        responseCode = "404",
        description = "No such factoid",
        content = [Content(schema = Schema(implementation = ProblemDetail::class))],
    )
    @PutMapping("/{selector}/lock", produces = ["application/json"])
    fun lock(@PathVariable selector: String, httpRequest: HttpServletRequest): ResponseEntity<*> =
        setLocked(selector, FactoidAttributeType.LOCK, httpRequest)

    @Operation(summary = "Unlock a factoid (admins)")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponse(responseCode = "204", description = "Unlocked")
    @ApiResponse(
        responseCode = "401",
        description = "Not signed in",
        content = [Content(schema = Schema(implementation = ProblemDetail::class))],
    )
    @ApiResponse(
        responseCode = "403",
        description = "Not an admin",
        content = [Content(schema = Schema(implementation = ProblemDetail::class))],
    )
    @ApiResponse(
        responseCode = "404",
        description = "No such factoid",
        content = [Content(schema = Schema(implementation = ProblemDetail::class))],
    )
    @DeleteMapping("/{selector}/lock", produces = ["application/json"])
    fun unlock(@PathVariable selector: String, httpRequest: HttpServletRequest): ResponseEntity<*> =
        setLocked(selector, FactoidAttributeType.UNLOCK, httpRequest)

    private fun setLocked(
        selector: String,
        change: FactoidAttributeType,
        httpRequest: HttpServletRequest,
    ): ResponseEntity<*> {
        val user =
            resolveUser(httpRequest)
                ?: return problem(HttpStatus.UNAUTHORIZED, "Authentication required")
        val name = selector.trim()
        // Exactly this factoid: chat's lookup would also take a longer selector as a shorter one
        // with arguments ("spring boot" as "spring" given "boot"), and lock the wrong factoid.
        if (factoidService.findFactoid(name) == null)
            return problem(HttpStatus.NOT_FOUND, "Factoid '$name' not found.")
        return when (val result = dispatch(FactoidQueryRequest(name, change), "lock", user)) {
            is OperationResult.Success -> ResponseEntity.noContent().build<Any>()
            else -> failure(result)
        }
    }

    /** [name] as an attribute that can be set (or forgotten): the text, unless another is named. */
    private fun settable(name: String?): FactoidAttributeType? {
        if (name.isNullOrBlank()) return FactoidAttributeType.TEXT
        val type = FactoidAttributeType.knownAttributes[name.trim().lowercase()] ?: return null
        return type.takeIf { it.mutable }
    }

    /** Sends [payload] through the operations as [user], from this service's HTTP provenance. */
    private fun dispatch(payload: Any, replyTo: String, user: UserPrincipal): OperationResult {
        val provenance =
            Provenance(
                protocol = Protocol.HTTP,
                serviceId = SERVICE_ID,
                replyTo = replyTo,
                user = user,
            )
        val message =
            MessageBuilder.withPayload(payload).setHeader(Provenance.HEADER, provenance).build()
        return eventGateway.process(message)
    }

    /** An operation's refusal, or no operation at all, as a problem detail. */
    private fun failure(result: OperationResult): ResponseEntity<*> {
        if (result !is OperationResult.Error) {
            // Nothing took it: the factoid group is switched off for this provenance.
            logger.warn("A factoid change over HTTP wasn't handled: {}", result)
            return problem(HttpStatus.FORBIDDEN, "Factoid changes aren't enabled for the web.")
        }
        val status =
            when {
                result.message.endsWith("is locked.") -> HttpStatus.CONFLICT
                result.message.contains("requires admin", ignoreCase = true) -> HttpStatus.FORBIDDEN
                result.message.contains("not found", ignoreCase = true) -> HttpStatus.NOT_FOUND
                else -> HttpStatus.BAD_REQUEST
            }
        return problem(status, result.message)
    }

    private fun problem(status: HttpStatus, detail: String): ResponseEntity<*> =
        ResponseEntity.status(status).body(ProblemDetail.forStatusAndDetail(status, detail))

    /** The factoid with its rendered attributes, or null when there's no such factoid. */
    private fun detail(selector: String): FactoidDetailResponse? {
        val factoid = factoidService.findFactoid(selector) ?: return null
        val attributes = factoidService.findBySelector(selector)
        if (attributes.isEmpty()) return null
        val attributeResponses =
            attributes
                .filter { !it.attributeValue.isNullOrEmpty() }
                .filter { it.attributeType.includeInSummary }
                .sortedBy { it.attributeType.ordinal }
                .map { attr ->
                    FactoidAttributeResponse(
                        type = attr.attributeType.name.lowercase(),
                        value = attr.attributeValue,
                        rendered = attr.attributeType.render(selector, attr.attributeValue),
                    )
                }
        return FactoidDetailResponse(
            selector = factoid.selector,
            locked = factoid.locked,
            updatedBy = factoid.updatedBy,
            updatedAt = factoid.updatedAt,
            lastAccessedAt = factoid.lastAccessedAt,
            accessCount = factoid.accessCount,
            attributes = attributeResponses,
        )
    }

    private fun Factoid.toSummary(
        summaries: Map<String, dev.streampack.factoid.service.FactoidService.FactoidListSummary>
    ) =
        FactoidSummaryResponse(
            selector = selector,
            locked = locked,
            updatedBy = updatedBy,
            updatedAt = updatedAt,
            lastAccessedAt = lastAccessedAt,
            accessCount = accessCount,
            text = summaries[selector]?.text,
            tags = summaries[selector]?.tags ?: emptyList(),
        )

    companion object {
        /** The service the factoid endpoints' provenance names: http://factoid/... */
        const val SERVICE_ID = "factoid"

        /** A selector's longest, as the factoid table holds it. */
        const val MAX_SELECTOR = 200
    }
}
