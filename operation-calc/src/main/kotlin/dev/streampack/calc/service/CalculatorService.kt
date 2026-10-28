/* Joseph B. Ottinger (C)2026 */
package dev.streampack.calc.service

import com.github.gbenroscience.parser.MathExpression
import com.github.gbenroscience.util.FunctionManager
import java.util.InputMismatchException
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Evaluates algebraic expressions, returning the result as a string or null for invalid input.
 *
 * ParserNG runs in strict mode, so an unknown name is an error, not zero.
 *
 * An expression can define functions for its own use, such as `f(x)=x+1;f(2)`. ParserNG keeps each
 * definition in a store that the whole JVM shares (`FunctionManager.FUNCTIONS`), and a definition
 * can replace a built-in function such as `sin`. Thus this service runs one calculation at a time,
 * and removes the functions that a calculation defined when it ends. A definition does not go to a
 * later calculation or to a different user.
 */
@Component
class CalculatorService {
    private val logger = LoggerFactory.getLogger(CalculatorService::class.java)

    /** Lets one calculation at a time use ParserNG's shared store. */
    private val lock = ReentrantLock()

    /**
     * Evaluates an expression.
     *
     * @param expression the expression, such as `2+2` or `f(x)=x*x;f(3)`
     * @return the result, such as `4.0`, or null when the expression is blank or not valid
     */
    fun evaluate(expression: String): String? {
        if (expression.isBlank()) return null
        return alone { solve(expression) }
    }

    /**
     * Runs a calculation alone, and removes the functions that it defined when it ends.
     *
     * This is the only code that handles ParserNG's shared store. ParserNG 3.0.9.2 adds
     * `FunctionScope`, which gives each expression its own store. When we use that version, remove
     * this function, [lock] and [forgetFunctionsAfter], and give each expression a new scope in
     * [solve]: `MathExpression(expression, false, true, FunctionScope.create())`.
     *
     * @param calculation the calculation to run
     * @return the result of [calculation]
     */
    private fun alone(calculation: () -> String?): String? = lock.withLock {
        val defined = FunctionManager.FUNCTIONS.keys.toSet()
        try {
            calculation()
        } finally {
            forgetFunctionsAfter(defined)
        }
    }

    /**
     * Solves an expression with ParserNG in strict mode.
     *
     * @param expression the expression that is not blank
     * @return the result, or null when ParserNG finds the expression not valid
     */
    private fun solve(expression: String): String? =
        try {
            val result = MathExpression(expression, false, true).solve()
            if (result == null || result == MathExpression.SYNTAX_ERROR) {
                logger.debug("Invalid expression: {}", expression)
                null
            } else {
                result
            }
        } catch (e: InputMismatchException) {
            logger.debug("Expression evaluation failed for '{}': {}", expression, e.message)
            null
        }

    /**
     * Removes each function that is not in [defined] from ParserNG's shared store. Removing a
     * definition of a built-in function, such as `sin`, makes the built-in function active again.
     *
     * @param defined the names that were in the store before the calculation started
     */
    private fun forgetFunctionsAfter(defined: Set<String>) {
        FunctionManager.FUNCTIONS.keys
            .filterNot { it in defined }
            .forEach { FunctionManager.delete(it) }
    }
}
