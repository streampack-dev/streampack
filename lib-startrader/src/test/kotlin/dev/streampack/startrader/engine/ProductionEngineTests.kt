/* Joseph B. Ottinger (C)2026 */
package dev.streampack.startrader.engine

import dev.streampack.startrader.config.ConfigLoader
import dev.streampack.startrader.model.Commodity
import dev.streampack.startrader.model.Planet
import dev.streampack.startrader.model.UniverseState
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ProductionEngineTests {
    private val config = ConfigLoader().load()
    private val engine = ProductionEngine()

    @Test
    fun `production increases supply of produced commodities`() {
        val planet = createPlanet("TEST", mapOf(Commodity.ORE to 2.0))
        val state = UniverseState(planets = listOf(planet))

        val result = engine.produce(state, config)
        val updatedPlanet = result.planets.first()

        // ORE has fuel and machines as inputs; with abundant supply, production should proceed
        assertTrue(
            updatedPlanet.supply[Commodity.ORE]!! > planet.supply[Commodity.ORE]!!,
            "ORE supply should increase from production",
        )
    }

    @Test
    fun `production is constrained by input availability`() {
        // MACHINES requires ALLOYS, COMPONENTS, FUEL - set them to zero
        val supply =
            Commodity.entries.associateWith { commodity ->
                when (commodity) {
                    Commodity.ALLOYS,
                    Commodity.COMPONENTS,
                    Commodity.FUEL -> 0.0
                    else -> 50.0
                }
            }
        val planet = Planet("TEST", 0.0, 0.0, 0.0, mapOf(Commodity.MACHINES to 2.0), 100.0, supply)
        val state = UniverseState(planets = listOf(planet))

        val result = engine.produce(state, config)
        val updatedPlanet = result.planets.first()

        // With zero inputs, MACHINES production should be near zero
        val machinesProduced =
            updatedPlanet.supply[Commodity.MACHINES]!! - planet.supply[Commodity.MACHINES]!!
        assertTrue(machinesProduced < 0.1, "MACHINES production should be near zero without inputs")
    }

    @Test
    fun `consumption reduces supply`() {
        val planet = createPlanet("TEST", emptyMap(), population = 500.0)
        val state = UniverseState(planets = listOf(planet))

        val result = engine.consume(state, config)
        val updatedPlanet = result.planets.first()

        // FOOD should be consumed by population
        if (config.populationConsumptionRates.containsKey(Commodity.FOOD)) {
            assertTrue(
                updatedPlanet.supply[Commodity.FOOD]!! < planet.supply[Commodity.FOOD]!!,
                "FOOD supply should decrease from consumption",
            )
        }
    }

    @Test
    fun `supply does not go negative from consumption`() {
        val supply = Commodity.entries.associateWith { 0.1 }
        val planet = Planet("TEST", 0.0, 0.0, 0.0, emptyMap(), 10000.0, supply)
        val state = UniverseState(planets = listOf(planet))

        val result = engine.consume(state, config)
        val updatedPlanet = result.planets.first()

        for (commodity in Commodity.entries) {
            assertTrue(
                updatedPlanet.supply[commodity]!! >= 0.0,
                "${commodity.displayName} went negative",
            )
        }
    }

    @Test
    fun `production consumes inputs`() {
        // ALLOYS requires ORE, FUEL, CHEMICALS
        val planet = createPlanet("TEST", mapOf(Commodity.ALLOYS to 2.0))
        val state = UniverseState(planets = listOf(planet))

        val result = engine.produce(state, config)
        val updatedPlanet = result.planets.first()

        // ORE is a major input for ALLOYS; it should be consumed
        val oreInputRate = config.productionMatrix.inputs[Commodity.ALLOYS]?.get(Commodity.ORE)
        if (oreInputRate != null && oreInputRate > 0.0) {
            assertTrue(
                updatedPlanet.supply[Commodity.ORE]!! < planet.supply[Commodity.ORE]!!,
                "ORE should be consumed during ALLOYS production",
            )
        }
    }

    private fun createPlanet(
        name: String,
        production: Map<Commodity, Double>,
        population: Double = 100.0,
    ): Planet {
        val supply = Commodity.entries.associateWith { 50.0 }
        return Planet(name, 0.0, 0.0, 0.0, production, population, supply)
    }
}
