/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.webconsole

import dev.streampack.core.entity.User
import dev.streampack.core.model.Role
import dev.streampack.core.repository.UserRepository
import java.util.UUID
import org.springframework.stereotype.Component

/**
 * Who may use the web console right now (#115): an active ADMIN or SUPER_ADMIN, read from the user
 * store each time it's asked, never from a credential's claims. This is the console's own access
 * policy; what each command may do is still decided by its operation, against the principal the
 * operation chain refreshes as the command enters it.
 */
@Component
class WebConsoleAccess(private val userRepository: UserRepository) {
    /** The account with [id] if it's an active admin now, or null. */
    fun currentAdmin(id: UUID): User? =
        userRepository.findActiveById(id)?.takeIf {
            it.role == Role.ADMIN || it.role == Role.SUPER_ADMIN
        }
}
