/* Joseph B. Ottinger (C)2026 */
package dev.streampack.github.service

import dev.streampack.forge.webhook.SecretCipher
import dev.streampack.github.config.GitHubProperties
import org.springframework.stereotype.Component

/**
 * Encrypts and decrypts GitHub webhook secrets for at-rest storage under
 * `streampack.github.webhook-secret-key`. See [SecretCipher] for the placeholder and blank-key
 * rules.
 */
@Component
class WebhookSecretCipher(properties: GitHubProperties) :
    SecretCipher(
        configuredKey = properties.webhookSecretKey,
        propertyDescription = "streampack.github.webhook-secret-key (GITHUB_WEBHOOK_SECRET_KEY)",
        kindLabel = "GitHub",
        notConfiguredMessage = NOT_CONFIGURED_MESSAGE,
    ) {
    companion object {
        const val NOT_CONFIGURED_MESSAGE =
            "GitHub webhook delivery requires GITHUB_WEBHOOK_SECRET_KEY " +
                "(streampack.github.webhook-secret-key) to be set"
    }
}
