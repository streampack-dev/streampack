/* Joseph B. Ottinger (C)2026 */
package dev.streampack.startrader.engine

import dev.streampack.startrader.config.ConfigLoader
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory

class SimulationPerformanceTests {
    private val logger = LoggerFactory.getLogger(SimulationPerformanceTests::class.java)
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
    fun `4000 ticks complete in reasonable time`() {
        val engine = createEngine()
        var state = seeder.seed(config)
        val tickCount = 4000

        val startTime = System.nanoTime()

        for (i in 1..tickCount) {
            state = engine.tick(state, config)
        }

        val elapsedMs = (System.nanoTime() - startTime) / 1_000_000.0
        val ticksPerSecond = tickCount / (elapsedMs / 1000.0)

        logger.info(
            "{} ticks completed in {}ms ({} ticks/sec)",
            tickCount,
            String.format("%.0f", elapsedMs),
            String.format("%.0f", ticksPerSecond),
        )
    }
}
