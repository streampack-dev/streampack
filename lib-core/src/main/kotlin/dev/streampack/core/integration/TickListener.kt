/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.integration

import java.time.Instant

/**
 * Implemented by beans that need periodic heartbeat notifications. The tick channel delivers
 * 1-second pulses; listeners decide internally what intervals matter to them (e.g., poll every 5
 * minutes by comparing the tick instant against their last action time).
 */
interface TickListener {
    fun onTick(now: Instant)
}
