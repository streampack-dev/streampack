/* Joseph B. Ottinger (C)2026 */
package dev.streampack.startrader.engine

import dev.streampack.startrader.config.SimulationConfig
import dev.streampack.startrader.model.Commodity
import dev.streampack.startrader.model.Planet
import dev.streampack.startrader.model.UniverseState
import java.util.Random
import kotlin.math.sqrt
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Component
class UniverseSeeder(private val random: Random = Random()) {
    private val logger = LoggerFactory.getLogger(UniverseSeeder::class.java)

    companion object {
        const val MAP_SIZE = 100.0
        const val MIN_DISTANCE = 12.0
        const val MIN_AVERAGE_DISTANCE = 35.0
        const val MAX_DISTANCE_STD_DEV = 20.0
        const val MAX_PLACEMENT_ATTEMPTS = 1000
        const val INITIAL_SUPPLY_BASE = 50.0
    }

    /** Create a fresh universe from config with randomized planet positions */
    fun seed(config: SimulationConfig): UniverseState {
        val planets = placePlanets(config)
        val initializedPlanets = initializeSupplyAndPrices(planets, config)

        logger.info("Seeded universe with {} planets", initializedPlanets.size)
        return UniverseState(planets = initializedPlanets, tickCount = 0)
    }

    /** Place planets in 3D space, respecting spatial distribution constraints */
    private fun placePlanets(config: SimulationConfig): List<Planet> {
        for (attempt in 1..MAX_PLACEMENT_ATTEMPTS) {
            val candidates = generateCandidatePositions(config)
            if (candidates != null && validateDistribution(candidates)) {
                logger.debug("Planet placement accepted on attempt {}", attempt)
                return candidates
            }
        }

        logger.warn("Could not find ideal placement, using best-effort distribution")
        return generateCandidatePositions(config)
            ?: throw IllegalStateException("Failed to place planets")
    }

    private fun generateCandidatePositions(config: SimulationConfig): List<Planet>? {
        val planets = mutableListOf<Planet>()

        for (planetConfig in config.planets) {
            val x: Double
            val y: Double
            val z: Double

            if (
                planetConfig.fixed &&
                    planetConfig.x != null &&
                    planetConfig.y != null &&
                    planetConfig.z != null
            ) {
                x = planetConfig.x
                y = planetConfig.y
                z = planetConfig.z
            } else {
                var placed = false
                var px = 0.0
                var py = 0.0
                var pz = 0.0

                for (i in 1..100) {
                    px = random.nextDouble() * MAP_SIZE
                    py = random.nextDouble() * MAP_SIZE
                    pz = random.nextDouble() * MAP_SIZE
                    if (planets.all { distance(px, py, pz, it.x, it.y, it.z) >= MIN_DISTANCE }) {
                        placed = true
                        break
                    }
                }

                if (!placed) return null
                x = px
                y = py
                z = pz
            }

            planets.add(
                Planet(
                    name = planetConfig.name,
                    x = x,
                    y = y,
                    z = z,
                    production = planetConfig.production,
                    population = planetConfig.population,
                )
            )
        }

        return planets
    }

    /** Validate that the 3D distribution has good spread and evenness */
    private fun validateDistribution(planets: List<Planet>): Boolean {
        if (planets.size < 2) return true

        val distances = mutableListOf<Double>()
        for (i in planets.indices) {
            for (j in i + 1 until planets.size) {
                distances.add(
                    distance(
                        planets[i].x,
                        planets[i].y,
                        planets[i].z,
                        planets[j].x,
                        planets[j].y,
                        planets[j].z,
                    )
                )
            }
        }

        val avgDistance = distances.average()
        val stdDev = sqrt(distances.map { (it - avgDistance) * (it - avgDistance) }.average())

        if (avgDistance < MIN_AVERAGE_DISTANCE) return false
        if (stdDev > MAX_DISTANCE_STD_DEV) return false

        return true
    }

    /** Initialize each planet with starting supply levels and base prices */
    private fun initializeSupplyAndPrices(
        planets: List<Planet>,
        config: SimulationConfig,
    ): List<Planet> {
        return planets.map { planet ->
            val supply = mutableMapOf<Commodity, Double>()
            val prices = mutableMapOf<Commodity, Double>()

            for (commodity in Commodity.entries) {
                val productionRate = planet.production[commodity] ?: 0.0
                val baseSupply =
                    INITIAL_SUPPLY_BASE * (0.5 + productionRate) +
                        random.nextDouble() * INITIAL_SUPPLY_BASE * 0.2

                supply[commodity] = baseSupply
                prices[commodity] = config.commodityBasePrices[commodity] ?: 0.0
            }

            planet.copy(supply = supply, prices = prices)
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
