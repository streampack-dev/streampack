/* Joseph B. Ottinger (C)2026 */
package dev.streampack.startrader.config

import dev.streampack.startrader.model.Commodity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ConfigLoaderTests {
    private val loader = ConfigLoader()

    @Test
    fun `loads all 12 commodity base prices`() {
        val config = loader.load()
        assertEquals(12, config.commodityBasePrices.size)
        for (commodity in Commodity.entries) {
            assertTrue(config.commodityBasePrices.containsKey(commodity), "Missing: $commodity")
            assertTrue(config.commodityBasePrices[commodity]!! > 0.0)
        }
    }

    @Test
    fun `loads all 15 planets`() {
        val config = loader.load()
        assertEquals(15, config.planets.size)

        val names =
            listOf(
                "SOL",
                "YORK",
                "BOYD",
                "IVAN",
                "REEF",
                "HOOK",
                "STAN",
                "TASK",
                "SINK",
                "SAND",
                "QUIN",
                "GAOL",
                "KIRK",
                "KRIS",
                "FATE",
            )
        for (name in names) {
            assertTrue(config.planets.any { it.name == name }, "Missing planet: $name")
        }
    }

    @Test
    fun `SOL is fixed at center`() {
        val config = loader.load()
        val sol = config.planets.first { it.name == "SOL" }
        assertTrue(sol.fixed)
        assertEquals(50.0, sol.x)
        assertEquals(50.0, sol.y)
    }

    @Test
    fun `non-fixed planets have no position`() {
        val config = loader.load()
        val york = config.planets.first { it.name == "YORK" }
        assertTrue(!york.fixed)
    }

    @Test
    fun `production matrix has entries for all commodities`() {
        val config = loader.load()
        for (commodity in Commodity.entries) {
            assertNotNull(
                config.productionMatrix.inputs[commodity],
                "No matrix entry for: $commodity",
            )
        }
    }

    @Test
    fun `events are loaded`() {
        val config = loader.load()
        assertTrue(config.events.isNotEmpty())
        val plague = config.events.first { it.id == "PLAGUE" }
        assertEquals(Commodity.MEDICINE, plague.commodity)
        assertEquals(3.0, plague.consumptionMultiplier)
    }

    @Test
    fun `NPC dampening config is loaded`() {
        val config = loader.load()
        assertTrue(config.npcDampening.baseFireProbability > 0.0)
        assertTrue(config.npcDampening.priceDeviationMultiplier > 0.0)
        assertTrue(config.npcDampening.maxAdjustmentFraction > 0.0)
    }

    @Test
    fun `population consumption rates are loaded`() {
        val config = loader.load()
        assertTrue(config.populationConsumptionRates.containsKey(Commodity.FOOD))
        assertTrue(config.populationConsumptionRates.containsKey(Commodity.MEDICINE))
    }

    @Test
    fun `each planet has positive population`() {
        val config = loader.load()
        for (planet in config.planets) {
            assertTrue(planet.population > 0.0, "${planet.name} has non-positive population")
        }
    }

    @Test
    fun `each planet has at least one production output`() {
        val config = loader.load()
        for (planet in config.planets) {
            assertTrue(planet.production.isNotEmpty(), "${planet.name} produces nothing")
        }
    }
}
