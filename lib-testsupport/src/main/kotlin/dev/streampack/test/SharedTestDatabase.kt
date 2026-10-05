/* Joseph B. Ottinger (C)2026 */
package dev.streampack.test

import java.sql.DriverManager
import java.util.UUID
import org.springframework.boot.EnvironmentPostProcessor
import org.springframework.boot.SpringApplication
import org.springframework.core.Ordered
import org.springframework.core.env.ConfigurableEnvironment
import org.springframework.core.env.MapPropertySource
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

/**
 * Tests' database: a fresh, empty one for each test JVM (each module's run), in one Postgres
 * container that's reused between modules and builds where Testcontainers allows reuse
 * (`testcontainers.reuse.enable=true` in `~/.testcontainers.properties`). Where it doesn't, the
 * container is the JVM's own and goes with it, as with the `jdbc:tc:` URL before. Either way each
 * module starts from an empty database, as it always has.
 *
 * Takes the place of a `jdbc:tc:postgresql:<version>://…` datasource URL, keeping its version; off
 * with `streampack.test.shared-database=false`. Runs after the other post-processors, so lib-core's
 * still sees the `jdbc:tc:` URL and leaves the database alone, as it always has.
 */
class SharedTestDatabaseEnvironmentPostProcessor : EnvironmentPostProcessor, Ordered {
    override fun getOrder(): Int = Ordered.LOWEST_PRECEDENCE

    override fun postProcessEnvironment(
        environment: ConfigurableEnvironment,
        application: SpringApplication,
    ) {
        val url = environment.getProperty("spring.datasource.url") ?: return
        val version = TC_URL.find(url)?.groupValues?.get(1) ?: return
        if (environment.getProperty("streampack.test.shared-database") == "false") return
        val database = SharedTestDatabase.forThisJvm(version)
        environment.propertySources.addFirst(
            MapPropertySource(
                "sharedTestDatabase",
                mapOf(
                    "spring.datasource.url" to database.url,
                    "spring.datasource.username" to database.username,
                    "spring.datasource.password" to database.password,
                ),
            )
        )
    }

    private companion object {
        val TC_URL = Regex("""^jdbc:tc:postgresql:([^:/]+)://""")
    }
}

/** The container, shared, and this JVM's database in it, created once and dropped at exit. */
object SharedTestDatabase {
    data class Database(val url: String, val username: String, val password: String)

    private var database: Database? = null

    @Synchronized
    fun forThisJvm(version: String): Database = database ?: create(version).also { database = it }

    private fun create(version: String): Database {
        val container =
            PostgreSQLContainer(DockerImageName.parse("postgres:$version"))
                .withReuse(true)
                // Modules run in parallel (mvnd), each JVM with a pool per cached context.
                .withCommand("postgres", "-c", "max_connections=1000", "-c", "fsync=off")
        container.start()
        val admin = {
            DriverManager.getConnection(container.jdbcUrl, container.username, container.password)
        }
        val pid = ProcessHandle.current().pid()
        val name = "test_${pid}_${UUID.randomUUID().toString().take(8)}"
        admin().use { conn ->
            conn.createStatement().use { st ->
                // Left by test JVMs that died before dropping theirs.
                val stale = mutableListOf<String>()
                st.executeQuery("SELECT datname FROM pg_database WHERE datname LIKE 'test\\_%'")
                    .use {
                        while (it.next()) stale += it.getString(1)
                    }
                stale
                    .filter { db -> ownerGone(db) }
                    .forEach { db -> st.execute("DROP DATABASE IF EXISTS \"$db\" WITH (FORCE)") }
                st.execute("CREATE DATABASE \"$name\"")
            }
        }
        Runtime.getRuntime()
            .addShutdownHook(
                Thread {
                    runCatching {
                        admin().use { conn ->
                            conn.createStatement().use {
                                it.execute("DROP DATABASE IF EXISTS \"$name\" WITH (FORCE)")
                            }
                        }
                    }
                }
            )
        val url = "jdbc:postgresql://${container.host}:${container.firstMappedPort}/$name"
        return Database(url, container.username, container.password)
    }

    /** Whether the JVM a test database was made for (its name carries the pid) has gone. */
    private fun ownerGone(database: String): Boolean {
        val pid = database.removePrefix("test_").substringBefore('_').toLongOrNull() ?: return true
        return ProcessHandle.of(pid).map { !it.isAlive }.orElse(true)
    }
}
