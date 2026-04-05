/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.entity

import com.enigmastation.streampack.core.model.Role
import com.enigmastation.streampack.core.model.UserPrincipal
import com.enigmastation.streampack.core.model.UserStatus
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID
import org.hibernate.annotations.UuidGenerator

/** Persistent user account record */
@Entity
@Table(name = "users")
data class User(
    @Id @UuidGenerator(style = UuidGenerator.Style.VERSION_7) val id: UUID = UUID(0, 0),
    @Column(nullable = false, unique = true) val username: String = "",
    @Column(nullable = false) val email: String = "",
    @Column(nullable = false) val emailVerified: Boolean = false,
    @Column(nullable = false) val displayName: String = "",
    val realName: String? = null,
    @Enumerated(EnumType.STRING) @Column(nullable = false) val role: Role = Role.USER,
    @Column(nullable = false) val createdAt: Instant = Instant.now(),
    val lastLoginAt: Instant? = null,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    val status: UserStatus = UserStatus.ACTIVE,
) {
    fun isActive(): Boolean = status == UserStatus.ACTIVE

    fun isSuspended(): Boolean = status == UserStatus.SUSPENDED

    fun isErased(): Boolean = status == UserStatus.ERASED

    /** Produces the lightweight principal carried in message headers */
    fun toUserPrincipal(): UserPrincipal =
        UserPrincipal(id = id, username = username, displayName = displayName, role = role)
}
