/* Joseph B. Ottinger (C)2026 */
package dev.streampack.web.error

import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.dao.InvalidDataAccessApiUsageException
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

/**
 * A page number so large that page × size passes Int.MAX_VALUE makes Spring Data refuse the query.
 * That's the caller's mistake (usually a crawler or a probe), so it's a 400 and one log line, not a
 * 500 with a stack trace. Any other misuse of the data API is still a server error.
 */
@RestControllerAdvice
class PageOffsetAdvice {
    private val log = LoggerFactory.getLogger(PageOffsetAdvice::class.java)

    @ExceptionHandler(InvalidDataAccessApiUsageException::class)
    fun pageOffset(
        e: InvalidDataAccessApiUsageException,
        request: HttpServletRequest,
    ): ResponseEntity<ProblemDetail> {
        if (e.message?.startsWith(OFFSET_MESSAGE) != true) throw e
        log.warn("Refused page past the end: {} {}", request.method, request.requestURI)
        return ResponseEntity.badRequest()
            .body(
                ProblemDetail.forStatusAndDetail(
                    HttpStatus.BAD_REQUEST,
                    "That page is too far past the end.",
                )
            )
    }

    companion object {
        /** Spring Data's message, from `PageableUtils.getOffsetAsInteger`. */
        const val OFFSET_MESSAGE = "Page offset exceeds Integer.MAX_VALUE"
    }
}
