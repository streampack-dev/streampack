/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.integration

import com.enigmastation.streampack.core.model.Protocol
import com.enigmastation.streampack.core.model.Provenance
import org.springframework.integration.core.MessageSelector
import org.springframework.messaging.Message

/**
 * Selects messages whose Provenance header matches the target protocol and, optionally, serviceId
 */
class ProvenanceMessageSelector(
    private val protocol: Protocol,
    private val serviceId: String? = null,
) : MessageSelector {
    override fun accept(message: Message<*>): Boolean {
        val provenance = message.headers[Provenance.HEADER] as? Provenance ?: return false
        if (provenance.protocol != protocol) return false
        if (serviceId != null && provenance.serviceId != serviceId) return false
        return true
    }
}
