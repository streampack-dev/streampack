/* Joseph B. Ottinger (C)2026 */
package dev.streampack.startrader.engine

import dev.streampack.startrader.config.SimulationConfig
import dev.streampack.startrader.model.Commodity
import dev.streampack.startrader.model.Planet
import dev.streampack.startrader.model.UniverseState
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Component
class ProductionEngine {
    private val logger = LoggerFactory.getLogger(ProductionEngine::class.java)

    /**
     * Run production for all planets. Each planet produces output based on its production rates,
     * constrained by available input commodities from the production matrix. Also consumes
     * population-based demand separately.
     */
    fun produce(state: UniverseState, config: SimulationConfig): UniverseState {
        val updatedPlanets = state.planets.map { planet -> producePlanet(planet, config) }
        return state.copy(planets = updatedPlanets)
    }

    /** Consume population-based demand for each planet */
    fun consume(state: UniverseState, config: SimulationConfig): UniverseState {
        val updatedPlanets = state.planets.map { planet -> consumePlanet(planet, config) }
        return state.copy(planets = updatedPlanets)
    }

    private fun producePlanet(planet: Planet, config: SimulationConfig): Planet {
        val supply = planet.supply.toMutableMap()

        for ((commodity, productionRate) in planet.production) {
            if (productionRate <= 0.0) continue

            val inputs = config.productionMatrix.inputs[commodity] ?: emptyMap()
            val constraintFactor = calculateConstraintFactor(inputs, supply)
            val produced = productionRate * constraintFactor

            if (produced > 0.0) {
                // Consume inputs proportional to actual production
                for ((input, rate) in inputs) {
                    val consumed = rate * produced
                    supply[input] = max(0.0, (supply[input] ?: 0.0) - consumed)
                }
                // Add produced output
                supply[commodity] = (supply[commodity] ?: 0.0) + produced

                logger.debug(
                    "Planet {} produced {:.2f} {} (constraint factor: {:.2f})",
                    planet.name,
                    produced,
                    commodity.displayName,
                    constraintFactor,
                )
            }
        }

        return planet.copy(supply = supply)
    }

    /**
     * Calculate how much production is constrained by input availability using a geometric mean.
     * Returns a factor between 0.0 and 1.0. Every scarce input drags production down proportionally
     * rather than only the single worst bottleneck mattering.
     */
    private fun calculateConstraintFactor(
        inputs: Map<Commodity, Double>,
        supply: Map<Commodity, Double>,
    ): Double {
        if (inputs.isEmpty()) return 1.0

        var product = 1.0
        var count = 0
        for ((input, requiredRate) in inputs) {
            if (requiredRate <= 0.0) continue
            val available = supply[input] ?: 0.0
            val factor = min(1.0, available / requiredRate)
            product *= factor
            count++
        }
        if (count == 0) return 1.0
        return product.pow(1.0 / count)
    }

    private fun consumePlanet(planet: Planet, config: SimulationConfig): Planet {
        val supply = planet.supply.toMutableMap()

        for ((commodity, rate) in config.populationConsumptionRates) {
            val consumed = planet.population * rate
            val available = supply[commodity] ?: 0.0
            supply[commodity] = max(0.0, available - consumed)

            logger.debug(
                "Planet {} consumed {:.2f} {} (population demand)",
                planet.name,
                min(consumed, available),
                commodity.displayName,
            )
        }

        return planet.copy(supply = supply)
    }
}
