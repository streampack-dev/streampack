/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.operation

import dev.streampack.core.model.OperationOutcome
import dev.streampack.core.model.OperationResult
import dev.streampack.core.model.Role
import dev.streampack.core.model.SecretRequirement
import dev.streampack.core.model.SecretRequirementsRequest
import dev.streampack.core.service.SecretRequirementSource
import dev.streampack.core.service.TranslatingOperation
import org.springframework.beans.factory.ObjectProvider
import org.springframework.messaging.Message
import org.springframework.stereotype.Component

/**
 * `secrets`: the environment variables the next start needs, from every service that stores
 * credentials. As text for the console; as the list itself for [SecretRequirementsRequest], which
 * `GET /admin/secrets` sends. Super-admin only, and never a value (#209).
 */
@Component
class SecretsOperation(private val sources: ObjectProvider<SecretRequirementSource>) :
    TranslatingOperation<SecretRequirementsRequest>(SecretRequirementsRequest::class) {

    override fun translate(payload: String, message: Message<*>): SecretRequirementsRequest? =
        if (payload.trim().equals("secrets", ignoreCase = true)) SecretRequirementsRequest else null

    override fun handle(payload: SecretRequirementsRequest, message: Message<*>): OperationOutcome {
        requireRole(message, Role.SUPER_ADMIN)?.let {
            return it
        }
        val needed =
            sources
                .orderedStream()
                .toList()
                .flatMap { it.secretRequirements() }
                .distinct()
                .sortedBy {
                    it.variable
                }
        if (message.payload !is String) return OperationResult.Success(needed)
        return OperationResult.Success(describe(needed))
    }

    private fun describe(needed: List<SecretRequirement>): String {
        if (needed.isEmpty()) return "No environment variables are needed before the next start."
        val lines = needed.map { requirement ->
            val state =
                when (requirement.state) {
                    SecretRequirement.State.STORED_IN_DATABASE ->
                        "stored in the database until it's set"
                    SecretRequirement.State.NOT_SET -> "not set"
                }
            "${requirement.variable}: ${requirement.description}, $state"
        }
        val count =
            if (needed.size == 1) "1 environment variable"
            else "${needed.size} environment variables"
        return (listOf("$count to set before the next start:") + lines).joinToString("\n")
    }
}
