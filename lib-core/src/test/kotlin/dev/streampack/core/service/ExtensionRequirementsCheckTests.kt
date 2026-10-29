/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.service

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows

class ExtensionRequirementsCheckTests {
    private fun extension(name: String, requires: String?) =
        object : Extension {
            override val name = name
            override val requiresServer = requires
        }

    private fun server(version: String?) =
        object : ServerInfo {
            override val version = version
        }

    private fun check(version: String?, vararg extensions: Extension) =
        ExtensionRequirementsCheck(extensions.toList(), server(version)).afterPropertiesSet()

    @Test
    fun `extensions the server is new enough for load`() {
        assertDoesNotThrow {
            check(
                "1.17.0",
                extension("a", "1.16.11"),
                extension("b", "1.17.0"),
                extension("c", null),
            )
        }
    }

    @Test
    fun `an extension needing a newer server stops the start`() {
        val e =
            assertThrows<SilentStartupException> {
                check("1.16.10", extension("ok", "1.16.0"), extension("hangman", "1.16.11"))
            }
        assertThat(e.message).contains("newer server")
    }

    @Test
    fun `a development build with no version lets every extension load`() {
        assertDoesNotThrow { check(null, extension("hangman", "99.0")) }
    }
}
