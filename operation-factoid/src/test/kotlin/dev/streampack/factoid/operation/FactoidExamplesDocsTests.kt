/* Joseph B. Ottinger (C)2026 */
package dev.streampack.factoid.operation

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.readText
import kotlin.io.path.relativeTo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

/**
 * The docs quote the factoid examples exactly. A page quotes an example with a marker, an HTML
 * comment `factoid-example: <id>`, followed by a `text` code block holding the example's
 * [transcript][FactoidExample.transcript]. An example with a `doc` page must be quoted there, and
 * every marker must name an example that lists that page.
 */
class FactoidExamplesDocsTests {

    @ParameterizedTest(name = "{0}")
    @MethodSource("quoted")
    fun `the docs quote the example`(example: FactoidExample) {
        val page = repository.resolve(example.doc!!)
        assertTrue(Files.exists(page), "${example.doc} is missing")
        val quotes = quotes(page)[example.id]
        assertTrue(quotes != null, "${example.doc} has no marker for ${example.id}")
        assertEquals(example.transcript(), quotes, "${example.doc}: ${example.id}")
    }

    @Test
    fun `every marker names its example`() {
        val examples = FactoidExamples.load().associateBy { it.id }
        val wrong =
            Files.walk(repository.resolve("docs")).use { paths ->
                paths
                    .filter { it.extension == "md" }
                    .toList()
                    .flatMap { page ->
                        val name = page.relativeTo(repository).toString()
                        quotes(page).keys.filter { examples[it]?.doc != name }.map { "$name: $it" }
                    }
            }
        assertEquals(emptyList<String>(), wrong)
    }

    companion object {
        /** The repository root: tests run in the module's directory. */
        private val repository: Path = Path.of("..").toAbsolutePath().normalize()

        private val MARKER =
            Regex(
                "<!-- factoid-example: ([\\w-]+) -->\\s*\\n```text\\n(.*?)\\n```",
                RegexOption.DOT_MATCHES_ALL,
            )

        @JvmStatic
        fun quoted(): List<FactoidExample> = FactoidExamples.load().filter { it.doc != null }

        /** Each marker's example id and the block after it. */
        private fun quotes(page: Path): Map<String, String> =
            MARKER.findAll(page.readText()).associate { it.groupValues[1] to it.groupValues[2] }
    }
}
