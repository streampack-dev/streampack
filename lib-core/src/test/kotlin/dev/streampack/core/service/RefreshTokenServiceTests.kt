/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.service

import dev.streampack.core.model.Protocol
import dev.streampack.core.repository.RefreshTokenRepository
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.transaction.annotation.Transactional

@SpringBootTest
@Transactional
class RefreshTokenServiceTests {

    @Autowired lateinit var refreshTokenService: RefreshTokenService
    @Autowired lateinit var refreshTokenRepository: RefreshTokenRepository
    @Autowired lateinit var userRegistrationService: UserRegistrationService

    private var testUserId: UUID = UUID(0, 0)

    @BeforeEach
    fun setUp() {
        val principal =
            userRegistrationService.register(
                username = "refreshtest",
                email = "refreshtest@example.com",
                displayName = "Refresh Test",
                protocol = Protocol.HTTP,
                serviceId = "test-service",
                externalIdentifier = "refreshtest@example.com",
            )
        testUserId = principal.id
    }

    @Test
    fun `issueToken creates a hashed record in the database`() {
        val rawToken = refreshTokenService.issueToken(testUserId)

        assertNotNull(rawToken)
        assertTrue(rawToken.isNotBlank())

        val hash = RefreshTokenService.sha256(rawToken)
        val stored = refreshTokenRepository.findByTokenHash(hash)
        assertNotNull(stored)
        assertEquals(testUserId, stored!!.userId)
    }

    @Test
    fun `stored hash does not equal the raw token`() {
        val rawToken = refreshTokenService.issueToken(testUserId)
        val hash = RefreshTokenService.sha256(rawToken)

        assertNotEquals(rawToken, hash)
    }

    @Test
    fun `rotateToken invalidates old token and issues new one`() {
        val rawToken = refreshTokenService.issueToken(testUserId)

        val result = refreshTokenService.rotateToken(rawToken)

        assertNotNull(result)
        val (userId, newRawToken) = result!!
        assertEquals(testUserId, userId)
        assertNotEquals(rawToken, newRawToken)

        assertNull(refreshTokenRepository.findByTokenHash(RefreshTokenService.sha256(rawToken)))
        assertNotNull(
            refreshTokenRepository.findByTokenHash(RefreshTokenService.sha256(newRawToken))
        )
    }

    @Test
    fun `rotateToken returns null for unknown token`() {
        val result = refreshTokenService.rotateToken("nonexistent-token")

        assertNull(result)
    }

    @Test
    fun `rotateToken returns null for already-used token`() {
        val rawToken = refreshTokenService.issueToken(testUserId)

        val firstRotation = refreshTokenService.rotateToken(rawToken)
        assertNotNull(firstRotation)

        val secondRotation = refreshTokenService.rotateToken(rawToken)
        assertNull(secondRotation)
    }

    @Test
    fun `revokeAllForUser deletes all tokens for user`() {
        refreshTokenService.issueToken(testUserId)
        refreshTokenService.issueToken(testUserId)
        refreshTokenService.issueToken(testUserId)

        val deleted = refreshTokenService.revokeAllForUser(testUserId)

        assertEquals(3, deleted)
        assertTrue(refreshTokenRepository.findAll().none { it.userId == testUserId })
    }

    @Test
    fun `cleanupExpired removes only expired rows`() {
        refreshTokenService.issueToken(testUserId)

        val deleted = refreshTokenService.cleanupExpired()

        assertEquals(0, deleted)
        assertTrue(refreshTokenRepository.findAll().any { it.userId == testUserId })
    }
}
