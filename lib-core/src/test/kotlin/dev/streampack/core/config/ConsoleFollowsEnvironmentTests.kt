/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.config

import dev.streampack.core.entity.OperationConfig
import dev.streampack.core.repository.OperationConfigRepository
import javax.sql.DataSource
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.SpringApplication
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.mock.env.MockEnvironment

/**
 * The console is the one service the database does not switch: `CONSOLE_ENABLED` decides it on
 * every start. The other services keep the database's value. Not transactional: the post-processor
 * reads the rows over a connection of its own.
 */
@SpringBootTest
class ConsoleFollowsEnvironmentTests {

    @Autowired lateinit var repository: OperationConfigRepository
    @Autowired lateinit var dataSource: DataSource
    @Autowired lateinit var seeder: OperationConfigSeeder

    @AfterEach
    fun cleanUp() {
        repository.deleteAll(
            repository.findAll().filter { it.operationGroup.startsWith("service:") },
        )
    }

    private fun row(name: String, enabled: Boolean) =
        repository.save(
            OperationConfig(
                provenancePattern = "",
                operationGroup = "service:$name",
                enabled = enabled,
            ),
        )

    @Test
    fun `database row does not switch the console`() {
        cleanUp()
        row("console", true)
        row("irc", true)
        val url = dataSource.connection.use { it.metaData.url }
        val user = dataSource.connection.use { it.metaData.userName }
        val environment =
            MockEnvironment()
                .withProperty("spring.datasource.url", url)
                .withProperty("spring.datasource.username", user)
                .withProperty("spring.datasource.password", "test")
                .withProperty("streampack.console.enabled", "false")
        OperationConfigEnvironmentPostProcessor()
            .postProcessEnvironment(environment, SpringApplication())
        assertEquals("false", environment.getProperty("streampack.console.enabled"))
        assertEquals("true", environment.getProperty("streampack.irc.enabled"))
    }

    @Test
    fun `seeding writes no console row`() {
        cleanUp()
        seeder.seed()
        val groups = repository.findAll().map { it.operationGroup }
        assertNull(groups.firstOrNull { it == "service:console" }, groups.toString())
        assertEquals(1, groups.count { it == "service:irc" }, groups.toString())
    }
}
