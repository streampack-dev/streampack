/* Joseph B. Ottinger (C)2026 */
package dev.streampack.github.service

import dev.streampack.github.config.GitHubProperties
import java.time.Duration
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GitHubWebhookDeliveryTrackerTests {

    @Test
    fun `isDuplicate returns false on first delivery and true on repeat`() {
        val tracker =
            GitHubWebhookDeliveryTracker(
                GitHubProperties(
                    pollInterval = Duration.ofMinutes(60),
                    connectTimeoutSeconds = 5,
                    readTimeoutSeconds = 10,
                    webhookSecretKey = "test-key",
                    deliveryDedupeTtl = Duration.ofMinutes(10),
                )
            )

        assertFalse(tracker.isDuplicate("delivery-abc"))
        assertTrue(tracker.isDuplicate("delivery-abc"))
    }
}
