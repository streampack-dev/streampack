/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.config

import dev.streampack.core.service.OperationConfigService
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

/**
 * Seeds service enablement configs from environment properties on first startup.
 *
 * Runs after Flyway migrations and JPA are ready. Only writes if no service:* rows exist yet, so
 * the DB matches the initial environment state.
 */
@Component
class OperationConfigSeeder(private val configService: OperationConfigService) {

    @EventListener(ApplicationReadyEvent::class)
    fun seed() {
        configService.seedServiceConfigs(listOf("irc", "slack", "discord", "console", "ai"))
    }
}
