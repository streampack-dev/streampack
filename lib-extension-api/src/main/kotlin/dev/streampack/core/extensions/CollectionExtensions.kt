/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.extensions

/** Joins a list of strings using English list conventions: "a", "a and b", "a, b, and c" */
fun List<String>.joinToStringWithAnd(): String {
    return when (size) {
        0 -> ""
        1 -> first()
        2 -> "${first()} and ${last()}"
        else -> "${dropLast(1).joinToString(", ")}, and ${last()}"
    }
}

/**
 * This word in the grammatical number for [count]: singular for exactly 1, plural for any other
 * count, 0 included. Only the word is returned; the caller writes the number, as in `"$count
 * ${"entry".pluralize(count)}"`.
 *
 * The plural follows regular English spelling: "entry" becomes "entries", "match" becomes
 * "matches", "key" becomes "keys", and anything else gains "s". A multi-word term changes its last
 * word only. Irregular words ("child", "person") get it wrong; pass [plural] for them.
 *
 * Why the rules are not more complete: messages name a short, fixed set of nouns, so an explicit
 * [plural] is cheaper than an inflection library.
 *
 * @param count how many there are; a negative count takes the plural.
 * @param plural the plural to use instead of the English rules, or null for the rules.
 * @return this word for 1, else [plural] or the rule-made plural.
 */
fun String.pluralize(count: Long, plural: String? = null): String =
    if (count == 1L) this else plural ?: englishPlural(this)

/** [pluralize] for an `Int` count. */
fun String.pluralize(count: Int, plural: String? = null): String = pluralize(count.toLong(), plural)

/**
 * This word in the grammatical number for the size of [collection]; see [pluralize] for the rules.
 *
 * @param collection the things counted; may be empty, which takes the plural.
 * @param plural the plural to use instead of the English rules, or null for the rules.
 */
fun String.pluralize(collection: Collection<*>, plural: String? = null): String =
    pluralize(collection.size.toLong(), plural)

private val SIBILANT_ENDINGS = listOf("s", "x", "z", "ch", "sh")

private fun englishPlural(word: String): String {
    val lower = word.lowercase()
    return when {
        lower.length > 1 && lower.endsWith("y") && lower[lower.length - 2] !in "aeiou" ->
            word.dropLast(1) + "ies"
        SIBILANT_ENDINGS.any { lower.endsWith(it) } -> word + "es"
        else -> word + "s"
    }
}
