/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.service

import dev.streampack.core.config.StreampackProperties
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationStartedEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

/**
 * Says at startup what the ext/ directory added to the server (#229), so an operator can see which
 * extension jars are running. The image puts the directory on the classpath (`loader.path`); this
 * reads the same directory, `STREAMPACK_EXT_DIR`, and reports it.
 */
@Component
class ExtensionDirectoryReport(private val properties: StreampackProperties) {
    private val logger = LoggerFactory.getLogger(ExtensionDirectoryReport::class.java)

    @EventListener(ApplicationStartedEvent::class)
    fun report() {
        describe(Path.of(properties.extDir))?.let { logger.info(it) }
    }

    companion object {
        private val CONFIGURATION =
            listOf("application.yml", "application.yaml", "application.properties")

        /**
         * Why [dir] can't be used, when it holds the server's own configuration file name. The
         * directory comes first on the classpath, so `application.yml` there replaces the server's
         * whole file, not just the settings in it; `config/application.yml` adds to it instead.
         * [dev.streampack.core.config.ExtensionConfigurationGuard] stops the start with it.
         */
        fun shadowedConfiguration(dir: Path): String? {
            val found = CONFIGURATION.filter { Files.isRegularFile(dir.resolve(it)) }
            if (found.isEmpty()) return null
            return "${found.joinToString(", ")} in $dir would replace the server's own " +
                "configuration, not just the settings in it, so the server won't start with it. " +
                "To add or change settings, move it to $dir/config/, or set them in the " +
                "environment."
        }

        /** What [dir] adds, or null when there's no such directory. */
        fun describe(dir: Path): String? {
            if (!dir.isDirectory()) return null
            val entries = dir.listDirectoryEntries().sortedBy { it.name }
            val jars = entries.filter { Files.isRegularFile(it) && it.name.endsWith(".jar") }
            val others = entries.filterNot { it in jars }
            if (entries.isEmpty()) return "Extensions: $dir is empty"
            val parts = mutableListOf<String>()
            parts +=
                if (jars.isEmpty()) "no jars" else "jars ${jars.joinToString(", ") { it.name }}"
            if (others.isNotEmpty()) {
                parts +=
                    "resources ${others.joinToString(", ") { it.name + if (it.isDirectory()) "/" else "" }}"
            }
            return "Extensions from $dir: ${parts.joinToString("; ")}"
        }
    }
}
