/* Joseph B. Ottinger (C)2026 */
package dev.streampack.github.service

import dev.streampack.core.service.SecretPlaceholders
import dev.streampack.core.service.SilentStartupException
import dev.streampack.github.config.GitHubProperties
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.springframework.stereotype.Component

/**
 * Encrypts and decrypts webhook secrets for at-rest storage.
 *
 * The AES key is derived from `streampack.github.webhook-secret-key`. A placeholder value such as
 * `change-me` is rejected at startup: a public key means anyone with database access can decrypt
 * every stored webhook secret and forge deliveries (issue #47). A blank value is tolerated so that
 * deployments not using GitHub webhooks still start; encrypt and decrypt then fail with a clear
 * message, and [GitHubWebhookAdminService] reports that the key must be configured.
 */
@Component
class WebhookSecretCipher(properties: GitHubProperties) {

    private val secureRandom = SecureRandom()
    private val key: SecretKey?

    init {
        val configured = properties.webhookSecretKey.trim()
        if (SecretPlaceholders.isPlaceholder(configured)) {
            System.err.println("============================================================")
            System.err.println("SECURITY STARTUP CHECK FAILED (GitHub webhook secret key)")
            System.err.println("============================================================")
            System.err.println(
                "- streampack.github.webhook-secret-key (GITHUB_WEBHOOK_SECRET_KEY) is set to the " +
                    "placeholder '$configured'. Stored webhook secrets would be decryptable by anyone."
            )
            System.err.println(
                "Set GITHUB_WEBHOOK_SECRET_KEY to a long random value, or leave it unset"
            )
            System.err.println("if GitHub webhook delivery is not used.")
            System.err.println("============================================================")
            throw SilentStartupException(
                "GitHub webhook secret key configuration rejected. See messages above."
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

    private fun requireKey(): SecretKey = key ?: throw IllegalStateException(NOT_CONFIGURED_MESSAGE)

    companion object {
        const val NOT_CONFIGURED_MESSAGE =
            "GitHub webhook delivery requires GITHUB_WEBHOOK_SECRET_KEY " +
                "(streampack.github.webhook-secret-key) to be set"
    }
}
