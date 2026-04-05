/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.service

import com.enigmastation.streampack.core.config.StreampackProperties
import com.enigmastation.streampack.core.entity.RefreshToken
import com.enigmastation.streampack.core.repository.RefreshTokenRepository
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.UUID
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/** Manages opaque refresh tokens for persistent session authentication */
@Service
class RefreshTokenService(
    private val refreshTokenRepository: RefreshTokenRepository,
    properties: StreampackProperties,
) {
    private val logger = LoggerFactory.getLogger(RefreshTokenService::class.java)
    private val random = SecureRandom()
    private val ttlDays = properties.refreshToken.days

    /** Issues a new refresh token for the given user, returning the raw (unhashed) token */
    @Transactional
    fun issueToken(userId: UUID): String {
        cleanupExpired()
        val rawToken = generateOpaqueToken()
        val hash = sha256(rawToken)
        val entity =
            RefreshToken(
                userId = userId,
                tokenHash = hash,
                expiresAt = Instant.now().plus(Duration.ofDays(ttlDays)),
            )
        refreshTokenRepository.saveAndFlush(entity)
        return rawToken
    }

    /** Validates and rotates a refresh token, returning the userId and a new raw token */
    @Transactional
    fun rotateToken(rawToken: String): Pair<UUID, String>? {
        val hash = sha256(rawToken)
        val existing = refreshTokenRepository.findByTokenHash(hash) ?: return null
        if (Instant.now().isAfter(existing.expiresAt)) {
            refreshTokenRepository.delete(existing)
            return null
        }
        refreshTokenRepository.delete(existing)
        val newRaw = generateOpaqueToken()
        val newHash = sha256(newRaw)
        val newEntity =
            RefreshToken(
                userId = existing.userId,
                tokenHash = newHash,
                expiresAt = Instant.now().plus(Duration.ofDays(ttlDays)),
            )
        refreshTokenRepository.saveAndFlush(newEntity)
        return existing.userId to newRaw
    }

    /** Revokes all refresh tokens for a user (for logout or account erasure) */
    @Transactional
    fun revokeAllForUser(userId: UUID): Int {
        val deleted = refreshTokenRepository.deleteByUserId(userId)
        if (deleted > 0) {
            logger.debug("Revoked {} refresh tokens for user {}", deleted, userId)
        }
        return deleted
    }

    /** Opportunistic cleanup of expired refresh tokens */
    @Transactional
    fun cleanupExpired(now: Instant = Instant.now()): Int {
        val deleted = refreshTokenRepository.deleteExpired(now)
        if (deleted > 0) {
            logger.debug("Deleted {} expired refresh tokens", deleted)
        }
        return deleted
    }

    private fun generateOpaqueToken(): String {
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    companion object {
        fun sha256(input: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
            return digest.digest(input.toByteArray()).joinToString("") { "%02x".format(it) }
        }
    }
}
