/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.service

import com.enigmastation.streampack.core.repository.OperationConfigRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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
class OperationConfigServiceTests {

    @Autowired lateinit var service: OperationConfigService
    @Autowired lateinit var repository: OperationConfigRepository

    @BeforeEach
    fun setup() {
        service.clearCache()
    }

    @Test
    fun `operations are enabled by default when no config exists`() {
        assertTrue(service.isOperationEnabled("irc://libera/%23java", "karma"))
    }

    @Test
    fun `global disable applies to all provenances`() {
        service.setEnabled("", "karma", false)

        assertFalse(service.isOperationEnabled("irc://libera/%23java", "karma"))
        assertFalse(service.isOperationEnabled("discord://guild/%23channel", "karma"))
    }

    @Test
    fun `global enable can be overridden per provenance`() {
        service.setEnabled("", "karma", true)
        service.setEnabled("irc://libera/%23java", "karma", false)

        assertFalse(service.isOperationEnabled("irc://libera/%23java", "karma"))
        assertTrue(service.isOperationEnabled("irc://libera/%23kotlin", "karma"))
    }

    @Test
    fun `most specific provenance pattern wins`() {
        service.setEnabled("", "karma", false)
        service.setEnabled("irc://libera", "karma", true)
        service.setEnabled("irc://libera/%23java", "karma", false)

        // Global disabled, but libera override enables
        assertTrue(service.isOperationEnabled("irc://libera/%23kotlin", "karma"))
        // Specific channel override disables
        assertFalse(service.isOperationEnabled("irc://libera/%23java", "karma"))
        // Non-libera IRC inherits global disabled
        assertFalse(service.isOperationEnabled("irc://oftc/%23java", "karma"))
    }

    @Test
    fun `different groups are independent`() {
        service.setEnabled("", "karma", false)
        service.setEnabled("", "factoid", true)

        assertFalse(service.isOperationEnabled("irc://libera/%23java", "karma"))
        assertTrue(service.isOperationEnabled("irc://libera/%23java", "factoid"))
    }

    @Test
    fun `setEnabled creates new entry when none exists`() {
        assertNull(service.findConfig("", "karma"))

        service.setEnabled("", "karma", false)

        val config = service.findConfig("", "karma")
        assertNotNull(config)
        assertFalse(config!!.enabled)
    }

    @Test
    fun `setEnabled updates existing entry`() {
        service.setEnabled("", "karma", false)
        val first = service.findConfig("", "karma")

        service.setEnabled("", "karma", true)
        val second = service.findConfig("", "karma")

        assertEquals(first!!.id, second!!.id)
        assertTrue(second.enabled)
    }

    @Test
    fun `config values merge from least to most specific`() {
        service.setConfigValue("", "karma", "maxLength", "100")
        service.setConfigValue("irc://libera", "karma", "maxLength", "50")
        service.setConfigValue("irc://libera", "karma", "style", "compact")

        val config = service.getOperationConfig("irc://libera/%23java", "karma")
        assertEquals("50", config["maxLength"])
        assertEquals("compact", config["style"])
    }

    @Test
    fun `config returns empty map when no config exists`() {
        val config = service.getOperationConfig("irc://libera/%23java", "karma")
        assertTrue(config.isEmpty())
    }

    @Test
    fun `cache is cleared on write`() {
        // Prime the cache
        assertTrue(service.isOperationEnabled("irc://libera/%23java", "karma"))

        // Write should clear cache
        service.setEnabled("", "karma", false)

        // Should reflect the new state
        assertFalse(service.isOperationEnabled("irc://libera/%23java", "karma"))
    }

    @Test
    fun `findByGroup returns all rows for a group`() {
        service.setEnabled("", "karma", false)
        service.setEnabled("irc://libera", "karma", true)
        service.setEnabled("", "factoid", true)

        val karmaConfigs = service.findByGroup("karma")
        assertEquals(2, karmaConfigs.size)
    }
}
