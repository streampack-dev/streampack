/* Joseph B. Ottinger (C)2026 */
package dev.streampack.hangman.service

import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

class ResourceWordSourceTests {
    @Test
    fun `by default the words come from the list in the jar`() {
        assertThat(ResourceWordSource(ResourceWordSource.DEFAULT).randomWord()).isNotBlank()
    }

    @Test
    fun `a list outside the jar replaces it, one word to a line`(@TempDir dir: Path) {
        val list = Files.writeString(dir.resolve("words.txt"), "  Kotlin \n\nkotlin\n")
        val source = ResourceWordSource("file:$list")
        assertThat((1..20).map { source.randomWord() }.toSet()).containsExactly("kotlin")
    }

    @Test
    fun `a missing or empty list stops the start, naming it`(@TempDir dir: Path) {
        assertThat(assertThrows<IllegalStateException> { ResourceWordSource("file:$dir/none.txt") })
            .hasMessageContaining("none.txt")
        val empty = Files.writeString(dir.resolve("empty.txt"), "\n \n")
        assertThat(assertThrows<IllegalStateException> { ResourceWordSource("file:$empty") })
            .hasMessageContaining("no words")
    }
}
