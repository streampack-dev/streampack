/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.service

import com.enigmastation.streampack.core.config.StreampackProperties
import com.enigmastation.streampack.core.model.Role
import com.enigmastation.streampack.core.model.UserPrincipal
import io.jsonwebtoken.Jwts
import java.security.MessageDigest
import java.util.Date
import java.util.UUID
import javax.crypto.SecretKey
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/** Creates and validates JWT tokens carrying user identity */
@Service
class JwtService(properties: StreampackProperties) {
    private val logger = LoggerFactory.getLogger(JwtService::class.java)
    private val expirationHours = properties.jwt.expirationHours
    private val key: SecretKey

    init {
        val configuredSecret = properties.jwt.secret
        key =
            if (configuredSecret.isNotBlank()) {
                val hash =
                    MessageDigest.getInstance("SHA-256").digest(configuredSecret.toByteArray())
                io.jsonwebtoken.security.Keys.hmacShaKeyFor(hash)
            } else {
                logger.warn(
                    "No JWT secret configured (streampack.jwt.secret). Using generated key - tokens will not survive restart."
                )
                Jwts.SIG.HS256.key().build()
            }
    }

    /** Generates a JWT encoding the user's identity and role */
    fun generateToken(principal: UserPrincipal): String {
        val now = Date()
        val expiration =
            if (expirationHours > 0) Date(now.time + expirationHours * 3600 * 1000)
            else Date(now.time - 1)

        return Jwts.builder()
            .subject(principal.id.toString())
            .claim("username", principal.username)
            .claim("displayName", principal.displayName)
            .claim("role", principal.role.name)
            .issuedAt(now)
            .expiration(expiration)
            .signWith(key)
            .compact()
    }

    /** Validates a JWT and extracts the UserPrincipal, returning null if invalid or expired */
    fun validateToken(token: String): UserPrincipal? {
        return try {
            val claims = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).payload

            UserPrincipal(
                id = UUID.fromString(claims.subject),
                username = claims["username"] as String,
                displayName = claims["displayName"] as String,
                role = Role.valueOf(claims["role"] as String),
            )
        } catch (e: Exception) {
            logger.debug("JWT validation failed: {}", e.message)
            null
        }
    }
}
