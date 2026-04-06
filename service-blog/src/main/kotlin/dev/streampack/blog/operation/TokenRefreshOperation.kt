/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.operation

import dev.streampack.blog.model.LoginResponse
import dev.streampack.blog.model.TokenRefreshRequest
import dev.streampack.core.model.OperationResult
import dev.streampack.core.repository.UserRepository
import dev.streampack.core.service.JwtService
import dev.streampack.core.service.Operation
import org.springframework.messaging.Message
import org.springframework.stereotype.Component

/** Issues a fresh JWT from a valid existing token or a validated user ID */
@Component
class TokenRefreshOperation(
    private val jwtService: JwtService,
    private val userRepository: UserRepository,
) : Operation {
    override val priority = 50

    override fun canHandle(message: Message<*>): Boolean = message.payload is TokenRefreshRequest

    override fun execute(message: Message<*>): OperationResult {
        val request = message.payload as TokenRefreshRequest

        if (request.userId != null) {
            val user =
                userRepository.findActiveById(request.userId)
                    ?: return OperationResult.Error("Invalid or expired token")
            val principal = user.toUserPrincipal()
            val newToken = jwtService.generateToken(principal)
            return OperationResult.Success(LoginResponse(newToken, principal))
        }

        val principal =
            jwtService.validateToken(request.token)
                ?: return OperationResult.Error("Invalid or expired token")

        userRepository.findActiveById(principal.id)
            ?: return OperationResult.Error("Invalid or expired token")

        val newToken = jwtService.generateToken(principal)
        return OperationResult.Success(LoginResponse(newToken, principal))
    }
}
