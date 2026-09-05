/* Joseph B. Ottinger (C)2026 */
package dev.streampack.github.service

import dev.streampack.forge.webhook.WebhookDeliveryTracker
import dev.streampack.github.config.GitHubProperties
import org.springframework.stereotype.Service

/** Deduplicates GitHub webhook deliveries by `X-GitHub-Delivery` id */
@Service
class GitHubWebhookDeliveryTracker(properties: GitHubProperties) :
    WebhookDeliveryTracker(properties.deliveryDedupeTtl)
