/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.repository

import dev.streampack.core.entity.User
import dev.streampack.core.model.Role
import dev.streampack.core.model.UserStatus
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.transaction.annotation.Transactional

@SpringBootTest
@Transactional
class UserRepositoryTests {

    @Autowired lateinit var userRepository: UserRepository

    @Test
    fun `save and retrieve user`() {
        val user =
            User(
                username = "testuser",
                email = "testuser@example.com",
                displayName = "Test User",
                role = Role.USER,
            )
        val saved = userRepository.save(user)
        val found = userRepository.findById(saved.id).orElse(null)

        assertNotNull(found)
        assertEquals("testuser", found.username)
        assertEquals("testuser@example.com", found.email)
        assertEquals("Test User", found.displayName)
        assertEquals(Role.USER, found.role)
    }

    @Test
    fun `UUIDv7 is generated on save`() {
        val user = User(username = "uuidtest", email = "uuid@test.com", displayName = "UUID Test")
        val saved = userRepository.save(user)

        assertNotEquals(UUID(0, 0), saved.id)
        // UUIDv7 has version 7 in the version nibble
        assertEquals(7, saved.id.version())
    }

    @Test
    fun `find by username`() {
        userRepository.save(
            User(username = "findme", email = "findme@test.com", displayName = "Find Me")
        )
        val found = userRepository.findByUsername("findme")

        assertNotNull(found)
        assertEquals("findme", found!!.username)
    }

    @Test
    fun `find by username returns null for nonexistent`() {
        assertNull(userRepository.findByUsername("nonexistent"))
    }

    @Test
    fun `find by email`() {
        userRepository.save(
            User(username = "emailtest", email = "specific@test.com", displayName = "Email Test")
        )
        val found = userRepository.findByEmail("specific@test.com")

        assertNotNull(found)
        assertEquals("emailtest", found!!.username)
    }

    @Test
    fun `status-based filtering excludes erased users`() {
        userRepository.save(
            User(username = "active", email = "active@test.com", displayName = "Active User")
        )
        userRepository.save(
            User(
                username = "erased",
                email = "erased@test.com",
                displayName = "Erased User",
                status = UserStatus.ERASED,
            )
        )
        val activeUsers = userRepository.findActive()

        assertEquals(1, activeUsers.size)
        assertEquals("active", activeUsers[0].username)
    }

    @Test
    fun `find distinct active admin email addresses dedupes and excludes suspended admins`() {
        userRepository.save(
            User(
                username = "admin1",
                email = "admins@test.com",
                displayName = "Admin One",
                role = Role.ADMIN,
            )
        )
        userRepository.save(
            User(
                username = "super1",
                email = "admins@test.com",
                displayName = "Super One",
                role = Role.SUPER_ADMIN,
            )
        )
        userRepository.save(
            User(
                username = "suspendedadmin",
                email = "suspended@test.com",
                displayName = "Suspended Admin",
                role = Role.ADMIN,
                status = UserStatus.SUSPENDED,
            )
        )
        userRepository.save(
            User(
                username = "regularuser",
                email = "user@test.com",
                displayName = "Regular User",
                role = Role.USER,
            )
        )

        val emails = userRepository.findDistinctActiveAdminEmailAddresses()

        assertEquals(listOf("admins@test.com"), emails)
    }

    @Test
    fun `toUserPrincipal conversion`() {
        val saved =
            userRepository.save(
                User(
                    username = "principal",
                    email = "principal@test.com",
                    displayName = "Principal Test",
                    role = Role.ADMIN,
                )
            )
        val principal = saved.toUserPrincipal()

        assertEquals(saved.id, principal.id)
        assertEquals("principal", principal.username)
        assertEquals("Principal Test", principal.displayName)
        assertEquals(Role.ADMIN, principal.role)
    }

    @Test
    fun `unique username constraint`() {
        userRepository.save(
            User(username = "unique", email = "first@test.com", displayName = "First")
        )
        userRepository.flush()

        assertThrows(Exception::class.java) {
            userRepository.save(
                User(username = "unique", email = "second@test.com", displayName = "Second")
            )
            userRepository.flush()
        }
    }
}
