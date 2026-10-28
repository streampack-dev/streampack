/* Joseph B. Ottinger (C)2026 */
package dev.streampack.calc.service

import com.github.gbenroscience.util.FunctionManager
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
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

    @Test
    fun `returns null for an unknown function`() {
        assertNull(service.evaluate("foo(3)"))
        assertNull(service.evaluate("foo(3)+2"))
    }

    @Test
    fun `returns null for an undeclared variable`() {
        assertNull(service.evaluate("hello"))
        assertNull(service.evaluate("x+1"))
    }

    @Test
    fun `returns null for a misplaced operator`() {
        assertNull(service.evaluate("2+*3"))
    }

    @Test
    fun `evaluates built-in functions and constants`() {
        assertEquals("0.0", service.evaluate("sin(0)"))
        assertEquals(Math.PI.toString(), service.evaluate("pi"))
    }

    /**
     * ParserNG 3.0.9.1 gives Infinity for 0/0 (NaN before); pinned (#272). ParserNG 3.0.9.2 gives
     * NaN again, so this test changes to NaN with that upgrade.
     */
    @Test
    fun `gives infinity for zero over zero`() {
        assertEquals("Infinity", service.evaluate("0/0"))
    }

    @Test
    fun `gives NaN for the square root of a negative number`() {
        assertEquals("NaN", service.evaluate("sqrt(-1)"))
    }

    @Test
    fun `uses a function defined in the same expression`() {
        assertEquals("3.0", service.evaluate("calcdefined(x)=x+1;calcdefined(2)"))
        assertEquals("12.0", service.evaluate("calcproduct(x,y)=x*y;calcproduct(3,4)"))
        assertEquals(
            "8.0",
            service.evaluate("calcinner(x)=x+1;calcouter(x)=calcinner(x)*2;calcouter(3)"),
        )
    }

    @Test
    fun `forgets a function after the expression that defined it`() {
        assertEquals("3.0", service.evaluate("calcdefined(x)=x+1;calcdefined(2)"))
        assertNull(service.evaluate("calcdefined(2)"))
        assertTrue(FunctionManager.FUNCTIONS.isEmpty())
    }

    @Test
    fun `restores a built-in function after an expression redefines it`() {
        assertEquals("99.0", service.evaluate("sin(x)=99;sin(0)"))
        assertEquals("0.0", service.evaluate("sin(0)"))
    }

    @Test
    fun `forgets a variable after the expression that assigned it`() {
        assertEquals("10.0", service.evaluate("calcvar=5;calcvar*2"))
        assertNull(service.evaluate("calcvar"))
    }

    @Test
    fun `forgets a function after an expression that fails`() {
        assertNull(service.evaluate("calcdefined(x)=x+1;2+*3"))
        assertNull(service.evaluate("calcdefined(2)"))
    }

    @Test
    fun `keeps definitions apart when calls run at the same time`() {
        val pool = Executors.newFixedThreadPool(8)
        try {
            val calls =
                (1..200).map { n ->
                    pool.submit<Pair<Int, String?>> {
                        n to service.evaluate("calcshared(x)=x+$n;calcshared(0)")
                    }
                }
            calls
                .map { it.get(30, TimeUnit.SECONDS) }
                .forEach { (n, result) -> assertEquals("$n.0", result) }
        } finally {
            pool.shutdownNow()
        }
        assertTrue(FunctionManager.FUNCTIONS.isEmpty())
    }

    @Test
    fun `keeps each calculation apart when many kinds run at the same time`() {
        val pool = Executors.newFixedThreadPool(32)
        try {
            val calls =
                (1..5000).map { n ->
                    pool.submit<Pair<String?, String?>> {
                        when (n % 5) {
                            0 -> "$n.0" to service.evaluate("calcmixed(x)=x+$n;calcmixed(0)")
                            1 -> "99.0" to service.evaluate("sin(x)=99;sin(0)")
                            2 -> "0.0" to service.evaluate("sin(0)")
                            3 -> "${n * 2}.0" to service.evaluate("calcmixedvar=$n;calcmixedvar*2")
                            else -> null to service.evaluate("calcmixed(2)")
                        }
                    }
                }
            val wrong =
                calls.map { it.get(60, TimeUnit.SECONDS) }.filter { (want, got) -> want != got }
            assertEquals(emptyList<Pair<String?, String?>>(), wrong.take(20), "${wrong.size} wrong")
        } finally {
            pool.shutdownNow()
        }
        assertTrue(FunctionManager.FUNCTIONS.isEmpty())
    }
}
