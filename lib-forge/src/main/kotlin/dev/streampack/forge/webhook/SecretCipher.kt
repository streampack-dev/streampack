/* Joseph B. Ottinger (C)2026 */
package dev.streampack.forge.webhook

import dev.streampack.core.service.SecretPlaceholders
import dev.streampack.core.service.SilentStartupException
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-GCM encryption of per-project webhook secrets for at-rest storage.
 *
 * Each forge module constructs one with its own configured key. A placeholder key (see
 * [SecretPlaceholders]) is rejected at startup: a public key would let anyone with database access
 * decrypt every stored webhook secret and forge deliveries (issue #47). A blank key is tolerated so
 * deployments not using webhooks for this forge still start; [encrypt] and [decrypt] then throw
 * [notConfiguredMessage].
 *
 * @param configuredKey the raw configured key; hashed with SHA-256 to derive the AES key.
 * @param propertyDescription how the key is named in configuration, for the startup banner, e.g.
 *   `streampack.github.webhook-secret-key (GITHUB_WEBHOOK_SECRET_KEY)`.
 * @param kindLabel the forge name for the startup banner.
 * @param notConfiguredMessage the message thrown when the key is blank and encryption is attempted.
 */
open class SecretCipher(
    configuredKey: String,
    propertyDescription: String,
    kindLabel: String,
    private val notConfiguredMessage: String,
) {
    private val secureRandom = SecureRandom()
    private val key: SecretKey?

    init {
        val configured = configuredKey.trim()
        if (SecretPlaceholders.isPlaceholder(configured)) {
            System.err.println("============================================================")
            System.err.println("SECURITY STARTUP CHECK FAILED ($kindLabel webhook secret key)")
            System.err.println("============================================================")
            System.err.println(
                "- $propertyDescription is set to the placeholder '$configured'. " +
                    "Stored webhook secrets would be decryptable by anyone."
            )
            System.err.println("Set it to a long random value, or leave it unset if $kindLabel")
            System.err.println("webhook delivery is not used.")
            System.err.println("============================================================")
            throw SilentStartupException(
                "$kindLabel webhook secret key configuration rejected. See messages above."
            )
        }
        key =
            if (configured.isBlank()) {
                null
            } else {
                val hash = MessageDigest.getInstance("SHA-256").digest(configured.toByteArray())
                SecretKeySpec(hash, "AES")
            }
    }

    /** Whether a key is configured; when false, [encrypt] and [decrypt] throw. */
    val isConfigured: Boolean
        get() = key != null

    fun encrypt(plaintext: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val iv = ByteArray(12).also { secureRandom.nextBytes(it) }
        cipher.init(Cipher.ENCRYPT_MODE, requireKey(), GCMParameterSpec(128, iv))
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val payload = ByteBuffer.allocate(iv.size + ciphertext.size).put(iv).put(ciphertext).array()
        return Base64.getEncoder().encodeToString(payload)
    }

    fun decrypt(encoded: String): String {
        val secretKey = requireKey()
        val payload = Base64.getDecoder().decode(encoded)
        val iv = payload.copyOfRange(0, 12)
        val ciphertext = payload.copyOfRange(12, payload.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(128, iv))
        val plaintext = cipher.doFinal(ciphertext)
        return plaintext.toString(Charsets.UTF_8)
    }

    private fun requireKey(): SecretKey = key ?: throw IllegalStateException(notConfiguredMessage)
}
