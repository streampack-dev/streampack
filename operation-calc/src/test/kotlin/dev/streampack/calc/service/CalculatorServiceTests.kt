/* Joseph B. Ottinger (C)2026 */
package dev.streampack.calc.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest

@SpringBootTest
class CalculatorServiceTests {
    @Autowired lateinit var service: CalculatorService

    @Test
    fun `evaluates basic arithmetic`() {
        val result = service.evaluate("(42/3.14)*4")
        assertNotNull(result)
    }

    @Test
    fun `evaluates division`() {
        val result = service.evaluate("87238.0/127.17")
        assertNotNull(result)
    }

    @Test
    fun `evaluates addition`() {
        val result = service.evaluate("2+2")
        assertNotNull(result)
        assertEquals("4.0", result)
    }

    @Test
    fun `returns null for garbage input`() {
        assertNull(service.evaluate("hello world"))
    }

    @Test
    fun `returns null for empty string`() {
        assertNull(service.evaluate(""))
    }

    @Test
    fun `returns null for mismatched parentheses`() {
        assertNull(service.evaluate("((()"))
    }

    @Test
    fun `handles division by zero`() {
        val result = service.evaluate("1/0")
        assertNotNull(result)
        assertEquals("Infinity", result)
    }
}
