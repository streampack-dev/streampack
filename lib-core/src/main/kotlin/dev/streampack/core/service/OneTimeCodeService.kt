/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.service

import com.enigmastation.streampack.core.config.StreampackProperties
import com.enigmastation.streampack.core.entity.OneTimeCode
import com.enigmastation.streampack.core.repository.OneTimeCodeRepository
import java.security.SecureRandom
import java.time.Instant
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/** Generates and validates one-time passcodes for email-based authentication */
@Service
class OneTimeCodeService(
    private val oneTimeCodeRepository: OneTimeCodeRepository,
    properties: StreampackProperties,
) {
    private val logger = LoggerFactory.getLogger(OneTimeCodeService::class.java)
    private val random = SecureRandom()
    private val maxActiveCodes = properties.otp.maxActiveCodes
    private val expirationMinutes = properties.otp.expirationMinutes

    /** Generates a 6-digit code for the given email, enforcing the active code limit */
    @Transactional
    fun generateCode(email: String): OneTimeCode {
        val normalizedEmail = email.lowercase()
        val now = Instant.now()
        cleanupStaleCodes(now)
        val activeCount = oneTimeCodeRepository.countActiveByEmail(normalizedEmail, now)
        if (activeCount >= maxActiveCodes) {
            throw IllegalStateException("Too many active codes for this email")
        }
        val code = random.nextInt(1_000_000).toString().padStart(6, '0')
        val otc =
            OneTimeCode(
                email = normalizedEmail,
                code = code,
                expiresAt = now.plusSeconds(expirationMinutes * 60L),
            )
        logger.debug("Generated OTP code for email {}", normalizedEmail)
        return oneTimeCodeRepository.saveAndFlush(otc)
    }

    /** Validates and consumes a code, returning true if the code was valid */
    @Transactional
    fun consumeCode(email: String, code: String): Boolean {
        val normalizedEmail = email.lowercase()
        val now = Instant.now()
        oneTimeCodeRepository.deleteStaleByEmail(normalizedEmail, now)
        val consumed = oneTimeCodeRepository.consumeValidCode(normalizedEmail, code, now) > 0
        if (!consumed) {
            oneTimeCodeRepository.deleteStaleByEmail(normalizedEmail, now)
        }
        return consumed
    }

    /** Opportunistic cleanup for used and expired rows to reduce retention. */
    @Transactional
    fun cleanupStaleCodes(now: Instant = Instant.now()): Int {
        val deleted = oneTimeCodeRepository.deleteStale(now)
        if (deleted > 0) {
            logger.debug("Deleted {} stale OTP rows", deleted)
        }
        return deleted
    }
}
