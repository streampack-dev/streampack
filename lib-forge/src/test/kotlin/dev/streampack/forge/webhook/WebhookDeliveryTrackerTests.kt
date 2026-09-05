/* Joseph B. Ottinger (C)2026 */
package dev.streampack.forge.webhook

import java.time.Duration
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WebhookDeliveryTrackerTests {
    @Test
    fun `first delivery is new and a repeat is a duplicate`() {
        val tracker = WebhookDeliveryTracker(Duration.ofHours(1))
        assertFalse(tracker.isDuplicate("d-1"))
        assertTrue(tracker.isDuplicate("d-1"))
        assertFalse(tracker.isDuplicate("d-2"))
    }

    @Test
    fun `expired deliveries are forgotten`() {
        val tracker = WebhookDeliveryTracker(Duration.ZERO)
        assertFalse(tracker.isDuplicate("d-1"))
        assertFalse(tracker.isDuplicate("d-1"))
    }
}
