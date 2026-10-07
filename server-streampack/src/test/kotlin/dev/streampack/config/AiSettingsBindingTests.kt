/* Joseph B. Ottinger (C)2026 */
package dev.streampack.config

import dev.streampack.ai.config.AiProperties
import java.time.Duration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.core.env.StandardEnvironment
import org.springframework.core.env.SystemEnvironmentPropertySource
import org.springframework.core.io.FileSystemResource

/**
 * The deployment's short AI_* names reach `streampack.ai` through the main application.yml, and the
 * STREAMPACK_AI_* names still win over them, as the environment outranks the file. The main file is
 * read from the source tree: the test one shadows it on the classpath.
 */
class AiSettingsBindingTests {
    private fun bind(environment: Map<String, Any>): AiProperties {
        val env = StandardEnvironment()
        env.propertySources.replace(
            StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
            SystemEnvironmentPropertySource(
                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                environment,
            ),
        )
        YamlPropertySourceLoader()
            .load("application.yml", FileSystemResource("src/main/resources/application.yml"))
            .forEach { env.propertySources.addLast(it) }
        return Binder.get(env).bind("streampack.ai", AiProperties::class.java).get()
    }

    @Test
    fun `unset, the file's defaults match the code's`() {
        val bound = bind(mapOf("AI_ENABLED" to "true"))
        val code = AiProperties()
        assertEquals(code.model, bound.model)
        assertEquals(code.moderationModel, bound.moderationModel)
        assertEquals(code.maxTokens, bound.maxTokens)
        assertEquals(code.timeout, bound.timeout)
        assertEquals(code.maxRetries, bound.maxRetries)
        assertEquals(code.thinking, bound.thinking)
        // An unset AI_EFFORT is blank, which the chat model's configuration treats as unset
        assertEquals("", bound.effort.orEmpty())
    }

    @Test
    fun `the short names bind`() {
        val bound =
            bind(
                mapOf(
                    "AI_ENABLED" to "true",
                    "AI_MODEL" to "claude-sonnet-5-5",
                    "AI_MODERATION_MODEL" to "claude-haiku-4-5",
                    "AI_MAX_TOKENS" to "2048",
                    "AI_TIMEOUT" to "20s",
                    "AI_MAX_RETRIES" to "0",
                    "AI_EFFORT" to "low",
                )
            )
        assertEquals("claude-sonnet-5-5", bound.model)
        assertEquals("claude-haiku-4-5", bound.moderationModel)
        assertEquals(2048, bound.maxTokens)
        assertEquals(Duration.ofSeconds(20), bound.timeout)
        assertEquals(0, bound.maxRetries)
        assertEquals("low", bound.effort)
    }

    @Test
    fun `the long names still win`() {
        val bound =
            bind(
                mapOf(
                    "AI_MODEL" to "claude-sonnet-5-5",
                    "STREAMPACK_AI_MODEL" to "claude-opus-5-5",
                    "AI_MODERATION_MODEL" to "claude-haiku-4-5",
                    "STREAMPACK_AI_MODERATION_MODEL" to "claude-sonnet-5-5",
                )
            )
        assertEquals("claude-opus-5-5", bound.model)
        assertEquals("claude-sonnet-5-5", bound.moderationModel)
    }
}
