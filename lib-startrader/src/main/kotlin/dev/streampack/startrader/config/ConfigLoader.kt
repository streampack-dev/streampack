/* Joseph B. Ottinger (C)2026 */
package dev.streampack.startrader.config

import dev.streampack.core.json.JacksonMappers
import org.springframework.stereotype.Component
import tools.jackson.module.kotlin.readValue

@Component
class ConfigLoader {
    private val objectMapper = JacksonMappers.allowNullForPrimitives()

    /** Load simulation config from classpath resource */
    fun load(
        resourcePath: String = "universe-config.json"
    ): dev.streampack.startrader.config.SimulationConfig {
        val stream =
            javaClass.classLoader.getResourceAsStream(resourcePath)
                ?: throw IllegalArgumentException("Config resource not found: $resourcePath")

        val raw: dev.streampack.startrader.config.RawConfig = objectMapper.readValue(stream)
        return raw.toSimulationConfig()
    }
}
