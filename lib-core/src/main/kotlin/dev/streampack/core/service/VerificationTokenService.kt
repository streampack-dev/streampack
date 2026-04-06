/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.service

import dev.streampack.core.config.StreampackProperties
import dev.streampack.core.entity.TokenType
import dev.streampack.core.entity.User
import dev.streampack.core.entity.VerificationToken
import dev.streampack.core.repository.VerificationTokenRepository
import java.time.Instant
import java.util.UUID
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/** Manages creation and consumption of verification tokens for email flows */
@Service
class VerificationTokenService(
    private val verificationTokenRepository: VerificationTokenRepository,
    properties: StreampackProperties,
) {
    private val emailVerificationHours = properties.token.emailVerificationHours
    private val logger = LoggerFactory.getLogger(VerificationTokenService::class.java)

    /** Creates a new token for the given user and type */
    @Transactional
    fun createToken(user: User, type: TokenType): VerificationToken {
        val token =
            VerificationToken(
                user = user,
                token = UUID.randomUUID().toString(),
                tokenType = type,
                expiresAt = Instant.now().plusSeconds(emailVerificationHours * 3600),
                createdAt = Instant.now(),
            )
        logger.debug("Created {} token for user {}", type, user.username)
        return verificationTokenRepository.saveAndFlush(token)
    }

    /** Validates and consumes a token, returning the associated user or null if invalid */
    @Transactional
    fun consumeToken(tokenString: String, expectedType: TokenType): User? {
        val token =
            verificationTokenRepository.findByToken(tokenString)
                ?: run {
                    logger.debug("Token not found: {}", tokenString)
                    return null
                }

        if (token.tokenType != expectedType) {
            logger.debug("Token type mismatch: expected {}, got {}", expectedType, token.tokenType)
            return null
        }

        if (!token.isValid()) {
            logger.debug("Token is expired or already used: {}", tokenString)
            return null
        }

        verificationTokenRepository.saveAndFlush(token.copy(usedAt = Instant.now()))
        logger.debug("Consumed {} token for user {}", expectedType, token.user.username)
        return token.user
    }
}
