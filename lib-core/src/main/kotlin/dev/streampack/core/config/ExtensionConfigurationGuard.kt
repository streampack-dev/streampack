/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.config

import dev.streampack.core.service.ExtensionDirectoryReport
import java.nio.file.Path
import org.springframework.boot.EnvironmentPostProcessor
import org.springframework.boot.SpringApplication
import org.springframework.core.Ordered
import org.springframework.core.env.ConfigurableEnvironment

/**
 * Stops a start whose ext/ directory holds `application.yml` (or `.yaml`, `.properties`) at its top
 * (#229). The directory comes first on the classpath, so such a file replaces the server's whole
 * configuration, and the start then fails somewhere unrelated, such as the data source. This runs
 * before configuration is read, so it names the cause instead. `config/application.yml` there adds
 * to the configuration, and is fine.
 */
class ExtensionConfigurationGuard : EnvironmentPostProcessor, Ordered {
    override fun getOrder(): Int = Ordered.HIGHEST_PRECEDENCE

    override fun postProcessEnvironment(
        environment: ConfigurableEnvironment,
        application: SpringApplication,
    ) {
        // The configuration may be what's broken, so the directory comes from the environment.
        val dir = environment.getProperty("STREAMPACK_EXT_DIR") ?: DEFAULT_DIR
        ExtensionDirectoryReport.shadowedConfiguration(Path.of(dir))?.let {
            throw IllegalStateException(it)
        }
    }

    companion object {
        const val DEFAULT_DIR = "/opt/streampack/ext"
    }
}
