/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.config

import java.sql.DriverManager
import org.springframework.boot.EnvironmentPostProcessor
import org.springframework.boot.SpringApplication
import org.springframework.core.env.ConfigurableEnvironment
import org.springframework.core.env.MapPropertySource

/**
 * Reads service enablement state from the operation_config table at startup and overrides
 * streampack.*.enabled properties so that @ConditionalOnProperty evaluates against the DB state.
 *
 * Runs before any Spring beans are created. Uses raw JDBC to avoid depending on Spring Data.
 * Gracefully handles missing table (first startup before Flyway runs).
 */
class OperationConfigEnvironmentPostProcessor : EnvironmentPostProcessor {

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
                            .trimIndent()
                    )
                stmt.use { ps ->
                    ps.executeQuery().use { rs ->
                        while (rs.next()) {
                            val group = rs.getString("operation_group")
                            val enabled = rs.getBoolean("enabled")
                            val serviceName = group.removePrefix("service:")
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
                MapPropertySource("operationConfigOverrides", overrides)
            )
        }
    }
}
