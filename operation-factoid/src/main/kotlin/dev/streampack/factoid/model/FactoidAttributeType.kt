/* Joseph B. Ottinger (C)2026 */
package dev.streampack.factoid.model

import dev.streampack.core.extensions.endsWithPunctuation
import dev.streampack.core.extensions.joinToStringWithAnd
import dev.streampack.core.extensions.pluralize

/** Factoid attribute types with rendering and mutability semantics */
enum class FactoidAttributeType(val mutable: Boolean = true, val includeInSummary: Boolean = true) {
    TEXT {
        override fun doRender(selector: String, value: String): String {
            val data =
                if (value.startsWith("<reply>", true)) {
                    value.substring("<reply>".length)
                } else {
                    "${selector.lowercase()} is $value"
                }
            return if (!data.endsWithPunctuation()) {
                "${data}."
            } else {
                data
            }
        }
    },
    URLS {
        override fun doRender(selector: String, value: String): String {
            return renderPluralValue("URL", value)
        }
    },
    TAGS {
        override fun doRender(selector: String, value: String): String {
            return renderPluralValue("Tag", value)
        }
    },
    LANGUAGES {
        override fun doRender(selector: String, value: String): String {
            return renderPluralValue("Language", value)
        }
    },
    TYPE {
        override fun doRender(selector: String, value: String): String {
            return renderPluralValue("Type", value)
        }
    },
    SEEALSO {
        override fun doRender(selector: String, value: String): String {
            val values = value.split(",").map { it.trim() }
            return "See also: ${values.joinToStringWithAnd()}"
        }
    },
    SEE(includeInSummary = false),
    MAVEN(includeInSummary = false) {
        override fun doRender(selector: String, value: String): String {
            val parts = value.split(":")
            return if (parts.size >= 2) {
                val group = parts[0]
                val artifact = parts[1]
                val url = "https://mvnrepository.com/artifact/$group/$artifact"
                "Maven: $value $url"
            } else {
                "Maven: $value"
            }
        }
    },
    FORGET(mutable = false, includeInSummary = false),
    UNKNOWN(mutable = false, includeInSummary = false),
    INFO(mutable = false, includeInSummary = false),
    LITERAL(mutable = false, includeInSummary = false),
    LOCK(mutable = false, includeInSummary = false),
    UNLOCK(mutable = false, includeInSummary = false),
    STATS(mutable = false, includeInSummary = false);

    open fun doRender(selector: String, value: String): String = value

    fun render(selector: String, value: String?) =
        if (value != null) {
            doRender(selector, value)
        } else ""

    companion object {
        fun renderPluralValue(name: String, value: String): String {
            val values = value.split(",").map { it.trim() }
            return "${name.pluralize(values)}: ${values.joinToStringWithAnd()}"
        }

        /** Maps lowercase attribute names (and singular forms) to their enum values */
        val knownAttributes: Map<String, FactoidAttributeType>

        init {
            val map = mutableMapOf<String, FactoidAttributeType>()
            FactoidAttributeType.entries.forEach {
                val name = it.name.lowercase()
                map[name] = it
                if (name.endsWith("s", true)) {
                    map[name.removeSuffix("s")] = it
                }
            }
            knownAttributes = map
        }
    }
}
