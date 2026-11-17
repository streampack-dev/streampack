/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.config

import java.sql.DriverManager
import org.springframework.boot.EnvironmentPostProcessor
import org.springframework.boot.SpringApplication
import org.springframework.core.Ordered
import org.springframework.core.env.ConfigurableEnvironment
import org.springframework.core.env.MapPropertySource

/**
 * Reads service enablement state from the operation_config table at startup and overrides
 * streampack.*.enabled properties so that @ConditionalOnProperty evaluates against the DB state.
 *
 * Runs before any Spring beans are created. Uses raw JDBC to avoid depending on Spring Data.
 * Gracefully handles missing table (first startup before Flyway runs).
 *
 * The services in [ENVIRONMENT_ONLY] are left to the environment: a row for one is ignored. The
 * console reads standard input, so whether it can run depends on how the server is started, with a
 * terminal or without; a database value would outlive that and start a console with no input.
 */
class OperationConfigEnvironmentPostProcessor : EnvironmentPostProcessor, Ordered {
    // Just before the tests' shared-database one (lib-testsupport), so in tests this one still
    // sees the jdbc:tc: URL and leaves the database alone.
    override fun getOrder(): Int = Ordered.LOWEST_PRECEDENCE - 1

    override fun postProcessEnvironment(
        environment: ConfigurableEnvironment,
        application: SpringApplication,
    ) {
        val url = environment.getProperty("spring.datasource.url") ?: return
        val username = environment.getProperty("spring.datasource.username") ?: ""
        val password = environment.getProperty("spring.datasource.password") ?: ""

        // Testcontainers URLs are not connectable at this stage
        if (url.startsWith("jdbc:tc:")) return

        val overrides = mutableMapOf<String, Any>()
        try {
            DriverManager.getConnection(url, username, password).use { conn ->
                val stmt =
                    conn.prepareStatement(
                        """
                        SELECT operation_group, enabled
                        FROM operation_config
                        WHERE provenance_pattern = '' AND operation_group LIKE 'service:%'
                        """
                            .trimIndent(),
                    )
                stmt.use { ps ->
                    ps.executeQuery().use { rs ->
                        while (rs.next()) {
                            val group = rs.getString("operation_group")
                            val enabled = rs.getBoolean("enabled")
                            val serviceName = group.removePrefix("service:")
                            if (serviceName in ENVIRONMENT_ONLY) continue
                            overrides["streampack.$serviceName.enabled"] = enabled.toString()
                        }
                    }
                }
            }
        } catch (_: Exception) {
            // Table may not exist yet (first startup before Flyway), or DB is unreachable
            return
        }

        if (overrides.isNotEmpty()) {
            environment.propertySources.addFirst(
                MapPropertySource("operationConfigOverrides", overrides),
            )
        }
    }

    companion object {
        /**
         * Services whose `streampack.<name>.enabled` only the environment sets, never the database.
         */
        val ENVIRONMENT_ONLY: Set<String> = setOf("console")
    }
}
