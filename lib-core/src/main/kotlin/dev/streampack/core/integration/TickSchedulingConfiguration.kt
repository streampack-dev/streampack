/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.integration

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling

/**
 * Enables Spring scheduling for tick emission. Gated on `streampack.tick.scheduler.enabled`
 * (defaults to true). Tests set this to false and publish ticks manually to the tickChannel bean.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty("streampack.tick.scheduler.enabled", matchIfMissing = true)
class TickSchedulingConfiguration
