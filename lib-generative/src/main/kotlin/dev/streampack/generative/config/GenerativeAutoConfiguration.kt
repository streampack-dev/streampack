/* Joseph B. Ottinger (C)2026 */
package dev.streampack.generative.config

import dev.streampack.generative.service.GenerativePromptService
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.io.ResourceLoader

/**
 * Spring auto-configuration for shared generative prompt loading.
 *
 * Registers [GenerativePromptService], which is the runtime entry point used by consumer modules
 * to resolve prompts from:
 *
 * - dynamic filesystem `.clj` overrides
 * - static filesystem `.txt` overrides
 * - bundled classpath fallback resources
 *
 * This configuration is intentionally small: it wires the prompt service and the configuration
 * properties, leaving prompt naming and context assembly to consumer modules.
 */
@Configuration
@EnableConfigurationProperties(GenerativeProperties::class)
class GenerativeAutoConfiguration {
    /**
     * Creates the shared prompt resolution service.
     *
     * @param properties generative prompt lookup settings
     * @param resourceLoader Spring resource loader used for classpath fallback resources
     * @return the shared prompt service used by generative feature modules
     */
    @Bean
    fun generativePromptService(
        properties: GenerativeProperties,
        resourceLoader: ResourceLoader,
    ): GenerativePromptService = GenerativePromptService(properties, resourceLoader)
}
