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

/** Appends "s" to a word when the collection has more than one element */
fun String.pluralize(collection: Collection<*>): String {
    return if (collection.size == 1) this else "${this}s"
}
