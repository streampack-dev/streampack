/* Joseph B. Ottinger (C)2026 */
package dev.streampack.console

import dev.streampack.core.integration.EgressSubscriber
import dev.streampack.core.model.OperationResult
import dev.streampack.core.model.Protocol
import dev.streampack.core.model.Provenance
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component

/** Watches the egress channel for console-bound results and prints them to stdout */
@Component
@ConditionalOnProperty("streampack.console.enabled", havingValue = "true")
class ConsoleEgressSubscriber : EgressSubscriber() {

    override fun matches(provenance: Provenance): Boolean = provenance.protocol == Protocol.CONSOLE

    override fun deliver(result: OperationResult, provenance: Provenance) {
        when (result) {
            is OperationResult.Success -> println(result.payload)
            is OperationResult.Error -> println("ERROR: ${result.message}")
            is OperationResult.NotHandled -> {} // silence for unhandled messages
        }
        System.out.flush()
    }
}
