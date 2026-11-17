/* Joseph B. Ottinger (C)2026 */
package dev.streampack.test

import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory

/**
 * Tests log through Rainbow Gum, the same provider as the server; Logback is not on the classpath.
 */
class TestLoggingTests {

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
}
