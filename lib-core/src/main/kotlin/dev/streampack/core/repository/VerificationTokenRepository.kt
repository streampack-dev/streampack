/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.repository

import dev.streampack.core.entity.TokenType
import dev.streampack.core.entity.User
import dev.streampack.core.entity.VerificationToken
import java.util.UUID
import org.springframework.data.jpa.repository.JpaRepository

/** Queries for email verification and password reset tokens */
interface VerificationTokenRepository : JpaRepository<VerificationToken, UUID> {
    fun findByToken(token: String): VerificationToken?

    fun findByUserAndTokenType(user: User, tokenType: TokenType): List<VerificationToken>
}
