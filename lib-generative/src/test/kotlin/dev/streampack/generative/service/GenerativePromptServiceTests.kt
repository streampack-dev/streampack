/* Joseph B. Ottinger (C)2026 */
package dev.streampack.generative.service

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest

@SpringBootTest(properties = ["streampack.generative.prompt-dir=/tmp/streampack-generative-tests"])
class GenerativePromptServiceTests {

    @Autowired lateinit var promptService: GenerativePromptService

    private val promptDir: Path = Path.of("/tmp/streampack-generative-tests")

    @AfterEach
    fun tearDown() {
        if (Files.exists(promptDir)) {
            Files.list(promptDir).use { paths -> paths.forEach { Files.deleteIfExists(it) } }
        }
    }

    @Test
    fun `falls back to bundled classpath prompt when no override exists`() {
        val rendered =
            promptService.render(
                "suggest-prompt",
                "dev/streampack/generative/test-prompts/suggest-prompt.txt",
                mapOf("sourceTitle" to "Signals and Noise"),
            )

        assertEquals(
            "You draft a technical blog summary from extracted source text.",
            rendered.lineSequence().first().trim(),
        )
    }

    @Test
    fun `filesystem txt prompt overrides bundled prompt`() {
        promptDir.createDirectories()
        promptDir.resolve("suggest-prompt.txt").writeText("OVERRIDE PROMPT\n")

        val rendered =
            promptService.render(
                "suggest-prompt",
                "dev/streampack/generative/test-prompts/suggest-prompt.txt",
                mapOf("sourceTitle" to "Signals and Noise"),
            )

        assertEquals("OVERRIDE PROMPT\n", rendered)
    }

    @Test
    fun `filesystem clj prompt can render dynamically from context`() {
        promptDir.createDirectories()
        promptDir
            .resolve("suggest-prompt.clj")
            .writeText(
                """
                (fn [ctx]
                  (str "lane=" (:lane ctx) " title=" (:sourceTitle ctx)))
                """
                    .trimIndent()
            )

        val rendered =
            promptService.render(
                "suggest-prompt",
                "dev/streampack/generative/test-prompts/suggest-prompt.txt",
                mapOf("lane" to "essay", "sourceTitle" to "Signals and Noise"),
            )

        assertEquals("lane=essay title=Signals and Noise", rendered)
    }
}
