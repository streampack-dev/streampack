/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.persistence

import dev.streampack.core.TestChannelConfiguration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate

/** Tests run on the PostgreSQL major version production runs, set once as `postgresVersion`. */
@SpringBootTest
@Import(TestChannelConfiguration::class)
class PostgresVersionTests {
    @Autowired private lateinit var jdbc: JdbcTemplate

    @Test
    fun `tests run on the production PostgreSQL major version`() {
        val versionNum =
            jdbc.queryForObject("SHOW server_version_num", String::class.java)!!.toInt()

        assertEquals(18, versionNum / 10_000)
    }
}
