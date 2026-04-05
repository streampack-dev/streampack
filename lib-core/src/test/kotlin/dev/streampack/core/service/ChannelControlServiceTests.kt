/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.service

import com.enigmastation.streampack.core.repository.ChannelControlOptionsRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.transaction.annotation.Transactional

@SpringBootTest
@Transactional
class ChannelControlServiceTests {

    @Autowired lateinit var channelControlService: ChannelControlService
    @Autowired lateinit var repository: ChannelControlOptionsRepository

    @Test
    fun `getOrCreateOptions creates entry with defaults`() {
        val uri = "irc://libera/%23java"
        val options = channelControlService.getOrCreateOptions(uri)

        assertNotNull(options)
        assertEquals(uri, options.provenanceUri)
        assertFalse(options.autojoin)
        assertFalse(options.automute)
        assertTrue(options.visible)
        assertTrue(options.logged)
        assertTrue(options.active)
        assertFalse(options.deleted)
    }

    @Test
    fun `getOrCreateOptions returns existing entry`() {
        val uri = "irc://libera/%23java"
        val first = channelControlService.getOrCreateOptions(uri)
        val second = channelControlService.getOrCreateOptions(uri)

        assertEquals(first.id, second.id)
    }

    @Test
    fun `getOptions returns null for unknown URI`() {
        assertNull(channelControlService.getOptions("irc://nonexistent/%23nope"))
    }

    @Test
    fun `setFlag updates autojoin`() {
        val uri = "irc://libera/%23java"
        channelControlService.setFlag(uri, "autojoin", true)

        val options = channelControlService.getOptions(uri)
        assertNotNull(options)
        assertTrue(options!!.autojoin)
    }

    @Test
    fun `setFlag updates automute`() {
        val uri = "irc://libera/%23java"
        channelControlService.setFlag(uri, "automute", true)

        val options = channelControlService.getOptions(uri)
        assertTrue(options!!.automute)
    }

    @Test
    fun `setFlag updates visible`() {
        val uri = "irc://libera/%23java"
        channelControlService.setFlag(uri, "visible", false)

        val options = channelControlService.getOptions(uri)
        assertFalse(options!!.visible)
    }

    @Test
    fun `setFlag updates logged`() {
        val uri = "irc://libera/%23java"
        channelControlService.setFlag(uri, "logged", false)

        val options = channelControlService.getOptions(uri)
        assertFalse(options!!.logged)
    }

    @Test
    fun `setFlag rejects unknown flag name`() {
        assertThrows(IllegalArgumentException::class.java) {
            channelControlService.setFlag("irc://libera/%23java", "bogus", true)
        }
    }

    @Test
    fun `findAutojoins returns only autojoin channels`() {
        channelControlService.setFlag("irc://libera/%23java", "autojoin", true)
        channelControlService.getOrCreateOptions("irc://libera/%23kotlin")

        val autojoins = channelControlService.findAutojoins()
        assertEquals(1, autojoins.size)
        assertEquals("irc://libera/%23java", autojoins[0].provenanceUri)
    }
}
