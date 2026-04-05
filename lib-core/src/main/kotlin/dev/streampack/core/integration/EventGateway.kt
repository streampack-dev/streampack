/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.integration

import com.enigmastation.streampack.core.model.OperationResult
import org.springframework.integration.annotation.Gateway
import org.springframework.integration.annotation.MessagingGateway
import org.springframework.messaging.Message

/**
 * Entry point for sending messages into the event system.
 *
 * Two modes of operation:
 * - [process]: Synchronous request-reply. Sends to ingress, blocks for the result. Use this when
 *   the caller needs the answer immediately (REST controllers, tests).
 * - [send]: Fire-and-forget. Sends to ingress, returns immediately. Results flow to the egress
 *   channel where services watch and claim messages matching their provenance. Use this for
 *   protocol adapters and background services that treat input as "dumb."
 *
 * ## Usage
 *
 * ```
 * // Synchronous (REST, tests)
 * val result = eventGateway.process(message)
 *
 * // Fire-and-forget (protocol adapters, background services)
 * eventGateway.send(message)
 * ```
 */
@MessagingGateway
interface EventGateway {
    /** Synchronous request-reply: sends to ingress and blocks for the result */
    @Gateway(requestChannel = "ingressChannel") fun process(message: Message<*>): OperationResult

    /** Fire-and-forget: sends to ingress, results arrive on the egress channel */
    @Gateway(requestChannel = "ingressChannel") fun send(message: Message<*>)
}
