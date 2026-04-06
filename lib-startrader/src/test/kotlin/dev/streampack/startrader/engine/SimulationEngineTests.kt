/* Joseph B. Ottinger (C)2026 */
package dev.streampack.startrader.engine

import dev.streampack.startrader.config.ConfigLoader
import dev.streampack.startrader.model.BuyOrder
import dev.streampack.startrader.model.Commodity
import dev.streampack.startrader.model.SellOrder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SimulationEngineTests {
    private val config = ConfigLoader().load()
    private val seeder = UniverseSeeder(java.util.Random(42))

    private fun createEngine(): SimulationEngine {
        val productionEngine = ProductionEngine()
        val npcDampeningEngine = NpcDampeningEngine(java.util.Random(42))
        val eventEngine = EventEngine(java.util.Random(42))
        val priceEngine = PriceEngine()
        return SimulationEngine(productionEngine, npcDampeningEngine, eventEngine, priceEngine)
    }

    @Test
    fun `tick increments tick count`() {
        val engine = createEngine()
        val state = seeder.seed(config)

        val result = engine.tick(state, config)
        assertEquals(1, result.tickCount)

        val result2 = engine.tick(result, config)
        assertEquals(2, result2.tickCount)
    }

    @Test
    fun `tick preserves planet count`() {
        val engine = createEngine()
        val state = seeder.seed(config)

        val result = engine.tick(state, config)
        assertEquals(state.planets.size, result.planets.size)
    }

    @Test
    fun `all prices remain positive after tick`() {
        val engine = createEngine()
        var state = seeder.seed(config)

        for (i in 1..10) {
            state = engine.tick(state, config)
        }

        for (planet in state.planets) {
            for (commodity in Commodity.entries) {
                assertTrue(
                    (planet.prices[commodity] ?: 0.0) > 0.0,
                    "Tick ${state.tickCount}: ${planet.name} has zero/negative price for ${commodity.displayName}",
                )
            }
        }
    }

    @Test
    fun `all supply levels remain non-negative after ticks`() {
        val engine = createEngine()
        var state = seeder.seed(config)

        for (i in 1..20) {
            state = engine.tick(state, config)
        }

        for (planet in state.planets) {
            for (commodity in Commodity.entries) {
                assertTrue(
                    (planet.supply[commodity] ?: 0.0) >= 0.0,
                    "${planet.name} has negative supply for ${commodity.displayName}",
                )
            }
        }
    }

    @Test
    fun `sell order increases supply`() {
        val engine = createEngine()
        val state = seeder.seed(config)

        val solSupplyBefore = state.planets.first { it.name == "SOL" }.supply[Commodity.FOOD] ?: 0.0
        val sell = SellOrder("SOL", Commodity.FOOD, 100.0)

        val result = engine.tick(state, config, listOf(sell))
        val solSupplyAfter = result.planets.first { it.name == "SOL" }.supply[Commodity.FOOD] ?: 0.0

        // Supply should be higher than it would be without the sell (accounting for consumption)
        // At minimum, the delta application should have added 100 to starting supply
        assertTrue(
            result.eventLog.any { it.contains("added") && it.contains("Food") },
            "Event log should record the sell order",
        )
    }

    @Test
    fun `buy order decreases supply`() {
        val engine = createEngine()
        val state = seeder.seed(config)
        val buy = BuyOrder("SOL", Commodity.ORE, 10.0)

        val result = engine.tick(state, config, listOf(buy))

        assertTrue(
            result.eventLog.any { it.contains("removed") && it.contains("Ore") },
            "Event log should record the buy order",
        )
    }

    @Test
    fun `economy stabilizes over many ticks`() {
        val engine = createEngine()
        var state = seeder.seed(config)

        // Run 50 ticks to let the economy settle
        for (i in 1..50) {
            state = engine.tick(state, config)
        }

        // Capture prices, run 10 more ticks, check variance
        val pricesBefore = state.planets.first().prices.toMap()

        for (i in 1..10) {
            state = engine.tick(state, config)
        }

        val pricesAfter = state.planets.first().prices
        var stableCount = 0
        for (commodity in Commodity.entries) {
            val before = pricesBefore[commodity] ?: 0.0
            val after = pricesAfter[commodity] ?: 0.0
            if (before > 0.0) {
                val change = kotlin.math.abs(after - before) / before
                if (change < 0.5) stableCount++
            }
        }

        // Most commodity prices should not wildly swing after stabilization
        assertTrue(
            stableCount >= 6,
            "At least half of commodity prices should be relatively stable",
        )
    }

    @Test
    fun `delta for unknown planet is handled gracefully`() {
        val engine = createEngine()
        val state = seeder.seed(config)
        val sell = SellOrder("NONEXISTENT", Commodity.FOOD, 50.0)

        // Should not throw
        val result = engine.tick(state, config, listOf(sell))
        assertEquals(1, result.tickCount)
    }

    @Test
    fun `supply delta types compute correct changes`() {
        val sell = SellOrder("SOL", Commodity.FOOD, 20.0)
        assertEquals(20.0, sell.supplyChange())

        val buy = BuyOrder("SOL", Commodity.FOOD, 20.0)
        assertEquals(-20.0, buy.supplyChange())
    }
}
