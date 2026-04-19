/* Joseph B. Ottinger (C)2026 */
package dev.streampack.web.auth

/** Shared names for HTTP authentication cookies emitted and consumed by web modules. */
object AuthCookieNames {
    /** Browser-facing JWT access token cookie. */
    const val ACCESS_TOKEN = "access_token"

    /** Browser-facing refresh token cookie. */
    const val REFRESH_TOKEN = "refresh_token"
}
