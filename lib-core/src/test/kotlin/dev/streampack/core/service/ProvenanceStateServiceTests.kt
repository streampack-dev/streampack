/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.service

import com.enigmastation.streampack.core.repository.ProvenanceStateRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.transaction.annotation.Transactional

@SpringBootTest
@Transactional
class ProvenanceStateServiceTests {

    @Autowired lateinit var service: ProvenanceStateService
    @Autowired lateinit var repository: ProvenanceStateRepository

    @Test
    fun `setState creates and getState retrieves`() {
        val uri = "irc://libera/%23java"
        val key = "21-matches"
        val data = mapOf<String, Any>("matches" to 21, "currentPlayer" to "alice")

        service.setState(uri, key, data)

        val result = service.getState(uri, key)
        assertEquals(data, result)
    }

    @Test
    fun `setState on existing key updates the data`() {
        val uri = "irc://libera/%23java"
        val key = "21-matches"

        service.setState(uri, key, mapOf("matches" to 21))
        val first = repository.findByProvenanceUriAndKey(uri, key)

        service.setState(uri, key, mapOf("matches" to 18))
        val second = repository.findByProvenanceUriAndKey(uri, key)

        // Same row was updated, not a new row created
        assertEquals(first!!.id, second!!.id)
        assertEquals(mapOf<String, Any>("matches" to 18), second.data)
    }

    @Test
    fun `getState returns null for nonexistent key`() {
        assertNull(service.getState("irc://libera/%23java", "nonexistent"))
    }

    @Test
    fun `clearState removes the row`() {
        val uri = "irc://libera/%23java"
        val key = "21-matches"

        service.setState(uri, key, mapOf("matches" to 21))
        service.clearState(uri, key)

        assertNull(service.getState(uri, key))
    }

    @Test
    fun `two different keys on same provenance are independent`() {
        val uri = "irc://libera/%23java"

        service.setState(uri, "game-a", mapOf("score" to 10))
        service.setState(uri, "game-b", mapOf("score" to 20))

        assertEquals(mapOf<String, Any>("score" to 10), service.getState(uri, "game-a"))
        assertEquals(mapOf<String, Any>("score" to 20), service.getState(uri, "game-b"))
    }

    @Test
    fun `same key on two different provenances are independent`() {
        val key = "21-matches"

        service.setState("irc://libera/%23java", key, mapOf("matches" to 21))
        service.setState("discord://guild/%23general", key, mapOf("matches" to 15))

        assertEquals(
            mapOf<String, Any>("matches" to 21),
            service.getState("irc://libera/%23java", key),
        )
        assertEquals(
            mapOf<String, Any>("matches" to 15),
            service.getState("discord://guild/%23general", key),
        )
    }
}
