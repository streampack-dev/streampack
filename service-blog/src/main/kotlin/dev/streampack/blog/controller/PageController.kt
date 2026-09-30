/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.controller

import dev.streampack.blog.model.ContentDetail
import dev.streampack.blog.model.FindContentRequest
import dev.streampack.blog.model.MessageResponse
import dev.streampack.blog.repository.ContentValidators
import dev.streampack.core.integration.EventGateway
import dev.streampack.core.model.OperationResult
import dev.streampack.core.model.Protocol
import dev.streampack.core.model.Provenance
import dev.streampack.core.service.JwtService
import dev.streampack.web.controller.UserAwareController
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import java.time.Instant
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.messaging.support.MessageBuilder
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** Serves system pages from the _pages category by slug */
@RestController
@RequestMapping("/pages")
@Tag(name = "Pages")
class PageController(
    private val eventGateway: EventGateway,
    jwtService: JwtService,
    private val validators: ContentValidators,
    private val conditionalGet: ConditionalGet,
) : UserAwareController(jwtService) {

    private val logger = LoggerFactory.getLogger(PageController::class.java)

    @Operation(
        summary = "Get a system page by slug",
        description =
            "Returns a page from the _pages or _sidebar category (About, Policies, and the " +
                "like) as the same ContentDetail a post has, at its undated address.",
        operationId = "getPage",
    )
    @ApiResponse(
        responseCode = "200",
        description = "Page detail",
        content = [Content(schema = Schema(implementation = ContentDetail::class))],
    )
    @ApiResponse(
        responseCode = "404",
        description = "Page not found",
        content = [Content(schema = Schema(implementation = ProblemDetail::class))],
    )
    @GetMapping("/{slug}", produces = ["application/json"])
    fun getPage(
        @Parameter(description = "The page's slug", example = "about") @PathVariable slug: String,
        httpRequest: HttpServletRequest,
        httpResponse: HttpServletResponse,
    ): ResponseEntity<*> {
        val user = resolveUser(httpRequest)
        return conditionalGet.respond(
            httpRequest,
            httpResponse,
            personal = user != null,
            key = listOf("page", slug),
            validator = { validators.page(slug, Instant.now()) },
        ) {
            findPage(slug, user)
        }
    }

    private fun findPage(
        slug: String,
        user: dev.streampack.core.model.UserPrincipal?,
    ): ResponseEntity<*> {
        val payload = FindContentRequest.FindPage(slug)
        val provenance =
            Provenance(
                protocol = Protocol.HTTP,
                serviceId = "blog-service",
                replyTo = "pages/$slug",
                user = user,
            )
        val message =
            MessageBuilder.withPayload(payload as Any)
                .setHeader(Provenance.HEADER, provenance)
                .build()

        return when (val result = eventGateway.process(message)) {
            is OperationResult.Success -> ResponseEntity.ok(MessageResponse.body(result.payload))
            is OperationResult.Error -> {
                logger.debug("Page not found: {}", slug)
                ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, result.message))
            }
            else -> {
                logger.warn("FindPage for slug '{}' was not handled", slug)
                ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "Page not found"))
            }
        }
    }
}
