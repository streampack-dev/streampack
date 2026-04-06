/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.repository

import dev.streampack.core.entity.User
import dev.streampack.core.model.Role
import dev.streampack.core.model.UserStatus
import java.util.UUID
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query

interface UserRepository : JpaRepository<User, UUID> {
    fun findByUsername(username: String): User?

    fun findByEmail(email: String): User?

    @Query("SELECT u FROM User u WHERE u.status = dev.streampack.core.model.UserStatus.ACTIVE")
    fun findActive(): List<User>

    @Query(
        "SELECT CASE WHEN COUNT(u) > 0 THEN true ELSE false END FROM User u WHERE u.role = :role AND u.status = dev.streampack.core.model.UserStatus.ACTIVE"
    )
    fun hasActiveWithRole(role: Role): Boolean

    @Query(
        "SELECT u FROM User u WHERE u.id = :id AND u.status = dev.streampack.core.model.UserStatus.ACTIVE"
    )
    fun findActiveById(id: UUID): User?

    @Query(
        "SELECT u FROM User u WHERE u.role = :role AND u.status = dev.streampack.core.model.UserStatus.ACTIVE ORDER BY u.createdAt ASC"
    )
    fun findActiveByRole(role: Role): List<User>

    @Query(
        "SELECT DISTINCT u.email FROM User u " +
            "WHERE u.status = dev.streampack.core.model.UserStatus.ACTIVE " +
            "AND u.role IN (" +
            "dev.streampack.core.model.Role.ADMIN, " +
            "dev.streampack.core.model.Role.SUPER_ADMIN" +
            ") " +
            "AND u.email <> '' " +
            "ORDER BY u.email ASC"
    )
    fun findDistinctActiveAdminEmailAddresses(): List<String>

    /** Returns users that are active or suspended (for admin views) */
    @Query(
        "SELECT u FROM User u WHERE u.status IN (dev.streampack.core.model.UserStatus.ACTIVE, dev.streampack.core.model.UserStatus.SUSPENDED) ORDER BY u.createdAt ASC"
    )
    fun findActiveOrSuspended(): List<User>

    /** Returns erased sentinel users (for admin purge workflow) */
    fun findByStatus(status: UserStatus): List<User>

    /** Hard-deletes a user record, bypassing Hibernate lifecycle */
    @Modifying(clearAutomatically = true)
    @Query("DELETE FROM User u WHERE u.id = :id")
    fun hardDeleteById(id: UUID)
}
