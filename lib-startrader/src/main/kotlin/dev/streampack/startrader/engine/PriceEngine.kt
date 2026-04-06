/* Joseph B. Ottinger (C)2026 */
package dev.streampack.startrader.engine

import dev.streampack.startrader.config.SimulationConfig
import dev.streampack.startrader.model.Commodity
import dev.streampack.startrader.model.UniverseState
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Component
class PriceEngine {
    private val logger = LoggerFactory.getLogger(PriceEngine::class.java)

    companion object {
        const val MAX_ITERATIONS = 20
        const val CONVERGENCE_THRESHOLD = 0.01

        /** Dampening factor to prevent wild price swings per iteration */
        const val DAMPENING = 0.3

        /** Minimum price floor as fraction of base price */
        const val MIN_PRICE_FACTOR = 0.1

        /** Maximum price ceiling as multiple of base price */
        const val MAX_PRICE_FACTOR = 10.0

        /**
         * Reference supply level: when a planet's stock equals this, the local scarcity factor is
         * 1.0 (no local premium or discount).
         */
        const val REFERENCE_SUPPLY = 50.0

        /** How much the exchange (market) price influences local price vs pure local supply */
        const val EXCHANGE_INFLUENCE = 0.2
    }

    /**
     * Two-tier exchange pricing model:
     * 1. Compute market price per commodity from aggregate supply across all planets
     * 2. Compute local price per planet as a blend of market price and local scarcity
     */
    fun convergePrices(state: UniverseState, config: SimulationConfig): UniverseState {
        val marketPrices = computeMarketPrices(state, config)

        val updatedPlanets =
            state.planets.map { planet ->
                var prices = planet.prices.toMutableMap()

                // Initialize missing prices
                for (commodity in Commodity.entries) {
                    if (commodity !in prices) {
                        prices[commodity] = config.commodityBasePrices[commodity] ?: 0.0
                    }
                }

                // Iterate to convergence (input costs depend on prices)
                for (iteration in 1..MAX_ITERATIONS) {
                    val newPrices = computeLocalPrices(planet.supply, prices, marketPrices, config)

                    val maxDelta =
                        Commodity.entries.maxOfOrNull { commodity ->
                            val oldPrice = prices[commodity] ?: 0.0
                            val newPrice = newPrices[commodity] ?: 0.0
                            if (oldPrice > 0.0) abs(newPrice - oldPrice) / oldPrice else 0.0
                        } ?: 0.0

                    prices = newPrices.toMutableMap()

                    if (maxDelta < CONVERGENCE_THRESHOLD) {
                        logger.debug(
                            "Prices converged for {} after {} iterations",
                            planet.name,
                            iteration,
                        )
                        break
                    }
                }

                planet.copy(prices = prices)
            }

        return state.copy(planets = updatedPlanets)
    }

    /**
     * Tier 1: Market price per commodity. Aggregate supply across all planets drives the
     * universe-wide price signal. Low total supply -> high market price.
     */
    private fun computeMarketPrices(
        state: UniverseState,
        config: SimulationConfig,
    ): Map<Commodity, Double> {
        val marketPrices = mutableMapOf<Commodity, Double>()
        val planetCount = state.planets.size.toDouble()

        for (commodity in Commodity.entries) {
            val basePrice = config.commodityBasePrices[commodity] ?: continue

            // Average supply across all planets
            val totalSupply = state.planets.sumOf { it.supply[commodity] ?: 0.0 }
            val avgSupply = if (planetCount > 0) totalSupply / planetCount else 0.0

            // Market price rises when average supply is below reference, falls when above
            val supplyRatio =
                if (avgSupply > 0.0) REFERENCE_SUPPLY / avgSupply else MAX_PRICE_FACTOR
            val marketFactor = min(MAX_PRICE_FACTOR, max(MIN_PRICE_FACTOR, supplyRatio))

            marketPrices[commodity] = basePrice * marketFactor
        }

        return marketPrices
    }

    /**
     * Tier 2: Local price per commodity on a specific planet. Blends the market price with local
     * supply conditions. A planet with scarce local supply pays above market; a planet with surplus
     * gets a discount.
     */
    private fun computeLocalPrices(
        localSupply: Map<Commodity, Double>,
        currentPrices: Map<Commodity, Double>,
        marketPrices: Map<Commodity, Double>,
        config: SimulationConfig,
    ): Map<Commodity, Double> {
        val newPrices = mutableMapOf<Commodity, Double>()

        for (commodity in Commodity.entries) {
            val basePrice = config.commodityBasePrices[commodity] ?: continue
            val marketPrice = marketPrices[commodity] ?: basePrice
            val supply = localSupply[commodity] ?: 0.0

            // Local scarcity factor: below reference supply drives local premium
            val localRatio = if (supply > 0.0) REFERENCE_SUPPLY / supply else MAX_PRICE_FACTOR
            val localFactor = min(MAX_PRICE_FACTOR, max(MIN_PRICE_FACTOR, localRatio))
            val localPrice = basePrice * localFactor

            // Input cost factor: expensive inputs raise output price
            val inputCostFactor = calculateInputCostFactor(commodity, currentPrices, config)

            // Blend market price with local price, adjusted by input costs
            val blendedTarget =
                (marketPrice * EXCHANGE_INFLUENCE + localPrice * (1.0 - EXCHANGE_INFLUENCE)) *
                    inputCostFactor

            // Dampen toward target from current price
            val currentPrice = currentPrices[commodity] ?: basePrice
            val dampened = currentPrice + (blendedTarget - currentPrice) * DAMPENING

            // Clamp to floor/ceiling
            val minPrice = basePrice * MIN_PRICE_FACTOR
            val maxPrice = basePrice * MAX_PRICE_FACTOR
            newPrices[commodity] = min(maxPrice, max(minPrice, dampened))
        }

        return newPrices
    }

    /**
     * Input cost factor: when a commodity's production inputs are expensive, the output price
     * rises. Returns 1.0 at baseline, higher when inputs are expensive.
     */
    private fun calculateInputCostFactor(
        commodity: Commodity,
        currentPrices: Map<Commodity, Double>,
        config: SimulationConfig,
    ): Double {
        val inputs = config.productionMatrix.inputs[commodity] ?: return 1.0
        if (inputs.isEmpty()) return 1.0

        var totalWeight = 0.0
        var weightedRatio = 0.0

        for ((input, rate) in inputs) {
            val currentInputPrice = currentPrices[input] ?: 0.0
            val baseInputPrice = config.commodityBasePrices[input] ?: 1.0
            if (baseInputPrice <= 0.0) continue

            val ratio = currentInputPrice / baseInputPrice
            weightedRatio += ratio * rate
            totalWeight += rate
        }

        return if (totalWeight > 0.0) {
            0.7 + 0.3 * (weightedRatio / totalWeight)
        } else {
            1.0
        }
    }
}
