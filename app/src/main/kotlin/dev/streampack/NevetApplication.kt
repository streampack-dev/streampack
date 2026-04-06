/* Joseph B. Ottinger (C)2026 */
package dev.streampack

import java.time.ZoneId
import java.time.format.DateTimeFormatter
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.info.BuildProperties
import org.springframework.boot.info.GitProperties
import org.springframework.boot.persistence.autoconfigure.EntityScan
import org.springframework.boot.runApplication
import org.springframework.context.event.EventListener
import org.springframework.data.jpa.repository.config.EnableJpaRepositories

@SpringBootApplication(scanBasePackages = ["dev.streampack"])
@ConfigurationPropertiesScan("dev.streampack")
@EntityScan("dev.streampack")
@EnableJpaRepositories("dev.streampack")
class NevetApplication(
    @Autowired(required = false) private val gitProperties: GitProperties?,
    @Autowired(required = false) private val buildProperties: BuildProperties?,
    @Value("\${spring.application.name:}") private val applicationName: String,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    /** Log build identity at startup so deployed instances are identifiable */
    @EventListener(ApplicationReadyEvent::class)
    fun logVersionOnStartup() {
        val parts = mutableListOf<String>()

        val name = applicationName.ifBlank { buildProperties?.name ?: "streampack" }
        val version = buildProperties?.version
        parts.add(if (version != null) "$name $version" else name)

        val commit = gitProperties?.shortCommitId
        val branch = gitProperties?.branch
        if (commit != null) {
            parts.add(if (branch != null) "$commit ($branch)" else commit)
        } else {
            parts.add("development build")
        }

        val buildTime = buildProperties?.time ?: gitProperties?.commitTime
        if (buildTime != null) {
            val formatted = BUILD_TIME_FORMAT.format(buildTime)
            parts.add("Built $formatted")
        }

        logger.info("Started: {}", parts.joinToString(" | "))
    }

    companion object {
        private val BUILD_TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z").withZone(ZoneId.systemDefault())
    }
}

fun main(args: Array<String>) {
    runApplication<NevetApplication>(*args)
}
