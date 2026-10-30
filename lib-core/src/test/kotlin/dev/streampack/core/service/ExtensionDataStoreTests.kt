/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.service

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.annotation.Transactional

/** The server's store for extension data (#294), and the end of hangman's old table. */
@SpringBootTest
@Transactional
class ExtensionDataStoreTests {
    @Autowired lateinit var store: ExtensionDataStore
    @Autowired lateinit var jdbc: JdbcTemplate

    @Test
    fun `a document is kept, replaced, listed and deleted`() {
        assertThat(store.get("game", "scores", "alice")).isNull()

        store.put("game", "scores", "alice", mapOf("points" to 3))
        store.put("game", "scores", "alice", mapOf("points" to 5))
        store.put("game", "scores", "bob", mapOf("points" to 1))

        assertThat(store.get("game", "scores", "alice")).isEqualTo(mapOf("points" to 5))
        assertThat(store.list("game", "scores").keys).containsExactlyInAnyOrder("alice", "bob")

        assertThat(store.delete("game", "scores", "alice")).isTrue()
        assertThat(store.delete("game", "scores", "alice")).isFalse()
        assertThat(store.get("game", "scores", "alice")).isNull()
    }

    @Test
    fun `extensions and collections don't see each other's documents`() {
        store.put("game", "scores", "alice", mapOf("points" to 3))

        assertThat(store.get("other", "scores", "alice")).isNull()
        assertThat(store.get("game", "wins", "alice")).isNull()
        assertThat(store.list("other", "scores")).isEmpty()
    }

    @Test
    fun `V84 drops hangman's old table, whose words V83 copied`() {
        val tables =
            jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.tables WHERE table_name = 'hangman_blocked_words'",
                Int::class.java,
            )
        assertThat(tables).isZero()
    }
}
