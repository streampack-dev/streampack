/* Joseph B. Ottinger (C)2026 */
package dev.streampack.calc.service

import com.github.gbenroscience.parser.MathExpression
import com.github.gbenroscience.util.FunctionScope
import java.util.InputMismatchException
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Evaluates algebraic expressions, returning the result as a string or null for invalid input.
 *
 * ParserNG runs in strict mode, so an unknown name is an error, not zero.
 *
 * An expression can define functions for its own use, such as `f(x)=x+1;f(2)`. Each expression gets
 * a new ParserNG `FunctionScope`, so a definition, even one that replaces a built-in function such
 * as `sin`, does not go to a later calculation or to a different user, and calculations can run at
 * the same time.
 */
@Component
class CalculatorService {
    private val logger = LoggerFactory.getLogger(CalculatorService::class.java)

    /**
     * Evaluates an expression.
     *
     * @param expression the expression, such as `2+2` or `f(x)=x*x;f(3)`
     * @return the result, such as `4.0`, or null when the expression is blank or not valid
     */
    fun evaluate(expression: String): String? {
        if (expression.isBlank()) return null
        return solve(expression)
    }

    /**
     * Solves an expression with ParserNG in strict mode, in a function scope of its own.
     *
     * @param expression the expression that is not blank
     * @return the result, or null when ParserNG finds the expression not valid
     */
    private fun solve(expression: String): String? =
        try {
            val result = MathExpression(expression, false, true, FunctionScope.create()).solve()
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
}
