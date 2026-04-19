/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.service

import dev.streampack.core.config.StreampackProperties
import dev.streampack.web.auth.AuthCookieNames
import java.time.Duration
import org.springframework.http.ResponseCookie
import org.springframework.stereotype.Service

/** Centralizes creation and clearing of authentication cookies */
@Service
class CookieService(properties: StreampackProperties) {
    private val secure = properties.cookie.secure
    private val jwtMaxAge = Duration.ofHours(properties.jwt.expirationHours)
    private val refreshMaxAge = Duration.ofDays(properties.refreshToken.days)

    /** Creates an httpOnly cookie carrying the JWT access token */
    fun createAccessTokenCookie(jwt: String): ResponseCookie =
        ResponseCookie.from(ACCESS_TOKEN_COOKIE, jwt)
            .httpOnly(true)
            .secure(secure)
            .path("/")
            .maxAge(jwtMaxAge)
            .sameSite("Strict")
            .build()

    /** Creates an httpOnly cookie carrying the refresh token */
    fun createRefreshTokenCookie(refreshToken: String): ResponseCookie =
        ResponseCookie.from(REFRESH_TOKEN_COOKIE, refreshToken)
            .httpOnly(true)
            .secure(secure)
            .path("/")
            .maxAge(refreshMaxAge)
            .sameSite("Strict")
            .build()

    /** Creates an expired access token cookie to clear it from the browser */
    fun clearAccessTokenCookie(): ResponseCookie =
        ResponseCookie.from(ACCESS_TOKEN_COOKIE, "")
            .httpOnly(true)
            .secure(secure)
            .path("/")
            .maxAge(0)
            .sameSite("Strict")
            .build()

    /** Creates an expired refresh token cookie to clear it from the browser */
    fun clearRefreshTokenCookie(): ResponseCookie =
        ResponseCookie.from(REFRESH_TOKEN_COOKIE, "")
            .httpOnly(true)
            .secure(secure)
            .path("/")
            .maxAge(0)
            .sameSite("Strict")
            .build()

    companion object {
        const val ACCESS_TOKEN_COOKIE = AuthCookieNames.ACCESS_TOKEN
        const val REFRESH_TOKEN_COOKIE = AuthCookieNames.REFRESH_TOKEN
    }
}
