/* Joseph B. Ottinger (C)2026 */
package dev.streampack.taxonomy

import dev.streampack.taxonomy.model.TagHint
import dev.streampack.taxonomy.model.TagHintKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** The rule-based hints that queue a new tag for review (#140); never folded, only queued. */
class TagHintTests {
    private val known =
        setOf("compilers", "framework", "build tools", "java", "kotlin", "spring boot", "new", "j")

    private fun hint(name: String) = TagVocabulary.hint(name, known)

    @Test
    fun `a singular of an existing plural is a plural hint`() {
        assertEquals(TagHint(TagHintKind.PLURAL, listOf("compilers")), hint("compiler"))
    }

    @Test
    fun `a plural of an existing singular is a plural hint`() {
        assertEquals(TagHint(TagHintKind.PLURAL, listOf("framework")), hint("frameworks"))
    }

    @Test
    fun `a multi-word plural pair is matched on its trailing s`() {
        assertEquals(TagHint(TagHintKind.PLURAL, listOf("build tools")), hint("build tool"))
    }

    @Test
    fun `news against new is a true trailing-s pair, so it is hinted, never folded`() {
        // Decided (#140): the rule is a plain trailing s, so news/new is queued for an admin, who
        // keeps it; resolve still stores news as written.
        assertEquals(TagHint(TagHintKind.PLURAL, listOf("new")), hint("news"))
    }

    @Test
    fun `news is not hinted when new isn't a tag`() {
        assertNull(TagVocabulary.hint("news", setOf("newsletter", "nu")))
    }

    @Test
    fun `a stem shorter than two characters is no pair`() {
        assertNull(hint("js"))
    }

    @Test
    fun `endings other than a single trailing s are not matched`() {
        assertNull(TagVocabulary.hint("classes", setOf("class")))
        assertNull(TagVocabulary.hint("compilerss", setOf("compiler")))
    }

    @Test
    fun `several words that are each a tag are a missing comma`() {
        assertEquals(
            TagHint(TagHintKind.MISSING_COMMA, listOf("java", "kotlin")),
            hint("java kotlin"),
        )
    }

    @Test
    fun `a missing comma may join multi-word tags, fewest parts first`() {
        assertEquals(
            TagHint(TagHintKind.MISSING_COMMA, listOf("spring boot", "kotlin")),
            hint("spring boot kotlin"),
        )
    }

    @Test
    fun `words that aren't all tags are no missing comma`() {
        assertNull(hint("java streams"))
        assertNull(hint("quarkus"))
    }
}
