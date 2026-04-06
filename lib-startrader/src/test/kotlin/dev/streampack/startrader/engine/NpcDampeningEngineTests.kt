/* Joseph B. Ottinger (C)2026 */
package dev.streampack.startrader.engine

import dev.streampack.startrader.config.ConfigLoader
import dev.streampack.startrader.model.Commodity
import dev.streampack.startrader.model.Planet
import dev.streampack.startrader.model.UniverseState
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NpcDampeningEngineTests {
    private val config = ConfigLoader().load()

    @Test
    fun `dampening adds supply when planet is well below universe average`() {
        val engine = NpcDampeningEngine(java.util.Random(0))

        // Two planets: one with abundant supply, one with almost none
        val richSupply = Commodity.entries.associateWith { 100.0 }
        val poorSupply = Commodity.entries.associateWith { 5.0 }

        val rich = Planet("RICH", 0.0, 0.0, 0.0, emptyMap(), 100.0, richSupply)
        val poor = Planet("POOR", 50.0, 50.0, 50.0, emptyMap(), 100.0, poorSupply)
        val state = UniverseState(planets = listOf(rich, poor))

        val result = engine.dampen(state, config)
        val updatedPoor = result.planets.first { it.name == "POOR" }

        // At least some commodities should have received NPC imports
        var importedCount = 0
        for (commodity in Commodity.entries) {
            if ((updatedPoor.supply[commodity] ?: 0.0) > 5.0) importedCount++
        }
        assertTrue(importedCount > 0, "NPC should import goods to supply-poor planet")
    }

    @Test
    fun `dampening removes supply when planet is well above universe average`() {
        val engine = NpcDampeningEngine(java.util.Random(0))

        val richSupply = Commodity.entries.associateWith { 200.0 }
        val normalSupply = Commodity.entries.associateWith { 20.0 }

        val rich = Planet("RICH", 0.0, 0.0, 0.0, emptyMap(), 100.0, richSupply)
        val normal = Planet("NORMAL", 50.0, 50.0, 50.0, emptyMap(), 100.0, normalSupply)
        val state = UniverseState(planets = listOf(rich, normal))

        val result = engine.dampen(state, config)
        val updatedRich = result.planets.first { it.name == "RICH" }

        var exportedCount = 0
        for (commodity in Commodity.entries) {
            if ((updatedRich.supply[commodity] ?: 0.0) < 200.0) exportedCount++
        }
        assertTrue(exportedCount > 0, "NPC should export goods from supply-rich planet")
    }

    @Test
    fun `dampening does not act when supply is near average`() {
        val engine = NpcDampeningEngine(java.util.Random(0))

        val supply = Commodity.entries.associateWith { 50.0 }
        val planet1 = Planet("A", 0.0, 0.0, 0.0, emptyMap(), 100.0, supply)
        val planet2 = Planet("B", 50.0, 50.0, 50.0, emptyMap(), 100.0, supply)
        val state = UniverseState(planets = listOf(planet1, planet2))

        val result = engine.dampen(state, config)

        for (planet in result.planets) {
            for (commodity in Commodity.entries) {
                val before = 50.0
                val after = planet.supply[commodity] ?: 0.0
                assertTrue(
                    kotlin.math.abs(before - after) < 0.01,
                    "${commodity.displayName} supply should not change when near average",
                )
            }
        }
    }

    @Test
    fun `dampening never drives supply below zero`() {
        val engine = NpcDampeningEngine(java.util.Random(0))

        val richSupply = Commodity.entries.associateWith { 100.0 }
        val tinySupply = Commodity.entries.associateWith { 0.5 }

        val rich = Planet("RICH", 0.0, 0.0, 0.0, emptyMap(), 100.0, richSupply)
        val tiny = Planet("TINY", 50.0, 50.0, 50.0, emptyMap(), 100.0, tinySupply)
        val state = UniverseState(planets = listOf(rich, tiny))

        val result = engine.dampen(state, config)

        for (planet in result.planets) {
            for (commodity in Commodity.entries) {
                assertTrue(
                    (planet.supply[commodity] ?: 0.0) >= 0.0,
                    "${commodity.displayName} supply went negative on ${planet.name}",
                )
            }
        }
    }
}
