/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.repository

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.annotation.Transactional

/** The database refuses a second account with the same email, whatever wrote it. */
@SpringBootTest
@Transactional
class UserEmailUniquenessTests {
    @Autowired lateinit var jdbc: JdbcTemplate

    private fun insert(username: String, email: String) =
        jdbc.update(
            "INSERT INTO users (id, username, email, display_name) VALUES (gen_random_uuid(), ?, ?, ?)",
            username,
            email,
            username,
        )

    @Test
    fun `two accounts cannot share an email`() {
        insert("one", "same@example.com")

        assertThrows(DataIntegrityViolationException::class.java) {
            insert("two", "same@example.com")
        }
    }

    @Test
    fun `any number of accounts can have no email`() {
        insert("one", "")
        insert("two", "")

        assertEquals(
            2,
            jdbc.queryForObject(
                "SELECT count(*) FROM users WHERE username IN ('one', 'two')",
                Int::class.java,
            ),
        )
    }
}
