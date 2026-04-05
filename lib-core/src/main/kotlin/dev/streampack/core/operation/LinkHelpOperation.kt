/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.operation

import com.enigmastation.streampack.core.extensions.compress
import com.enigmastation.streampack.core.model.OperationOutcome
import com.enigmastation.streampack.core.model.OperationResult
import com.enigmastation.streampack.core.model.Protocol
import com.enigmastation.streampack.core.model.Provenance
import com.enigmastation.streampack.core.model.Role
import com.enigmastation.streampack.core.service.IdentityProvider
import com.enigmastation.streampack.core.service.TypedOperation
import org.springframework.messaging.Message
import org.springframework.stereotype.Component

/** Shows available protocols and their identity field descriptions for admin discovery */
@Component
class LinkHelpOperation(private val identityProviders: List<IdentityProvider>) :
    TypedOperation<String>(String::class) {

    override val priority = 49

    override fun canHandle(payload: String, message: Message<*>): Boolean =
        payload.compress().lowercase().startsWith("link help")

    override fun handle(payload: String, message: Message<*>): OperationOutcome {
        val provenance =
            message.headers[Provenance.HEADER] as? Provenance
                ?: return OperationResult.Error("No provenance")
        val principal = provenance.user ?: return OperationResult.Error("Not authenticated")

        if (principal.role != Role.SUPER_ADMIN) {
            return OperationResult.Error("Insufficient privileges")
        }

        val text = payload.compress()
        val parts = text.split(" ")

        // "link help <protocol>" - filter to specific protocol
        if (parts.size >= 3) {
            val protocolName = parts[2]
            val protocol =
                try {
                    Protocol.valueOf(protocolName.uppercase())
                } catch (_: IllegalArgumentException) {
                    return OperationResult.Error("Unknown protocol: $protocolName")
                }

            val provider =
                identityProviders.find { it.protocol == protocol }
                    ?: return OperationResult.Error("No identity provider for ${protocol.name}")

            return OperationResult.Success(formatProvider(provider))
        }

        // "link help" - list all protocols
        if (identityProviders.isEmpty()) {
            return OperationResult.Success("No identity providers registered.")
        }

        val lines = buildString {
            append("Available protocols for identity binding:")
            for (provider in identityProviders.sortedBy { it.protocol.name }) {
                append("\n")
                append(formatProvider(provider).prependIndent("  "))
            }
        }

        return OperationResult.Success(lines)
    }

    private fun formatProvider(provider: IdentityProvider): String {
        val desc = provider.describeIdentity()
        val protocolLower = desc.protocol.name.lowercase()
        val services =
            if (desc.availableServices.isEmpty()) "(none registered)"
            else desc.availableServices.joinToString(", ")
        val servicesLabel = desc.serviceIdLabel.replaceFirstChar { it.uppercase() } + "s"

        return buildString {
            append("${desc.protocol.name}: link user <username> $protocolLower")
            append(" <${desc.serviceIdLabel}> <${desc.externalIdLabel}>")
            append("\n  $servicesLabel: $services")
        }
    }
}
