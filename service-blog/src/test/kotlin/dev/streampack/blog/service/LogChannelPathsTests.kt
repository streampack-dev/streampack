/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.service

import dev.streampack.core.model.Protocol
import dev.streampack.core.model.Provenance
import dev.streampack.core.service.ChannelNameProvider
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class LogChannelPathsTests {
    private val mattermostNames =
        mapOf("chan01" to "town-square", "chan02" to "town-square", "chan03" to "jvm")

    private val paths =
        LogChannelPaths(
            listOf(
                object : ChannelNameProvider {
                    override val protocol = Protocol.MATTERMOST

                    override fun name(provenance: Provenance) = mattermostNames[provenance.replyTo]
                }
            )
        )

    private val primate = "irc://libera/%23primate"
    private val doublePrimate = "irc://libera/%23%23primate"
    private val doubleJava = "irc://libera/%23%23java"

    @Test
    fun `an IRC name is tried as written, then with one hash, then two`() {
        val channels = listOf(primate, doublePrimate, doubleJava)
        assertEquals(primate, paths.resolve("irc", "libera", "primate", channels))
        assertEquals(primate, paths.resolve("irc", "libera", "#primate", channels))
        assertEquals(doublePrimate, paths.resolve("irc", "libera", "##primate", channels))
        assertEquals(doubleJava, paths.resolve("irc", "libera", "java", channels))
        assertEquals(primate, paths.resolve("IRC", "libera", "PRIMATE", channels))
    }

    @Test
    fun `only the channels given are found`() {
        assertNull(paths.resolve("irc", "libera", "primate", listOf(doubleJava)))
        assertNull(paths.resolve("irc", "oftc", "primate", listOf(primate)))
        assertNull(paths.resolve("slack", "libera", "primate", listOf(primate)))
    }

    @Test
    fun `a path is the shortest name that comes back to its channel`() {
        val channels = listOf(primate, doublePrimate, doubleJava)
        assertEquals("irc/libera/primate", paths.path(primate, channels))
        assertEquals("irc/libera/%23%23primate", paths.path(doublePrimate, channels))
        assertEquals("irc/libera/java", paths.path(doubleJava, channels))
    }

    @Test
    fun `other protocols go by name, or by id where the name is shared or unknown`() {
        val shared1 = "mattermost://work/chan01"
        val shared2 = "mattermost://work/chan02"
        val jvm = "mattermost://work/chan03"
        val unnamed = "mattermost://work/chan04"
        val channels = listOf(shared1, shared2, jvm, unnamed)
        assertEquals("mattermost/work/jvm", paths.path(jvm, channels))
        assertEquals(jvm, paths.resolve("mattermost", "work", "JVM", channels))
        assertEquals("mattermost/work/chan01", paths.path(shared1, channels))
        assertEquals(shared2, paths.resolve("mattermost", "work", "chan02", channels))
        assertNull(paths.resolve("mattermost", "work", "town-square", channels))
        assertEquals("mattermost/work/chan04", paths.path(unnamed, channels))
    }

    @Test
    fun `a Discord channel goes by the name in its address`() {
        val general = "discord://123456789012345678/987654321098765432/Guild/%23general"
        assertEquals(
            "discord/123456789012345678/general",
            paths.path(general, listOf(general)),
        )
    }
}
