/* Joseph B. Ottinger (C)2026 */
package dev.streampack.cal.operation

import dev.streampack.cal.service.CalendarService
import dev.streampack.core.model.OperationOutcome
import dev.streampack.core.model.OperationResult
import dev.streampack.core.parser.ChoiceArgType
import dev.streampack.core.parser.CommandArgSpec
import dev.streampack.core.parser.CommandMatchResult
import dev.streampack.core.parser.CommandPattern
import dev.streampack.core.parser.CommandPatternMatcher
import dev.streampack.core.service.TypedOperation
import java.time.LocalDate
import org.springframework.messaging.Message
import org.springframework.stereotype.Component

/** Reports today/tomorrow dates in configurable calendar systems. */
@Component
class CalendarDayOperation(private val calendarService: CalendarService) :
    TypedOperation<String>(String::class) {

    override val operationGroup: String = "cal"

    override fun canHandle(payload: String, message: Message<*>): Boolean =
        matcher().match(payload) != null

    override fun handle(payload: String, message: Message<*>): OperationOutcome {
        return when (val result = matcher().match(payload)) {
            is CommandMatchResult.Match -> handleMatch(result)
            is CommandMatchResult.InvalidArgument -> {
                val command = displayName(result.patternName)
                OperationResult.Error(
                        "Unknown calendar: ${result.token}. Use '${result.patternName} list' to see available calendars."
                    )
                    .also {
                        logger.debug("{} command invalid calendar '{}'", command, result.token)
                    }
            }
            is CommandMatchResult.MissingArguments -> {
                val command = result.patternName
                OperationResult.Error("Usage: $command [list|calendar]")
            }
            is CommandMatchResult.TooManyArguments -> {
                val command = result.patternName
                OperationResult.Error("Usage: $command [list|calendar]")
            }
            null -> OperationResult.NotHandled
        }
    }

    private fun handleMatch(match: CommandMatchResult.Match): OperationOutcome {
        val command = match.patternName
        val date = if (command == "today") LocalDate.now() else LocalDate.now().plusDays(1)
        val prefix = displayName(command)
        val target =
            match.captures["target"] as? String
                ?: return OperationResult.Success("$prefix is ${calendarService.formatDate(date)}")

        if (target.equals("list", ignoreCase = true)) {
            val names = calendarService.listCalendars().joinToString(", ") { it.name }
            return OperationResult.Success("Available calendars: $names")
        }

        val formatted = calendarService.formatDate(date, target)
        return if (formatted != null) {
            val displayName = calendarService.getCalendar(target)!!.displayName
            OperationResult.Success("$prefix is $formatted ($displayName)")
        } else {
            OperationResult.Error(
                "Unknown calendar: $target. Use '$command list' to see available calendars."
            )
        }
    }

    private fun displayName(command: String): String =
        if (command == "today") "Today" else "Tomorrow"

    private fun matcher(): CommandPatternMatcher {
        val options = calendarService.listCalendars().map { it.name }.toSet() + "list"
        val targetArg = CommandArgSpec("target", ChoiceArgType(options))
        return CommandPatternMatcher(
            listOf(
                CommandPattern(
                    name = "today",
                    literals = listOf("today"),
                    args = listOf(targetArg),
                ),
                CommandPattern(name = "today", literals = listOf("today")),
                CommandPattern(
                    name = "tomorrow",
                    literals = listOf("tomorrow"),
                    args = listOf(targetArg),
                ),
                CommandPattern(name = "tomorrow", literals = listOf("tomorrow")),
            )
        )
    }
}
