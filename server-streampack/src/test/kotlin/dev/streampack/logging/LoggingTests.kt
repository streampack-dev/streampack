/* Joseph B. Ottinger (C)2026 */
package dev.streampack.logging

import dev.streampack.ServerStreampackApplication
import dev.streampack.test.TestChannelConfiguration
import java.io.File
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.slf4j.LoggerFactory
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.context.annotation.Import

/**
 * Rainbow Gum writes the server's logs: to the console, and to a rolling file in the log directory
 * (`STREAMPACK_LOG_DIR`, `logs` by default). The image declares that directory a volume, so the
 * history can outlive the container when `deploy` mounts it.
 *
 * A console line starts with its level: Docker stamps each line with its own time, so a date of
 * ours would repeat it, and the process id is always 1 in a container. A file line keeps the full
 * date, because the file spans days.
 */
@SpringBootTest(
    classes = [ServerStreampackApplication::class],
    properties = ["spring.main.allow-bean-definition-overriding=true"],
)
@Import(TestChannelConfiguration::class)
@ExtendWith(OutputCaptureExtension::class)
class LoggingTests {

    private val logFile = File(System.getenv("STREAMPACK_LOG_DIR") ?: "logs", "streampack.log")

    @Test
    fun `Rainbow Gum is the provider`() {
        val factory = LoggerFactory.getILoggerFactory().javaClass.name
        assertTrue(factory.startsWith("io.jstach.rainbowgum"), factory)
    }

    @Test
    fun `Logback is absent`() {
        assertThrows(ClassNotFoundException::class.java) {
            Class.forName("ch.qos.logback.classic.LoggerContext")
        }
    }

    @Test
    fun `a line reaches the file`() {
        val marker = logMarker("log file check")
        assertTrue(logFile.exists(), "no log file at $logFile")
        assertTrue(logFile.readText().contains(marker))
    }

    @Test
    fun `the image declares the volume`() {
        val dockerfile = File("../Dockerfile").readText()
        assertTrue(dockerfile.contains("""VOLUME ["/opt/streampack/logs"]"""))
    }

    @Test
    fun `a console line starts with its level`(output: CapturedOutput) {
        val marker = logMarker("console line check")
        val line = output.out.lines().single { marker in it }
        assertTrue(line.startsWith("INFO "), line)
    }

    @Test
    fun `a file line starts with its date`() {
        val marker = logMarker("file line check")
        val line = logFile.readLines().single { marker in it }
        assertTrue(DATED_INFO.matches(line), line)
    }

    @Test
    fun `the level of dev streampack is INFO`() {
        val logger = LoggerFactory.getLogger("dev.streampack.logging.Probe")
        assertEquals(listOf(true, false), listOf(logger.isInfoEnabled, logger.isDebugEnabled))
    }

    private fun logMarker(text: String) =
        "$text ${UUID.randomUUID()}"
            .also { LoggerFactory.getLogger(LoggingTests::class.java).info(it) }

    private companion object {
        val DATED_INFO = Regex("""\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}\S* INFO .*""")
    }
}
