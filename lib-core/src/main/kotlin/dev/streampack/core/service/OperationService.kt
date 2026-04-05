/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.service

import com.enigmastation.streampack.core.config.StreampackProperties
import com.enigmastation.streampack.core.integration.EventGateway
import com.enigmastation.streampack.core.model.Declined
import com.enigmastation.streampack.core.model.FanOut
import com.enigmastation.streampack.core.model.LoggingRequest
import com.enigmastation.streampack.core.model.OperationOutcome
import com.enigmastation.streampack.core.model.OperationResult
import com.enigmastation.streampack.core.model.Provenance
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Lazy
import org.springframework.integration.annotation.ServiceActivator
import org.springframework.messaging.Message
import org.springframework.messaging.MessageChannel
import org.springframework.messaging.support.MessageBuilder
import org.springframework.stereotype.Service

/**
 * Orchestrates the global operation chain.
 *
 * Collects all [Operation] beans, sorts them by priority, and runs them against each incoming
 * message. The first operation to return a terminal [OperationResult] wins -- the chain
 * short-circuits and that result is returned to the caller via the egress path.
 *
 * [Declined] results are consumed here (logged with operation context) and the chain continues. If
 * no operation handles the message, [OperationResult.NotHandled] is returned.
 */
@Service
class OperationService(
    operations: List<Operation>,
    private val properties: StreampackProperties,
    @Lazy private val eventGateway: EventGateway,
    @Qualifier("egressChannel") private val egressChannel: MessageChannel,
    private val throttleService: ThrottleService,
    private val operationConfigService: OperationConfigService,
    private val transformerChain: TransformerChainService,
) {
    private val logger = LoggerFactory.getLogger(OperationService::class.java)
    private val sortedOperations = operations.sortedBy { it.priority }
    private val watchdogScheduler: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().factory())

    /**
     * Checks whether any non-addressed operation is interested in this message.
     *
     * Protocol adapters with trigger detection (e.g. IRC) call this before submitting unaddressed
     * messages. If no non-addressed operation claims interest, the message is dropped silently.
     */
    fun hasUnaddressedInterest(message: Message<*>): Boolean =
        sortedOperations.any {
            !it.addressed && isGroupEnabled(it, message) && it.canHandle(message)
        }

    /** Receives from the ingress channel and returns the result to the gateway's reply channel */
    @ServiceActivator(inputChannel = "ingressChannel")
    fun process(message: Message<*>): OperationResult {
        val hopCount = message.headers[FanOut.HOP_COUNT_HEADER] as? Int ?: 0
        val provenance = message.headers[Provenance.HEADER] as? Provenance
        val result = processChain(message)
        val transformed =
            if (provenance != null) transformerChain.apply(result, provenance) else result
        publishToEgress(transformed, message, hopCount)
        return transformed
    }

    /** Runs the message through the operation chain and returns the result */
    private fun processChain(message: Message<*>): OperationResult {
        if (message.payload is LoggingRequest) {
            return OperationResult.NotHandled
        }

        val hopCount = message.headers[FanOut.HOP_COUNT_HEADER] as? Int ?: 0
        if (hopCount > properties.maxHops) {
            logger.warn(
                "Message {} exceeded maximum hop count ({}/{})",
                message.headers.id,
                hopCount,
                properties.maxHops,
            )
            return OperationResult.Error("Maximum hop count exceeded")
        }

        val isAddressed = message.headers[Provenance.ADDRESSED] as? Boolean ?: true

        for (op in sortedOperations) {
            if (op.addressed && !isAddressed) continue
            if (!isGroupEnabled(op, message)) continue
            if (op.canHandle(message)) {
                if (op.throttlePolicy != null && !tryThrottle(op, message)) {
                    logger.info(
                        "Operation {} throttled for message {}",
                        op::class.simpleName,
                        message.headers.id,
                    )
                    continue
                }
                logger.debug(
                    "Operation {} handling message {}",
                    op::class.simpleName,
                    message.headers.id,
                )
                val result = executeWithTimeout(op, message)
                if (result == null) {
                    logger.debug(
                        "Operation {} returned null, continuing chain",
                        op::class.simpleName,
                    )
                    continue
                }
                if (result is Declined) {
                    logger.info(
                        "Operation {} declined message {}: {}",
                        op::class.simpleName,
                        message.headers.id,
                        result.reason,
                    )
                    continue
                }
                if (result is FanOut) {
                    logger.debug(
                        "Operation {} produced FanOut with {} messages",
                        op::class.simpleName,
                        result.messages.size,
                    )
                    return dispatchFanOut(result, hopCount)
                }
                if (result is OperationResult) {
                    logger.debug(
                        "Operation {} produced {}",
                        op::class.simpleName,
                        result::class.simpleName,
                    )
                    if (result is OperationResult.Success) {
                        logger.debug("Message {} generated {}", message.headers.id, result.payload)
                    }
                    return result
                }
            }
        }
        logger.debug("No operation handled message {}", message.headers.id)
        return OperationResult.NotHandled
    }

    /** Publishes a terminal result to the egress channel, then re-injects if loopback is set */
    private fun publishToEgress(result: OperationResult, inputMessage: Message<*>, hopCount: Int) {
        val inputProvenance = inputMessage.headers[Provenance.HEADER] as? Provenance ?: return
        val provenance =
            if (result is OperationResult.Success && result.provenance != null) {
                result.provenance
            } else {
                inputProvenance
            }
        val egressMessage =
            MessageBuilder.withPayload(result as Any)
                .setHeader(Provenance.HEADER, provenance)
                .build()
        try {
            egressChannel.send(egressMessage)
        } catch (e: Exception) {
            logger.warn("Failed to publish to egress channel: {}", e.message)
        }

        if (result is OperationResult.Success && result.loopback && hopCount < properties.maxHops) {
            val loopbackMessage =
                MessageBuilder.withPayload(result.payload.toString())
                    .setHeader(Provenance.HEADER, provenance)
                    .setHeader(Provenance.ADDRESSED, true)
                    .setHeader(FanOut.HOP_COUNT_HEADER, hopCount + 1)
                    .build()
            try {
                logger.debug(
                    "Loopback: re-injecting result as addressed message (hop {})",
                    hopCount + 1,
                )
                eventGateway.send(loopbackMessage)
            } catch (e: Exception) {
                logger.warn("Failed to re-inject loopback message: {}", e.message)
            }
        }
    }

    /** Returns true if the operation's group is enabled for the message's provenance */
    private fun isGroupEnabled(op: Operation, message: Message<*>): Boolean {
        val group = op.operationGroup ?: return true
        val provenance = message.headers[Provenance.HEADER] as? Provenance
        val provenanceUri = provenance?.encode() ?: ""
        return operationConfigService.isOperationEnabled(provenanceUri, group)
    }

    /**
     * Checks the token bucket for a throttled operation. Returns true if the request is allowed.
     */
    private fun tryThrottle(op: Operation, message: Message<*>): Boolean {
        val policy = op.throttlePolicy ?: return true
        val provenance = message.headers[Provenance.HEADER] as? Provenance
        val provenanceUri = provenance?.encode() ?: "unknown"
        val key = "${op::class.simpleName}:$provenanceUri"
        return throttleService.tryAcquire(key, policy)
    }

    /**
     * Executes an operation on the current thread with a scheduled watchdog interrupt. Keeping
     * execution on the caller's thread preserves transaction context and ThreadLocal state. On
     * timeout the watchdog interrupts the operation thread; blocking I/O and Thread.sleep on
     * virtual threads respond to interruption.
     */
    private fun executeWithTimeout(op: Operation, message: Message<*>): OperationOutcome? {
        val caller = Thread.currentThread()
        val watchdog =
            watchdogScheduler.schedule(
                { caller.interrupt() },
                op.timeout.toMillis(),
                TimeUnit.MILLISECONDS,
            )
        return try {
            val result = op.execute(message)
            watchdog.cancel(false)
            Thread.interrupted() // clear any interrupt that raced with completion
            result
        } catch (e: InterruptedException) {
            watchdog.cancel(false)
            Thread.interrupted()
            logger.warn(
                "Operation {} timed out after {} for message {}",
                op::class.simpleName,
                op.timeout,
                message.headers.id,
            )
            null
        } catch (e: Exception) {
            watchdog.cancel(false)
            Thread.interrupted()
            throw e
        }
    }

    /** Dispatches each child message with an incremented hop count */
    private fun dispatchFanOut(fanOut: FanOut, currentHopCount: Int): OperationResult {
        var dispatched = 0
        for (child in fanOut.messages) {
            val enriched =
                MessageBuilder.fromMessage(child)
                    .setHeader(FanOut.HOP_COUNT_HEADER, currentHopCount + 1)
                    .build()
            try {
                eventGateway.process(enriched)
                dispatched++
            } catch (e: Exception) {
                logger.warn("Fan-out child message {} failed: {}", child.headers.id, e.message)
            }
        }
        return OperationResult.Success("Dispatched $dispatched messages")
    }
}
