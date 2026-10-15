/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.config

import org.springframework.boot.context.properties.ConfigurationProperties

/** Central configuration for all streampack core services */
@ConfigurationProperties(prefix = "streampack")
data class StreampackProperties(
    /** The deployment's name (`STREAMPACK_NAME`, #228), as it introduces itself. */
    val name: String = "nevet",
    val baseUrl: String = "http://localhost:8080",
    /**
     * The directory whose jars and files join the classpath (`STREAMPACK_EXT_DIR`, #229). The image
     * passes it to Spring Boot's launcher; the server only reports what's in it.
     */
    val extDir: String = "/opt/streampack/ext",
    val frontendUrl: String = "",
    val jwt: JwtProperties = JwtProperties(),
    val token: TokenProperties = TokenProperties(),
    val mail: MailProperties = MailProperties(),
    val otp: OtpProperties = OtpProperties(),
    val refreshToken: RefreshTokenProperties = RefreshTokenProperties(),
    val cookie: CookieProperties = CookieProperties(),
    val maxHops: Int = 3,
    val logs: LogsProperties = LogsProperties(),
    val localAdminToken: LocalAdminTokenProperties = LocalAdminTokenProperties(),
) {
    data class JwtProperties(val secret: String = "", val expirationHours: Long = 24)

    data class TokenProperties(val emailVerificationHours: Long = 24)

    data class MailProperties(val from: String = "noreply@bytecode.news")

    /**
     * [maxFailedAttempts] is how many wrong codes a recipient may present before every active code
     * it holds is expired and it has to ask for a new one.
     */
    data class OtpProperties(
        val maxActiveCodes: Int = 3,
        val expirationMinutes: Long = 10,
        val maxFailedAttempts: Int = 5,
    )

    data class RefreshTokenProperties(val days: Long = 30)

    data class CookieProperties(val secure: Boolean = true)

    /** [zone] is the IANA timezone channel logs are split into days by (#212) */
    data class LogsProperties(val zone: String = "UTC")

    /**
     * A super-admin token written to [file] for a setup tool on the same box (#209), valid for
     * [lifetime] and renewed at half of it. Off while [file] is blank.
     */
    data class LocalAdminTokenProperties(
        val file: String = "",
        val lifetime: java.time.Duration = java.time.Duration.ofHours(1),
    )
}
