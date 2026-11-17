/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.extensions

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import tools.jackson.module.kotlin.jacksonObjectMapper
import tools.jackson.module.kotlin.readValue

/** `pluralize`: the word for a count, by English rules, or by an explicit plural. */
class PluralizeTests {

    data class Case(
        val word: String,
        val count: Long,
        val expected: String,
        val plural: String? = null,
        val collection: Boolean = false,
    )

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    fun `word for count`(name: String, case: Case) {
        val actual =
            if (case.collection) case.word.pluralize(List(case.count.toInt()) { it }, case.plural)
            else case.word.pluralize(case.count, case.plural)
        assertEquals(case.expected, actual)
    }

    companion object {
        @JvmStatic
        fun cases(): List<Arguments> {
            val json =
                PluralizeTests::class.java.getResource("/pluralize/cases.json")?.readText()
                    ?: error("pluralize/cases.json is missing")
            return jacksonObjectMapper().readValue<List<Case>>(json).map {
                val via = if (it.collection) "collection of ${it.count}" else "${it.count}"
                Arguments.of("${it.word} for $via is ${it.expected}", it)
            }
        }
    }
}
