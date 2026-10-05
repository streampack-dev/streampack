/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.webconsole

import dev.streampack.core.integration.EgressSubscriber
import dev.streampack.core.model.OperationResult
import dev.streampack.core.model.Protocol
import dev.streampack.core.model.Provenance
import dev.streampack.core.service.ProtocolAdapter
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * The one way output reaches a web console (#115): by its destination,
 * `webconsole://web/users/<id>`, whoever sent it. A command's results carry its correlation id;
 * output nobody asked for here (a poller's, or a `tell` from elsewhere) has none. The destination's
 * owner must be an admin now, or the output is dropped; nothing is sent to anyone else's console,
 * whatever the provenance says.
 */
@Component
class WebConsoleDelivery(
    private val streams: WebConsoleStreams,
    private val access: WebConsoleAccess,
) {
    private val logger = LoggerFactory.getLogger(WebConsoleDelivery::class.java)

    fun deliver(provenance: Provenance, result: WebConsoleResult) {
        if (provenance.protocol != Protocol.WEBCONSOLE) return
        if (provenance.serviceId != WebConsoleAddress.SERVICE) return
        val owner = WebConsoleAddress.owner(provenance.replyTo)
        if (owner == null) {
            logger.debug(
                "Dropping web console output to a malformed address {}",
                provenance.replyTo,
            )
            return
        }
        if (streams.count(owner) == 0) return
        if (access.currentAdmin(owner) == null) {
            logger.debug("Dropping web console output to {}, who isn't an admin now", owner)
            return
        }
        streams.publish(owner, result)
    }

    /** [result] as a stream event, answering [provenance]'s command if it carries one. */
    fun event(result: OperationResult, provenance: Provenance): WebConsoleResult =
        when (result) {
            is OperationResult.Success ->
                WebConsoleResult(provenance.correlationId, "success", result.payload.toString())
            is OperationResult.Error ->
                WebConsoleResult(provenance.correlationId, "error", result.message)
            is OperationResult.NotHandled -> WebConsoleResult(provenance.correlationId, "unhandled")
        }
}

/**
 * Web console output from the egress channel. Reference tokens (`{{ref:name}}`) are rendered by
 * [EgressSubscriber] before delivery, as bare names: the console takes commands without a prefix.
 * Unhandled results are delivered too, so a command that matched nothing says so.
 */
@Component
class WebConsoleEgressSubscriber(private val delivery: WebConsoleDelivery) : EgressSubscriber() {
    override fun matches(provenance: Provenance): Boolean =
        provenance.protocol == Protocol.WEBCONSOLE &&
            provenance.serviceId == WebConsoleAddress.SERVICE

    override fun deliver(result: OperationResult, provenance: Provenance) {
        delivery.deliver(provenance, delivery.event(result, provenance))
    }
}

/**
 * The web console as a protocol adapter, so `/features` lists it and anything that replies through
 * an adapter reaches the console the way egress does.
 */
@Component
class WebConsoleAdapter(private val delivery: WebConsoleDelivery) : ProtocolAdapter {
    override val protocol: Protocol = Protocol.WEBCONSOLE
    override val serviceName: String = WebConsoleAddress.SERVICE

    override fun wouldTriggerIngress(text: String): Boolean = false

    override fun sendReply(provenance: Provenance, text: String) {
        delivery.deliver(provenance, WebConsoleResult(provenance.correlationId, "success", text))
    }
}
