/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.service

import com.enigmastation.streampack.core.model.ThrottlePolicy
import java.time.Duration
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class ThrottleServiceTests {

    private lateinit var throttleService: ThrottleService

    @BeforeEach
    fun setup() {
        throttleService = ThrottleService()
    }

    @Test
    fun `allows requests up to the limit`() {
        val policy = ThrottlePolicy(3, Duration.ofHours(1))
        assertTrue(throttleService.tryAcquire("test:channel", policy))
        assertTrue(throttleService.tryAcquire("test:channel", policy))
        assertTrue(throttleService.tryAcquire("test:channel", policy))
    }

    @Test
    fun `rejects requests beyond the limit`() {
        val policy = ThrottlePolicy(2, Duration.ofHours(1))
        assertTrue(throttleService.tryAcquire("test:channel", policy))
        assertTrue(throttleService.tryAcquire("test:channel", policy))
        assertFalse(throttleService.tryAcquire("test:channel", policy))
    }

    @Test
    fun `different keys are independent`() {
        val policy = ThrottlePolicy(1, Duration.ofHours(1))
        assertTrue(throttleService.tryAcquire("op:channelA", policy))
        assertFalse(throttleService.tryAcquire("op:channelA", policy))
        assertTrue(throttleService.tryAcquire("op:channelB", policy))
    }

    @Test
    fun `clear resets all buckets`() {
        val policy = ThrottlePolicy(1, Duration.ofHours(1))
        assertTrue(throttleService.tryAcquire("test:key", policy))
        assertFalse(throttleService.tryAcquire("test:key", policy))
        throttleService.clear()
        assertTrue(throttleService.tryAcquire("test:key", policy))
    }

    @Test
    fun `token replenishment allows requests after time passes`() {
        val policy = ThrottlePolicy(1, Duration.ofNanos(1))
        assertTrue(throttleService.tryAcquire("test:fast", policy))
        Thread.sleep(1)
        assertTrue(
            throttleService.tryAcquire("test:fast", policy),
            "Token should have replenished after the window elapsed",
        )
    }
}
