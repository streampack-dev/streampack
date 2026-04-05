/* Joseph B. Ottinger (C)2026 */
package dev.streampack

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.persistence.autoconfigure.EntityScan
import org.springframework.data.jpa.repository.config.EnableJpaRepositories

@SpringBootApplication(scanBasePackages = ["dev.streampack"])
@ConfigurationPropertiesScan("dev.streampack")
@EntityScan("dev.streampack")
@EnableJpaRepositories("dev.streampack")
class TestApplication {}
