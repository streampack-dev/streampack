/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.config

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.springframework.boot.SpringApplication
import org.springframework.mock.env.MockEnvironment

class ExtensionConfigurationGuardTests {
    @TempDir lateinit var dir: Path

    private fun guard() =
        ExtensionConfigurationGuard()
            .postProcessEnvironment(
                MockEnvironment().withProperty("STREAMPACK_EXT_DIR", dir.toString()),
                SpringApplication(),
            )

    @Test
    fun `a directory without configuration files lets the start go on`() {
        Files.writeString(dir.resolve("banner.txt"), "")
        Files.createDirectory(dir.resolve("config"))
        Files.writeString(dir.resolve("config").resolve("application.yml"), "")
        assertDoesNotThrow { guard() }
    }

    @Test
    fun `an application yml at the top stops the start, saying where to put it`() {
        Files.writeString(dir.resolve("application.yml"), "")
        val error = assertThrows<IllegalStateException> { guard() }
        assertTrue(error.message!!.contains("$dir/config/"), error.message)
    }

    @Test
    fun `a missing directory lets the start go on`() {
        Files.delete(dir)
        assertDoesNotThrow { guard() }
    }
}
