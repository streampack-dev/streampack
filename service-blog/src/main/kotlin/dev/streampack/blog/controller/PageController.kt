/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.controller

import dev.streampack.blog.model.FindContentRequest
import dev.streampack.blog.repository.ContentValidators
import dev.streampack.core.integration.EventGateway
import dev.streampack.core.model.OperationResult
import dev.streampack.core.model.Protocol
import dev.streampack.core.model.Provenance
import dev.streampack.core.service.JwtService
import dev.streampack.web.controller.UserAwareController
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
class PageController(
    private val eventGateway: EventGateway,
    jwtService: JwtService,
    private val validators: ContentValidators,
    private val conditionalGet: ConditionalGet,
) : UserAwareController(jwtService) {

    private val logger = LoggerFactory.getLogger(PageController::class.java)

    @GetMapping("/{slug}", produces = ["application/json"])
    fun getPage(
        @PathVariable slug: String,
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
            is OperationResult.Success -> ResponseEntity.ok(result.payload)
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
