/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.operation

import dev.streampack.core.model.OperationOutcome
import dev.streampack.core.model.OperationResult
import dev.streampack.core.model.Protocol
import dev.streampack.core.model.Provenance
import dev.streampack.core.model.ServiceIdentity
import dev.streampack.core.repository.ServiceBindingRepository
import dev.streampack.core.service.DirectConversations
import dev.streampack.core.service.TypedOperation
import org.springframework.messaging.Message
import org.springframework.stereotype.Component

/**
 * `whoami`: the account you're using and its role; when you aren't linked, the identity this
 * protocol knows you by, as the `link user` line an admin would type (#247). For anyone, signed in
 * or not. In a channel it asks for a direct message, so nobody's role or identifier is announced.
 */
@Component
class WhoAmIOperation(
    private val serviceBindingRepository: ServiceBindingRepository,
    private val directConversations: DirectConversations,
) : TypedOperation<String>(String::class) {

    override fun canHandle(payload: String, message: Message<*>): Boolean =
        payload.trim().lowercase().replace(WHITESPACE, " ") in COMMANDS

    override fun handle(payload: String, message: Message<*>): OperationOutcome {
        val provenance =
            message.headers[Provenance.HEADER] as? Provenance
                ?: return OperationResult.Error("No provenance")
        val identity = message.headers[Provenance.IDENTITY] as? ServiceIdentity
        if (identity != null && !directConversations.isDirect(provenance)) {
            return OperationResult.Success("Ask me that in a direct message.")
        }
        val protocol = provenance.protocol.name.lowercase()

        provenance.user?.let { user ->
            val role = UserCommands.role(user.role)
            val via = identity?.let { " Linked as $protocol ${linkable(it)}." } ?: ""
            return OperationResult.Success(
                "You're ${user.username} (${user.displayName}), $role.$via",
            )
        }
        identity
            ?: return OperationResult.Success(
                "You're not signed in. Sign in, or link this identity to an account.",
            )

        val binding =
            serviceBindingRepository.resolve(
                provenance.protocol,
                identity.serviceId,
                identity.externalIdentifier,
            )
        if (binding != null) {
            return OperationResult.Success(
                "This identity is linked to ${binding.user.username}, whose account is " +
                    "${binding.user.status.name.lowercase()}.",
            )
        }
        if (provenance.protocol == Protocol.DISCORD && identity.serviceId.isEmpty()) {
            return OperationResult.Success(
                "You're not linked to an account. Your Discord user id is " +
                    "${identity.externalIdentifier}; an admin links it for each server: " +
                    "link user <username> discord <server id> ${identity.externalIdentifier}",
            )
        }
        return OperationResult.Success(
            "You're not linked to an account. An admin can link you with: " +
                "link user <username> $protocol ${linkable(identity)}",
        )
    }

    private fun linkable(identity: ServiceIdentity): String =
        "${identity.serviceId.ifEmpty { "(none)" }} ${identity.externalIdentifier}"

    private companion object {
        val WHITESPACE = Regex("\\s+")
        val COMMANDS = setOf("whoami", "who am i", "who am i?")
    }
}
