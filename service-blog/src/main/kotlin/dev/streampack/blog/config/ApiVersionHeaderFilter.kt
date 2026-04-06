/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.config

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

/**
 * Advisory API version headers.
 *
 * `Accept-Version` is optional and currently non-breaking: unsupported values fall back to current
 * behavior, but are logged. `Content-Version` always reflects the server's current contract
 * version.
 */
@Component
class ApiVersionHeaderFilter(
    @Value("\${streampack.api.version.current:2026-03-10}") private val currentVersion: String,
    @Value("\${streampack.api.version.supported:}") supportedVersionsRaw: String,
) : OncePerRequestFilter() {
    // OncePerRequestFilter has its own logger, so we need to use our own
    private val localLogger = LoggerFactory.getLogger(this::class.java)

    private val supportedVersions: Set<String> =
        (supportedVersionsRaw.split(",").map { it.trim() }.filter { it.isNotEmpty() } +
                currentVersion)
            .toSet()

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val requested = request.getHeader(ACCEPT_VERSION_HEADER)?.trim()?.takeIf { it.isNotEmpty() }
        val resolved =
            when {
                requested == null -> currentVersion
                requested in supportedVersions -> requested
                else -> {
                    localLogger.warn(
                        "Unknown Accept-Version '{}' for {} {}; defaulting to '{}'",
                        requested,
                        request.method,
                        request.requestURI,
                        currentVersion,
                    )
                    currentVersion
                }
            }

        response.setHeader(CONTENT_VERSION_HEADER, currentVersion)
        response.setHeader(ACCEPT_VERSION_HEADER, resolved)
        filterChain.doFilter(request, response)
    }

    companion object {
        const val ACCEPT_VERSION_HEADER = "Accept-Version"
        const val CONTENT_VERSION_HEADER = "Content-Version"
    }
}
