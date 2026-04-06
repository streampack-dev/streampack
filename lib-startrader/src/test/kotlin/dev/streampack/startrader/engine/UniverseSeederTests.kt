/* Joseph B. Ottinger (C)2026 */
package dev.streampack.startrader.engine

import dev.streampack.startrader.config.ConfigLoader
import dev.streampack.startrader.model.Commodity
import kotlin.math.sqrt
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class UniverseSeederTests {
    private val config = ConfigLoader().load()
    private val seeder = UniverseSeeder()

    @Test
    fun `seed creates correct number of planets`() {
        val state = seeder.seed(config)
        assertEquals(15, state.planets.size)
    }

    @Test
    fun `seed places SOL at fixed position`() {
        val state = seeder.seed(config)
        val sol = state.planets.first { it.name == "SOL" }
        assertEquals(50.0, sol.x)
        assertEquals(50.0, sol.y)
        assertEquals(50.0, sol.z)
    }

    @Test
    fun `all planets have initial supply for every commodity`() {
        val state = seeder.seed(config)
        for (planet in state.planets) {
            for (commodity in Commodity.entries) {
                assertTrue(
                    (planet.supply[commodity] ?: 0.0) > 0.0,
                    "${planet.name} missing supply for ${commodity.displayName}",
                )
            }
        }
    }

    @Test
    fun `all planets have initial prices for every commodity`() {
        val state = seeder.seed(config)
        for (planet in state.planets) {
            for (commodity in Commodity.entries) {
                assertTrue(
                    (planet.prices[commodity] ?: 0.0) > 0.0,
                    "${planet.name} missing price for ${commodity.displayName}",
                )
            }
        }
    }

    @Test
    fun `minimum 3D distance between planets is respected`() {
        val state = seeder.seed(config)
        for (i in state.planets.indices) {
            for (j in i + 1 until state.planets.size) {
                val p1 = state.planets[i]
                val p2 = state.planets[j]
                val dist = distance(p1.x, p1.y, p1.z, p2.x, p2.y, p2.z)
                assertTrue(
                    dist >= UniverseSeeder.MIN_DISTANCE,
                    "${p1.name} and ${p2.name} are too close: $dist",
                )
            }
        }
    }

    @Test
    fun `tick count starts at zero`() {
        val state = seeder.seed(config)
        assertEquals(0, state.tickCount)
    }

    @Test
    fun `no active events at start`() {
        val state = seeder.seed(config)
        assertTrue(state.activeEvents.isEmpty())
    }

    @Test
    fun `seeding is deterministic with fixed random seed`() {
        val seeder1 = UniverseSeeder(java.util.Random(42))
        val seeder2 = UniverseSeeder(java.util.Random(42))
        val state1 = seeder1.seed(config)
        val state2 = seeder2.seed(config)

        for (i in state1.planets.indices) {
            assertEquals(state1.planets[i].x, state2.planets[i].x)
            assertEquals(state1.planets[i].y, state2.planets[i].y)
            assertEquals(state1.planets[i].z, state2.planets[i].z)
        }
    }

    private fun distance(
        x1: Double,
        y1: Double,
        z1: Double,
        x2: Double,
        y2: Double,
        z2: Double,
    ): Double {
        val dx = x1 - x2
        val dy = y1 - y2
        val dz = z1 - z2
        return sqrt(dx * dx + dy * dy + dz * dz)
    }
}
