/* Joseph B. Ottinger (C)2026 */
package dev.streampack.factoid.operation

import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

/**
 * The factoid conversations in `factoid/examples.json`. [FactoidOperationTests] runs each one
 * against the bot, and [FactoidExamplesDocsTests] checks that the docs quote them exactly, so the
 * docs, the examples and the code cannot disagree.
 *
 * @property id a unique, stable name; a docs page names it in its marker.
 * @property title what the example shows, used as the test's display name.
 * @property doc the repository-relative page that quotes the example, or null if none does.
 * @property steps the messages, in order.
 */
data class FactoidExample(
    val id: String,
    val title: String,
    val doc: String?,
    val steps: List<FactoidExampleStep>,
) {
    override fun toString(): String = "$id: $title"

    /**
     * The example as the docs show it: one `<nick> message` line per message and one `<bot> reply`
     * line per expected reply. A choice is shown as `one of: a | b`, and no answer as `(no reply)`.
     * `{{ref:name}}` is shown as `!name`, as IRC shows it with the default signal character.
     *
     * @throws IllegalStateException when a step's expectation can't be shown exactly: no
     *   expectation, an error without its text, or a `contains` check.
     */
    fun transcript(): String =
        steps.joinToString("\n") { step ->
            "<${step.nick}> ${step.say}\n<bot> ${step.shownReply(id)}"
        }
}

/**
 * One message and what the bot must answer. At most one of [reply], [error], [startsWith], [oneOf]
 * and [silent] is set; with none set, the answer is not checked.
 *
 * @property say the message, as a user types it.
 * @property role `USER` or `ADMIN`.
 * @property reply the exact answer.
 * @property error true for any error, or the exact error text.
 * @property startsWith the answer's start, for answers that hold a time.
 * @property oneOf the answers a choice can give.
 * @property repeat how many times to ask, for [oneOf]; at least 1.
 * @property silent true when the bot must not answer at all.
 */
data class FactoidExampleStep(
    val say: String,
    val role: String,
    val reply: String?,
    val error: Any?,
    val startsWith: String?,
    val oneOf: List<String>?,
    val repeat: Int,
    val silent: Boolean,
) {
    /** The speaker's nick: `admin` for an admin, otherwise `alice`. */
    val nick: String
        get() = if (role == "ADMIN") "admin" else "alice"

    /** The answer as the docs show it; see [FactoidExample.transcript]. */
    fun shownReply(exampleId: String): String {
        val shown =
            when {
                reply != null -> reply
                error is String -> error
                startsWith != null -> "$startsWith…"
                oneOf != null -> "one of: " + oneOf.joinToString(" | ")
                silent -> "(no reply)"
                else ->
                    error(
                        "$exampleId: \"$say\" has no exact answer, so the docs can't quote it",
                    )
            }
        return shown.replace(REF_TOKEN) { "!${it.groupValues[1]}" }
    }

    companion object {
        private val REF_TOKEN = Regex("\\{\\{ref:([^}]+)}}")
    }
}

/** Loads `factoid/examples.json` from the test classpath. */
object FactoidExamples {
    /**
     * @return every example, in file order.
     * @throws IllegalStateException when the file is missing.
     */
    fun load(): List<FactoidExample> {
        val json =
            FactoidExamples::class.java.getResource("/factoid/examples.json")?.readText()
                ?: error("factoid/examples.json is missing")
        val root = JsonMapper.builder().build().readTree(json)
        return (0 until root.size()).map { example(root[it]) }
    }

    private fun example(node: JsonNode): FactoidExample {
        val steps = node["steps"]
        return FactoidExample(
            id = node["id"].asString(),
            title = node["title"].asString(),
            doc = node["doc"]?.asString(),
            steps = (0 until steps.size()).map { step(steps[it]) },
        )
    }

    private fun step(node: JsonNode): FactoidExampleStep {
        val error = node["error"]
        return FactoidExampleStep(
            say = node["say"].asString(),
            role = node["role"]?.asString() ?: "USER",
            reply = node["reply"]?.asString(),
            error =
                when {
                    error == null -> null
                    error.isBoolean -> error.asBoolean()
                    else -> error.asString()
                },
            startsWith = node["startsWith"]?.asString(),
            oneOf =
                node["oneOf"]?.let { list -> (0 until list.size()).map { list[it].asString() } },
            repeat = node["repeat"]?.asInt() ?: 1,
            silent = node["silent"]?.asBoolean() ?: false,
        )
    }
}
