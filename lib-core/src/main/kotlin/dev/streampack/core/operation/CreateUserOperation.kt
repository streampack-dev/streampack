/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.operation

import dev.streampack.core.model.CreateUserRequest
import dev.streampack.core.model.OperationOutcome
import dev.streampack.core.model.OperationResult
import dev.streampack.core.model.Provenance
import dev.streampack.core.model.Role
import dev.streampack.core.parser.CommandLexer
import dev.streampack.core.parser.UnbalancedQuoteException
import dev.streampack.core.service.TranslatingOperation
import dev.streampack.core.service.UserRegistrationService
import org.springframework.messaging.Message
import org.springframework.stereotype.Component

/** Provisions a new user account. Restricted to SUPER_ADMIN. */
@Component
class CreateUserOperation(private val userRegistrationService: UserRegistrationService) :
    TranslatingOperation<CreateUserRequest>(CreateUserRequest::class) {

    override val priority = 50

    /**
     * `create user <username> <email> <display-name> [role]`. Arguments may be quoted, so a display
     * name with spaces is `"Dana Kim"`; a role that doesn't exist, or a word too many, is an error
     * rather than a guess (#247).
     */
    override fun translate(payload: String, message: Message<*>): CreateUserRequest? =
        if (isCreateUser(payload)) parse(payload).getOrNull() else null

    /** A malformed `create user` is answered with what's wrong, not passed down the chain */
    override fun canHandle(message: Message<*>): Boolean =
        (message.payload as? String)?.let(::isCreateUser) ?: super.canHandle(message)

    override fun execute(message: Message<*>): OperationOutcome? {
        val text = message.payload as? String ?: return super.execute(message)
        if (!isCreateUser(text)) return null
        return parse(text)
            .fold(
                onSuccess = { UserCommands.said(handle(it, message), "Created") },
                onFailure = {
                    if (hasRole(message, Role.SUPER_ADMIN)) OperationResult.Error(it.message!!)
                    else OperationResult.Error("Insufficient privileges")
                },
            )
    }

    private fun isCreateUser(text: String): Boolean {
        val lowered = text.trim().lowercase()
        return lowered == "create user" || lowered.startsWith("create user ")
    }

    private fun parse(text: String): Result<CreateUserRequest> {
        val args =
            try {
                CommandLexer.split(text).drop(2)
            } catch (e: UnbalancedQuoteException) {
                return Result.failure(e)
            }
        if (args.size < 3) return Result.failure(IllegalArgumentException(USAGE))
        if (args.size > 4) {
            return Result.failure(
                IllegalArgumentException(
                    "Too many arguments. A display name with spaces goes in quotes: " +
                        "create user dana dana@example.com \"Dana Kim\" admin",
                ),
            )
        }
        val role =
            args.getOrNull(3)?.let {
                UserCommands.parseRole(it)
                    ?: return Result.failure(
                        IllegalArgumentException("Unknown role '$it': use ${UserCommands.roles}"),
                    )
            } ?: Role.USER
        return Result.success(
            CreateUserRequest(
                username = args[0],
                email = args[1],
                displayName = args[2],
                role = role,
            ),
        )
    }

    override fun handle(payload: CreateUserRequest, message: Message<*>): OperationOutcome {
        val provenance =
            message.headers[Provenance.HEADER] as? Provenance
                ?: return OperationResult.Error("No provenance")
        val principal = provenance.user ?: return OperationResult.Error("Not authenticated")

        if (principal.role != Role.SUPER_ADMIN) {
            return OperationResult.Error("Insufficient privileges")
        }

        return try {
            val created =
                userRegistrationService.createUser(
                    username = payload.username,
                    email = payload.email,
                    displayName = payload.displayName,
                    role = payload.role,
                )
            OperationResult.Success(created)
        } catch (e: IllegalArgumentException) {
            OperationResult.Error(e.message ?: "Invalid user")
        } catch (e: Exception) {
            logger.warn("Failed to create user {}: {}", payload.username, e.message)
            OperationResult.Error("Username already exists")
        }
    }

    private companion object {
        const val USAGE =
            "Usage: create user <username> <email> <display-name> [role]; " +
                "a display name with spaces goes in quotes"
    }
}
