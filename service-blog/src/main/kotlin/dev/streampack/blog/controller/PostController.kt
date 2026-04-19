/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.controller

import dev.streampack.blog.config.BlogProperties
import dev.streampack.blog.model.ContentDetail
import dev.streampack.blog.model.ContentListResponse
import dev.streampack.blog.model.CreateContentHttpRequest
import dev.streampack.blog.model.CreateContentRequest
import dev.streampack.blog.model.CreateContentResponse
import dev.streampack.blog.model.DeriveSummaryHttpRequest
import dev.streampack.blog.model.DeriveSummaryRequest
import dev.streampack.blog.model.DeriveSummaryResponse
import dev.streampack.blog.model.EditContentHttpRequest
import dev.streampack.blog.model.EditContentRequest
import dev.streampack.blog.model.FindContentRequest
import dev.streampack.blog.model.PostStatus
import dev.streampack.blog.model.RecordPostAccessRequest
import dev.streampack.blog.model.SuggestTagsHttpRequest
import dev.streampack.blog.model.SuggestTagsRequest
import dev.streampack.blog.model.SuggestTagsResponse
import dev.streampack.core.integration.EventGateway
import dev.streampack.core.model.OperationResult
import dev.streampack.core.model.Protocol
import dev.streampack.core.model.Provenance
import dev.streampack.core.model.UserPrincipal
import dev.streampack.core.service.JwtService
import dev.streampack.web.controller.UserAwareController
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.servlet.http.HttpServletRequest
import java.time.Instant
import java.util.UUID
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.messaging.support.MessageBuilder
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/** HTTP adapter for public and authenticated post endpoints */
@RestController
@Tag(name = "Posts")
class PostController(
    private val eventGateway: EventGateway,
    jwtService: JwtService,
    private val blogProperties: BlogProperties,
) : UserAwareController(jwtService) {
    private val serviceId = blogProperties.serviceId
    private val logger = LoggerFactory.getLogger(PostController::class.java)

    @Operation(
        summary = "List published posts",
        description =
            "Returns a paginated list of posts that are approved, not deleted, " +
                "and have a publishedAt date in the past. Authenticated users also see " +
                "their own drafts interleaved in the results.",
        operationId = "listPosts",
    )
    @ApiResponse(
        responseCode = "200",
        description = "Paginated list of published posts",
        content = [Content(schema = Schema(implementation = ContentListResponse::class))],
    )
    @GetMapping("/posts", produces = ["application/json"])
    fun listPosts(
        @Parameter(description = "Zero-based page index", example = "0")
        @RequestParam(defaultValue = "0")
        page: Int,
        @Parameter(description = "Number of posts per page", example = "20")
        @RequestParam(defaultValue = "20")
        size: Int,
        @Parameter(description = "Filter by category name")
        @RequestParam(required = false)
        category: String?,
        @Parameter(description = "Filter by tag name") @RequestParam(required = false) tag: String?,
        httpRequest: HttpServletRequest,
    ): ResponseEntity<*> {
        val user = resolveUser(httpRequest)
        val payload =
            if (tag != null) {
                FindContentRequest.FindByTag(tag, page, size)
            } else if (category != null) {
                FindContentRequest.FindByCategory(category, page, size)
            } else {
                FindContentRequest.FindPublished(page, size)
            }
        return dispatch(payload, "posts/list", user) { result -> mapError(result) }
    }

    @Operation(
        summary = "Get a published post by slug",
        description =
            "Returns full post detail including rendered HTML, tags, categories, and " +
                "comment count. Draft posts are only visible to their author and admins.",
        operationId = "getPostBySlug",
    )
    @ApiResponse(
        responseCode = "200",
        description = "Post detail",
        content = [Content(schema = Schema(implementation = ContentDetail::class))],
    )
    @ApiResponse(
        responseCode = "404",
        description = "Post not found",
        content = [Content(schema = Schema(implementation = ProblemDetail::class))],
    )
    @GetMapping("/posts/{year}/{month}/{slug}", produces = ["application/json"])
    fun getPost(
        @Parameter(description = "Publication year", example = "2026")
        @PathVariable
        @Schema(minimum = "2007", maximum = "3000")
        year: Int,
        @Parameter(description = "Publication month (1-12)", example = "3")
        @PathVariable
        @Schema(minimum = "1", maximum = "12")
        month: Int,
        @Parameter(description = "URL-safe slug derived from the post title", example = "my-post")
        @PathVariable
        slug: String,
        httpRequest: HttpServletRequest,
    ): ResponseEntity<*> {
        val user = resolveUser(httpRequest)
        val payload = FindContentRequest.FindBySlug("$year/${"%02d".format(month)}/$slug")
        return maybeTrackPostAccess(
            dispatch(payload, "posts/detail", user) { result -> mapError(result) }
        )
    }

    @Operation(
        summary = "Get a post by ID",
        description =
            "Returns full post detail by its UUIDv7 identifier. " +
                "Draft posts are only visible to their author and admins.",
        operationId = "getPostById",
    )
    @ApiResponse(
        responseCode = "200",
        description = "Post detail",
        content = [Content(schema = Schema(implementation = ContentDetail::class))],
    )
    @ApiResponse(
        responseCode = "404",
        description = "Post not found",
        content = [Content(schema = Schema(implementation = ProblemDetail::class))],
    )
    @GetMapping("/posts/{id}", produces = ["application/json"])
    fun getPostById(
        @Parameter(description = "Post UUIDv7") @PathVariable id: UUID,
        httpRequest: HttpServletRequest,
    ): ResponseEntity<*> {
        val user = resolveUser(httpRequest)
        val payload = FindContentRequest.FindById(id)
        return maybeTrackPostAccess(
            dispatch(payload, "posts/detail", user) { result -> mapError(result) }
        )
    }

    @Operation(summary = "Record a UI-driven access of a blog post")
    @ApiResponse(responseCode = "202", description = "Access tracking accepted")
    @PostMapping("/posts/{id}/access")
    fun recordPostAccess(
        @Parameter(description = "Post UUIDv7") @PathVariable id: UUID
    ): ResponseEntity<Void> {
        eventGateway.send(MessageBuilder.withPayload(RecordPostAccessRequest(id)).build())
        return ResponseEntity.accepted().build()
    }

    @Operation(
        summary = "Search published posts",
        description =
            "Full-text search across post titles (weighted higher) and excerpts. " +
                "Only published, non-deleted posts are searched. " +
                "Results are ranked by relevance. Blank queries return an empty list.",
        operationId = "searchPosts",
    )
    @ApiResponse(
        responseCode = "200",
        description = "Search results ranked by relevance",
        content = [Content(schema = Schema(implementation = ContentListResponse::class))],
    )
    @GetMapping("/posts/search", produces = ["application/json"])
    fun searchPosts(
        @Parameter(description = "Search query", example = "virtual threads", required = true)
        @RequestParam
        q: String,
        @Parameter(description = "Zero-based page index", example = "0")
        @RequestParam(defaultValue = "0")
        page: Int,
        @Parameter(description = "Number of results per page", example = "20")
        @RequestParam(defaultValue = "20")
        size: Int,
        httpRequest: HttpServletRequest,
    ): ResponseEntity<*> {
        val user = resolveUser(httpRequest)
        val payload = FindContentRequest.Search(q, page, size)
        return dispatch(payload, "posts/search", user) { result -> mapError(result) }
    }

    @Operation(
        summary = "Create a new blog post draft",
        description =
            "Submits a new post as a DRAFT awaiting admin approval. " +
                "Authentication is required unless anonymous submission is enabled " +
                "(see GET /features for the anonymousSubmission flag). " +
                "Authenticated users must have a verified email. " +
                "If a `summary` is provided, it is persisted as the post excerpt. " +
                "Otherwise an excerpt is derived heuristically. " +
                "Includes honeypot and timing-based spam prevention - frontends should " +
                "include the 'website' field as a CSS-hidden input (left empty) and set " +
                "'formLoadedAt' to Date.now() when the form renders.",
        operationId = "createPost",
    )
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponse(
        responseCode = "201",
        description = "Post created as draft",
        content = [Content(schema = Schema(implementation = CreateContentResponse::class))],
    )
    @ApiResponse(
        responseCode = "400",
        description = "Validation error (blank title, blank content, or unverified email)",
        content = [Content(schema = Schema(implementation = ProblemDetail::class))],
    )
    @ApiResponse(
        responseCode = "401",
        description = "Authentication required (when anonymous submission is disabled)",
        content = [Content(schema = Schema(implementation = ProblemDetail::class))],
    )
    @PostMapping("/posts", produces = ["application/json"], consumes = ["application/json"])
    fun createPost(
        @RequestBody request: CreateContentHttpRequest,
        httpRequest: HttpServletRequest,
    ): ResponseEntity<*> {
        // Honeypot check - bots auto-fill hidden fields; return fake success to avoid tipping
        // them off
        if (!request.website.isNullOrBlank()) {
            logger.debug("Honeypot field populated, rejecting silently")
            return fakeCreatedResponse()
        }

        // Timing check - formLoadedAt is mandatory; reject if missing or too fast
        val loadedAt = request.formLoadedAt
        if (loadedAt == null) {
            logger.debug("Missing formLoadedAt, rejecting silently")
            return fakeCreatedResponse()
        }
        val elapsed = Instant.now().toEpochMilli() - loadedAt
        if (elapsed < MINIMUM_FORM_DURATION_MS) {
            logger.debug("Form submitted too quickly ({}ms), rejecting silently", elapsed)
            return fakeCreatedResponse()
        }

        val user = resolveUser(httpRequest)

        // Gate anonymous submissions behind feature flag
        if (user == null && !blogProperties.anonymousSubmission) {
            return unauthorized("Authentication required")
        }

        val payload =
            CreateContentRequest(
                title = request.title,
                markdownSource = request.markdownSource,
                tags = request.tags,
                categoryIds = request.categoryIds,
                summary = request.summary,
            )
        return dispatchCreated(payload, "posts/create", user) { result -> mapError(result) }
    }

    @Operation(
        summary = "Edit a post",
        description =
            "Updates an existing post's title, content, tags, and/or categories. " +
                "Editing is admin-only. " +
                "Edits re-render HTML from the updated markdown source. " +
                "If a `summary` is provided, it is persisted as the post excerpt.",
        operationId = "editPost",
    )
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponse(
        responseCode = "200",
        description = "Post updated",
        content = [Content(schema = Schema(implementation = ContentDetail::class))],
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
    @ApiResponse(
        responseCode = "404",
        description = "Post not found or deleted",
        content = [Content(schema = Schema(implementation = ProblemDetail::class))],
    )
    @PutMapping("/posts/{id}", produces = ["application/json"], consumes = ["application/json"])
    fun editPost(
        @Parameter(description = "Post UUIDv7") @PathVariable id: UUID,
        @RequestBody request: EditContentHttpRequest,
        httpRequest: HttpServletRequest,
    ): ResponseEntity<*> {
        val user = resolveUser(httpRequest) ?: return unauthorized("Authentication required")
        val payload =
            EditContentRequest(
                id = id,
                title = request.title,
                markdownSource = request.markdownSource,
                tags = request.tags,
                categoryIds = request.categoryIds,
                summary = request.summary,
                publishedAt = request.publishedAt,
                sortOrder = request.sortOrder,
            )
        return dispatch(payload, "posts/edit", user) { result -> mapError(result) }
    }

    @Operation(
        summary = "Suggest tags heuristically from unsaved draft content",
        description =
            "Returns non-persistent tag suggestions using deterministic heuristics (no AI call). " +
                "Available to all callers.",
        operationId = "suggestTags",
    )
    @ApiResponse(
        responseCode = "200",
        description = "Suggested tags",
        content = [Content(schema = Schema(implementation = SuggestTagsResponse::class))],
    )
    @ApiResponse(
        responseCode = "400",
        description = "Invalid input",
        content = [Content(schema = Schema(implementation = ProblemDetail::class))],
    )
    @PostMapping(
        "/posts/derive-tags",
        produces = ["application/json"],
        consumes = ["application/json"],
    )
    fun suggestTags(
        @RequestBody request: SuggestTagsHttpRequest,
        httpRequest: HttpServletRequest,
    ): ResponseEntity<*> {
        val user = resolveUser(httpRequest)
        val payload =
            SuggestTagsRequest(
                title = request.title ?: "",
                markdownSource = request.markdownSource ?: "",
                existingTags = request.existingTags ?: emptyList(),
            )
        return dispatch(payload, "posts/derive-tags", user) { result -> mapError(result) }
    }

    @Operation(
        summary = "Derive a summary heuristically from unsaved draft content",
        description =
            "Returns a non-persistent summary generated from title + markdown content. " +
                "Requires authentication. The returned value is the same text shape used for " +
                "persisted excerpts unless manually overridden.",
        operationId = "deriveSummary",
    )
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponse(
        responseCode = "200",
        description = "Derived summary",
        content = [Content(schema = Schema(implementation = DeriveSummaryResponse::class))],
    )
    @ApiResponse(
        responseCode = "401",
        description = "Authentication required",
        content = [Content(schema = Schema(implementation = ProblemDetail::class))],
    )
    @ApiResponse(
        responseCode = "400",
        description = "Invalid input",
        content = [Content(schema = Schema(implementation = ProblemDetail::class))],
    )
    @PostMapping(
        "/posts/derive-summary",
        produces = ["application/json"],
        consumes = ["application/json"],
    )
    fun deriveSummary(
        @RequestBody request: DeriveSummaryHttpRequest,
        httpRequest: HttpServletRequest,
    ): ResponseEntity<*> {
        val user = resolveUser(httpRequest) ?: return unauthorized("Authentication required")
        val payload =
            DeriveSummaryRequest(
                title = request.title ?: "",
                markdownSource = request.markdownSource ?: "",
            )
        return dispatch(payload, "posts/derive-summary", user) { result -> mapError(result) }
    }

    /** Sends a payload through the event system and maps the result to an HTTP response */
    private fun dispatch(
        payload: Any,
        replyTo: String,
        user: UserPrincipal? = null,
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

    /** Sends a payload and returns 201 Created on success */
    private fun dispatchCreated(
        payload: Any,
        replyTo: String,
        user: UserPrincipal?,
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
            is OperationResult.Success ->
                ResponseEntity.status(HttpStatus.CREATED).body(result.payload)
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

    private fun maybeTrackPostAccess(response: ResponseEntity<*>): ResponseEntity<*> {
        val detail = response.body as? ContentDetail ?: return response
        if (response.statusCode.is2xxSuccessful) {
            eventGateway.send(
                MessageBuilder.withPayload(RecordPostAccessRequest(detail.id)).build()
            )
        }
        return response
    }

    /** Maps an error to the appropriate HTTP status based on error message content */
    private fun mapError(result: OperationResult): ResponseEntity<*> {
        val message = (result as OperationResult.Error).message
        val status =
            when {
                message.contains("Authentication required") -> HttpStatus.UNAUTHORIZED
                message.contains("Not authorized") -> HttpStatus.FORBIDDEN
                message.contains("Insufficient privileges") -> HttpStatus.FORBIDDEN
                message.contains("not found", ignoreCase = true) -> HttpStatus.NOT_FOUND
                else -> HttpStatus.BAD_REQUEST
            }
        logger.debug("Operation error on post endpoint: {}", message)
        return ResponseEntity.status(status).body(ProblemDetail.forStatusAndDetail(status, message))
    }

    /** Returns a plausible 201 to avoid revealing spam detection to bots */
    private fun fakeCreatedResponse(): ResponseEntity<*> {
        val fake =
            CreateContentResponse(
                id = UUID.randomUUID(),
                title = "Submitted",
                slug = "pending",
                excerpt = null,
                status = PostStatus.DRAFT,
                authorId = null,
                authorDisplayName = "Anonymous",
                createdAt = Instant.now(),
            )
        return ResponseEntity.status(HttpStatus.CREATED).body(fake)
    }

    private fun unauthorized(message: String): ResponseEntity<*> {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
            .body(ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, message))
    }

    companion object {
        private const val MINIMUM_FORM_DURATION_MS = 3000L
    }
}
