/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.service

import dev.streampack.core.config.StreampackProperties
import dev.streampack.core.entity.OneTimeCode
import dev.streampack.core.model.CodeChannel
import dev.streampack.core.repository.OneTimeCodeRepository
import java.security.SecureRandom
import java.time.Instant
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Generates and validates one-time passcodes for passwordless authentication over any channel. A
 * code is scoped to the channel and recipient it was issued for; the active-code cap applies per
 * such pair. Email recipients are normalized to lowercase; other channels pass their canonical
 * recipient key as given.
 */
@Service
class OneTimeCodeService(
    private val oneTimeCodeRepository: OneTimeCodeRepository,
    properties: StreampackProperties,
) {
    private val logger = LoggerFactory.getLogger(OneTimeCodeService::class.java)
    private val random = SecureRandom()
    private val maxActiveCodes = properties.otp.maxActiveCodes
    private val expirationMinutes = properties.otp.expirationMinutes

    /** Generates a 6-digit code for an email address, enforcing the active code limit */
    @Transactional
    fun generateCode(email: String): OneTimeCode = generateCode(CodeChannel.EMAIL, email)

    /** Generates a 6-digit code for [recipient] on [channel], enforcing the active code limit */
    @Transactional
    fun generateCode(channel: CodeChannel, recipient: String): OneTimeCode {
        val key = normalize(channel, recipient)
        val now = Instant.now()
        cleanupStaleCodes(now)
        val activeCount = oneTimeCodeRepository.countActive(channel, key, now)
        if (activeCount >= maxActiveCodes) {
            throw IllegalStateException("Too many active codes for this recipient")
        }
        val code = random.nextInt(1_000_000).toString().padStart(6, '0')
        val otc =
            OneTimeCode(
                channel = channel,
                recipient = key,
                code = code,
                expiresAt = now.plusSeconds(expirationMinutes * 60L),
            )
        logger.debug("Generated OTP code for {} recipient {}", channel, key)
        return oneTimeCodeRepository.saveAndFlush(otc)
    }

    /** Validates and consumes an email code, returning true if the code was valid */
    @Transactional
    fun consumeCode(email: String, code: String): Boolean =
        consumeCode(CodeChannel.EMAIL, email, code)

    /** Validates and consumes a code for [recipient] on [channel] */
    @Transactional
    fun consumeCode(channel: CodeChannel, recipient: String, code: String): Boolean {
        val key = normalize(channel, recipient)
        val now = Instant.now()
        oneTimeCodeRepository.deleteStaleFor(channel, key, now)
        val consumed = oneTimeCodeRepository.consumeValidCode(channel, key, code, now) > 0
        if (!consumed) {
            oneTimeCodeRepository.deleteStaleFor(channel, key, now)
        }
        return consumed
    }

    /** Removes every code for a recipient, used when an account is erased */
    @Transactional
    fun forget(channel: CodeChannel, recipient: String) =
        oneTimeCodeRepository.deleteByChannelAndRecipient(channel, normalize(channel, recipient))

    /** Opportunistic cleanup for used and expired rows to reduce retention. */
    @Transactional
    fun cleanupStaleCodes(now: Instant = Instant.now()): Int {
        val deleted = oneTimeCodeRepository.deleteStale(now)
        if (deleted > 0) {
            logger.debug("Deleted {} stale OTP rows", deleted)
        }
        return deleted
    }

    private fun normalize(channel: CodeChannel, recipient: String): String =
        if (channel == CodeChannel.EMAIL) recipient.trim().lowercase() else recipient.trim()
}
