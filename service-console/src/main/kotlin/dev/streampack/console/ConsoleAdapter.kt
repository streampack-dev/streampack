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
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.messaging.support.MessageBuilder
import org.springframework.stereotype.Component

/**
 * Interactive console adapter that reads stdin and fires messages into the event system.
 *
 * Input is fire-and-forget: the adapter publishes to ingress and does not wait for a result. Output
 * arrives via [ConsoleEgressSubscriber] watching the egress channel.
 */
@Component
@ConditionalOnProperty("streampack.console.enabled", havingValue = "true")
class ConsoleAdapter(
    private val eventGateway: EventGateway,
    private val userRepository: UserRepository,
) : ApplicationRunner, ProtocolAdapter {
    override val protocol: Protocol = Protocol.CONSOLE
    override val serviceName: String = "console"

    override fun wouldTriggerIngress(text: String): Boolean = false

    override fun sendReply(provenance: Provenance, text: String) {
        // ConsoleEgressSubscriber handles output delivery
    }

    private val logger = LoggerFactory.getLogger(ConsoleAdapter::class.java)

    override fun run(args: ApplicationArguments) {
        val principal = resolveSuperAdmin()
        if (principal == null) {
            logger.error("No superadmin user found, console cannot start")
            return
        }
        logger.info("Console started as user '{}'", principal.username)

        val provenance =
            Provenance(
                protocol = Protocol.CONSOLE,
                serviceId = "",
                replyTo = "local",
                user = principal,
            )

        val reader = BufferedReader(InputStreamReader(System.`in`))
        while (true) {
            print("> ")
            System.out.flush()
            val line = reader.readLine() ?: break
            if (line.isBlank()) continue
            if (line == "exit" || line == "quit") break

            val message =
                MessageBuilder.withPayload(line)
                    .setHeader(Provenance.HEADER, provenance)
                    .setHeader(Provenance.ADDRESSED, true)
                    .build()

            eventGateway.send(message)
        }
    }

    private fun resolveSuperAdmin(): UserPrincipal? {
        return userRepository.findActiveByRole(Role.SUPER_ADMIN).firstOrNull()?.toUserPrincipal()
    }
}
