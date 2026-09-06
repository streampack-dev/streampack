/* Joseph B. Ottinger (C)2026 */
package dev.streampack.forge.service

import java.time.Duration

/**
 * The due-batch settings a forge module passes from its properties: every [schedulerInterval] the
 * poller takes [batchSize] projects; a polled project is next due [pollInterval] later, or later
 * still after failures, up to [maxBackoff].
 */
data class ForgePollingSchedule(
    val pollInterval: Duration,
    val schedulerInterval: Duration = Duration.ofSeconds(90),
    val batchSize: Int = 5,
    val maxBackoff: Duration = Duration.ofDays(1),
)
