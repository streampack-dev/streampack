/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.operation

import dev.streampack.core.entity.ServiceBinding
import dev.streampack.core.entity.User
import dev.streampack.core.model.OperationOutcome
import dev.streampack.core.model.OperationResult
import dev.streampack.core.model.Role
import dev.streampack.core.model.UserPrincipal

/** How the user commands name things, so `list users`, `show user` and `whoami` agree */
internal object UserCommands {
    /** `super_admin`, as `create user` and `alter user` take it */
    fun role(role: Role): String = role.name.lowercase()

    /** The roles, lowest first, for an error that lists them */
    val roles: String = Role.entries.joinToString(", ") { role(it) }

    /** A role as typed (`admin`, `super-admin`), or null for one that doesn't exist */
    fun parseRole(text: String): Role? =
        Role.entries.firstOrNull { it.name == text.uppercase().replace("-", "_") }

    fun state(user: User): String = user.status.name.lowercase()

    /**
     * A text command's answer, in words: `<verb> dana (Dana Kim), admin.` in place of the
     * [UserPrincipal] a typed (HTTP) request gets back. Anything else passes through.
     */
    fun said(outcome: OperationOutcome?, verb: String, suffix: String = ""): OperationOutcome? {
        val user =
            (outcome as? OperationResult.Success)?.payload as? UserPrincipal ?: return outcome
        return OperationResult.Success(
            "$verb ${user.username} (${user.displayName}), ${role(user.role)}.$suffix",
        )
    }

    /** `<username> (<display name>)` */
    fun name(user: User): String = "${user.username} (${user.displayName})"

    /** A binding as `link user` and `unlink user` take it: `mattermost firenet 8xk3…` */
    fun identity(binding: ServiceBinding): String =
        listOf(
                binding.protocol.name.lowercase(),
                binding.serviceId.ifEmpty { "(none)" },
                binding.externalIdentifier,
            )
            .joinToString(" ")
}
