/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.service

import dev.streampack.core.config.StreampackProperties
import dev.streampack.core.model.Role
import dev.streampack.core.model.UserPrincipal
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.time.Duration
import java.time.Instant
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * The local admin token (#209): a short-lived super-admin token in a file only the server's own
 * user can read, for a setup tool on the same box. Off unless a file is named.
 */
class LocalAdminTokenWriterTests {
    @TempDir lateinit var dir: Path

    private val root =
        UserPrincipal(UUID.randomUUID(), "root", "System Administrator", Role.SUPER_ADMIN)
    private val jwt =
        JwtService(
            StreampackProperties(
                jwt =
                    StreampackProperties.JwtProperties(
                        secret = "a test secret that is long enough to sign with",
                    ),
            ),
        )

    private fun writer(
        file: Path?,
        superAdmin: UserPrincipal? = root,
        lifetime: Duration = Duration.ofHours(1),
    ) =
        LocalAdminTokenWriter(
            StreampackProperties.LocalAdminTokenProperties(
                file = file?.toString() ?: "",
                lifetime = lifetime,
            ),
            jwt,
        ) {
            superAdmin
        }

    @Test
    fun `writes a token for the super-admin, readable by the server's user alone`() {
        val file = dir.resolve("admin-token")
        assertTrue(writer(file).write())

        val token = Files.readString(file).trim()
        val validated = jwt.validate(token)
        assertNotNull(validated)
        assertEquals(root, validated!!.principal)
        val left = Duration.between(Instant.now(), validated.expiresAt)
        assertTrue(left > Duration.ofMinutes(59) && left <= Duration.ofHours(1), "$left")
        assertEquals(
            "rw-------",
            PosixFilePermissions.toString(Files.getPosixFilePermissions(file)),
        )
    }

    @Test
    fun `replaces what was there, permissions too`() {
        val file = dir.resolve("admin-token")
        Files.writeString(file, "old")
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r--r--"))

        assertTrue(writer(file).write())
        assertFalse(Files.readString(file).contains("old"))
        assertEquals(
            "rw-------",
            PosixFilePermissions.toString(Files.getPosixFilePermissions(file)),
        )
    }

    @Test
    fun `writes nothing without a super-admin, and takes away a token left from before`() {
        val file = dir.resolve("admin-token")
        Files.writeString(file, "stale")
        assertFalse(writer(file, superAdmin = null).write())
        assertFalse(Files.exists(file))
    }

    @Test
    fun `is taken away on shutdown`() {
        val file = dir.resolve("admin-token")
        val writer = writer(file)
        writer.write()
        writer.remove()
        assertFalse(Files.exists(file))
    }

    @Test
    fun `does nothing when no file is named`() {
        assertFalse(writer(null).write())
        writer(null).remove()
    }

    @Test
    fun `is renewed at half its lifetime, so the file always holds a token with time left`() {
        assertEquals(Duration.ofMinutes(30), writer(dir.resolve("t")).renewEvery)
        assertEquals(
            Duration.ofMinutes(1),
            writer(dir.resolve("t"), lifetime = Duration.ofSeconds(10)).renewEvery,
        )
    }
}
