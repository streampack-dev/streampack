/* Joseph B. Ottinger (C)2026 */
package dev.streampack.startrader.engine

import dev.streampack.startrader.config.ConfigLoader
import dev.streampack.startrader.model.Commodity
import dev.streampack.startrader.model.Planet
import dev.streampack.startrader.model.UniverseState
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PriceEngineTests {
    private val config = ConfigLoader().load()
    private val engine = PriceEngine()

    @Test
    fun `prices are assigned for all commodities after convergence`() {
        val state = createState(supplyLevel = 50.0)
        val result = engine.convergePrices(state, config)

        for (planet in result.planets) {
            for (commodity in Commodity.entries) {
                assertTrue(
                    (planet.prices[commodity] ?: 0.0) > 0.0,
                    "${planet.name} has zero price for ${commodity.displayName}",
                )
            }
        }
    }

    @Test
    fun `low supply drives prices up`() {
        val normalState = createState(supplyLevel = 50.0)
        val scarceState = createState(supplyLevel = 5.0)

        val normalResult = engine.convergePrices(normalState, config)
        val scarceResult = engine.convergePrices(scarceState, config)

        val normalPlanet = normalResult.planets.first()
        val scarcePlanet = scarceResult.planets.first()

        // At least some commodities should be more expensive when supply is low
        var higherCount = 0
        for (commodity in Commodity.entries) {
            val normalPrice = normalPlanet.prices[commodity] ?: 0.0
            val scarcePrice = scarcePlanet.prices[commodity] ?: 0.0
            if (scarcePrice > normalPrice) higherCount++
        }
        assertTrue(higherCount > 6, "Most prices should be higher when supply is scarce")
    }

    @Test
    fun `high supply drives prices down`() {
        val normalState = createState(supplyLevel = 50.0)
        val abundantState = createState(supplyLevel = 500.0)

        val normalResult = engine.convergePrices(normalState, config)
        val abundantResult = engine.convergePrices(abundantState, config)

        val normalPlanet = normalResult.planets.first()
        val abundantPlanet = abundantResult.planets.first()

        var lowerCount = 0
        for (commodity in Commodity.entries) {
            val normalPrice = normalPlanet.prices[commodity] ?: 0.0
            val abundantPrice = abundantPlanet.prices[commodity] ?: 0.0
            if (abundantPrice < normalPrice) lowerCount++
        }
        assertTrue(lowerCount > 6, "Most prices should be lower when supply is abundant")
    }

    @Test
    fun `prices stay within bounds`() {
        val state = createState(supplyLevel = 0.1)
        val result = engine.convergePrices(state, config)

        for (planet in result.planets) {
            for (commodity in Commodity.entries) {
                val price = planet.prices[commodity] ?: 0.0
                val basePrice = config.commodityBasePrices[commodity] ?: 1.0
                val minPrice = basePrice * PriceEngine.MIN_PRICE_FACTOR
                val maxPrice = basePrice * PriceEngine.MAX_PRICE_FACTOR

                assertTrue(
                    price >= minPrice - 0.01,
                    "${commodity.displayName} price $price below floor $minPrice",
                )
                assertTrue(
                    price <= maxPrice + 0.01,
                    "${commodity.displayName} price $price above ceiling $maxPrice",
                )
            }
        }
    }

    @Test
    fun `convergence is idempotent when supply is stable`() {
        val state = createState(supplyLevel = 50.0)
        val first = engine.convergePrices(state, config)
        val second = engine.convergePrices(first, config)

        val planet1 = first.planets.first()
        val planet2 = second.planets.first()

        for (commodity in Commodity.entries) {
            val price1 = planet1.prices[commodity] ?: 0.0
            val price2 = planet2.prices[commodity] ?: 0.0
            val delta = kotlin.math.abs(price1 - price2)
            assertTrue(delta < 1.0, "${commodity.displayName} price not stable: $price1 vs $price2")
        }
    }

    private fun createState(supplyLevel: Double): UniverseState {
        val supply = Commodity.entries.associateWith { supplyLevel }
        val planet =
            Planet(
                name = "TEST",
                x = 0.0,
                y = 0.0,
                z = 0.0,
                production = mapOf(Commodity.ORE to 1.0),
                population = 100.0,
                supply = supply,
            )
        return UniverseState(planets = listOf(planet))
    }
}
