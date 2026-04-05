/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.config

import org.springframework.boot.context.properties.ConfigurationProperties

/** Central configuration for all streampack core services */
@ConfigurationProperties(prefix = "streampack")
data class StreampackProperties(
    val baseUrl: String = "http://localhost:8080",
    val frontendUrl: String = "",
    val jwt: JwtProperties = JwtProperties(),
    val token: TokenProperties = TokenProperties(),
    val mail: MailProperties = MailProperties(),
    val otp: OtpProperties = OtpProperties(),
    val refreshToken: RefreshTokenProperties = RefreshTokenProperties(),
    val cookie: CookieProperties = CookieProperties(),
    val maxHops: Int = 3,
) {
    data class JwtProperties(val secret: String = "", val expirationHours: Long = 24)

    data class TokenProperties(val emailVerificationHours: Long = 24)

    data class MailProperties(val from: String = "noreply@bytecode.news")

    data class OtpProperties(val maxActiveCodes: Int = 3, val expirationMinutes: Long = 10)

    data class RefreshTokenProperties(val days: Long = 30)

    data class CookieProperties(val secure: Boolean = true)
}
