/* Joseph B. Ottinger (C)2026 */
package dev.streampack.console

import dev.streampack.core.integration.EventGateway
import dev.streampack.core.model.Protocol
import dev.streampack.core.model.Provenance
import dev.streampack.core.model.Role
import dev.streampack.core.model.UserPrincipal
import dev.streampack.core.repository.UserRepository
import dev.streampack.core.service.ProtocolAdapter
import java.io.BufferedReader
import java.io.InputStreamReader
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

/**
 * Interactive console adapter that reads stdin and fires messages into the event system.
 *
 * It starts once the application is ready, on a thread of its own, so it never holds up startup:
 * the super-admin bootstrap and the other startup runners have run by then, and readiness is
 * published without waiting for the console to end (#209). The read loop is [ConsoleSession].
 */
@Component
@ConditionalOnProperty("streampack.console.enabled", havingValue = "true")
class ConsoleAdapter(
    private val eventGateway: EventGateway,
    private val userRepository: UserRepository,
) : ProtocolAdapter {
    override val protocol: Protocol = Protocol.CONSOLE
    override val serviceName: String = "console"

    override fun wouldTriggerIngress(text: String): Boolean = false

    override fun sendReply(provenance: Provenance, text: String) {
        // ConsoleEgressSubscriber handles output delivery
    }

    private val logger = LoggerFactory.getLogger(ConsoleAdapter::class.java)

    @EventListener(ApplicationReadyEvent::class)
    fun start() {
        // A daemon, so a console waiting on input never keeps the JVM from shutting down
        Thread.ofPlatform().name("console").daemon(true).start(::session)
    }

    private fun session() {
        val principal = resolveSuperAdmin()
        if (principal != null) logger.info("Console started as user '{}'", principal.username)
        val ending =
            ConsoleSession(eventGateway)
                .run(principal, BufferedReader(InputStreamReader(System.`in`)), System.out)
        when (ending) {
            ConsoleSession.Ending.NO_SUPER_ADMIN,
            ConsoleSession.Ending.NO_INPUT -> logger.warn(ending.explanation)
            else -> logger.info(ending.explanation)
        }
    }

    private fun resolveSuperAdmin(): UserPrincipal? {
        return userRepository.findActiveByRole(Role.SUPER_ADMIN).firstOrNull()?.toUserPrincipal()
    }
}
