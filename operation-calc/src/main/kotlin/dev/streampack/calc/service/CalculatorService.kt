/* Joseph B. Ottinger (C)2026 */
package dev.streampack.calc.service

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import parser.MathExpression

/** Evaluates algebraic expressions, returning the result as a string or null for invalid input. */
@Component
class CalculatorService {
    private val logger = LoggerFactory.getLogger(CalculatorService::class.java)

    fun evaluate(expression: String): String? {
        if (expression.isBlank()) return null
        return try {
            val result = MathExpression(expression).solve()
            if (result == null || result == "SYNTAX ERROR") {
                logger.debug("Invalid expression: {}", expression)
                null
            } else {
                result
            }
        } catch (e: Exception) {
            logger.debug("Expression evaluation failed for '{}': {}", expression, e.message)
            null
        }
    }
}
