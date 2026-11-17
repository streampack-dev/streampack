/* Joseph B. Ottinger (C)2026 */
package dev.streampack.test

import io.jstach.rainbowgum.RainbowGum
import io.jstach.rainbowgum.output.ListLogOutput
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.io.PrintStream

/**
 * One log event captured by [captureLog].
 *
 * @property level the SLF4J level name: `TRACE`, `DEBUG`, `INFO`, `WARN` or `ERROR`.
 * @property logger the full logger name, usually the class name.
 * @property message the message with its arguments filled in; its first line only when it was read
 *   from the console.
 */
data class LogLine(val level: String, val logger: String, val message: String)

/**
 * Runs [action] and returns its result with the log events it wrote at INFO or above.
 *
 * Why two sources: the logger is Rainbow Gum, which has no appender a test can attach at run time.
 * - In a Spring test, Spring's Rainbow Gum writes the events of loggers made before [action] to the
 *   console. Standard out is teed for the length of [action] and its lines are parsed. That needs
 *   the console in `follow` mode (set for Surefire in the parent `pom.xml`) and the test console
 *   pattern from `streampack-test-defaults.yml`, which writes the full logger name.
 * - Outside Spring nothing writes to the console, so for the length of [action] a Rainbow Gum that
 *   writes only to a list takes the global place. It also receives events of loggers made during
 *   [action]. The global one before it, if any, goes back afterwards.
 *
 * The result holds the list's events first, then the console's; each source keeps its order. A
 * Rainbow Gum replays events it queued before it started into the next one set, so the list is
 * cleared after the swap. Other threads' events while [action] runs are captured too, so filter by
 * [LogLine.logger]. Not safe for tests that run in parallel in one JVM.
 *
 * @param action the code whose logging is captured; its exception propagates after the restore.
 * @return the result of [action] and the captured lines.
 */
fun <T> captureLog(action: () -> T): Pair<T, List<LogLine>> {
    val previous = RainbowGum.getOrNull()
    val list = ListLogOutput()
    val capturing =
        RainbowGum.builder().route { route -> route.appender("capture") { it.output(list) } }.set()
    list.clear()
    val console = ByteArrayOutputStream()
    val out = System.out
    System.setOut(PrintStream(Tee(out, console), true, Charsets.UTF_8))
    val result =
        try {
            action()
        } finally {
            System.setOut(out)
            capturing.close()
            previous?.let { RainbowGum.set(it) }
        }
    val listed =
        list.events().map { (event, _) ->
            LogLine(
                slf4jLevel(event.level()),
                event.loggerName(),
                StringBuilder().also { event.formattedMessage(it) }.toString(),
            )
        }
    val printed =
        console.toString(Charsets.UTF_8).lines().mapNotNull { line ->
            CONSOLE_LINE.matchEntire(line)?.destructured?.let { (level, logger, message) ->
                LogLine(level, logger, message)
            }
        }
    return result to listed + printed
}

/** A console line in the test pattern: `%-5level [%thread] %logger : %msg`. */
private val CONSOLE_LINE = Regex("""^(TRACE|DEBUG|INFO|WARN|ERROR)\s+\[[^\]]*\] (\S+) : (.*)$""")

private fun slf4jLevel(level: System.Logger.Level): String =
    if (level == System.Logger.Level.WARNING) "WARN" else level.name

/** Writes to both streams; closing it leaves both open. */
private class Tee(private val first: OutputStream, private val second: OutputStream) :
    OutputStream() {
    override fun write(b: Int) {
        first.write(b)
        second.write(b)
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        first.write(b, off, len)
        second.write(b, off, len)
    }

    override fun flush() {
        first.flush()
        second.flush()
    }
}
