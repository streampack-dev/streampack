/* Joseph B. Ottinger (C)2026 */
package dev.streampack.startrader.engine

import dev.streampack.startrader.config.NpcDampeningConfig
import dev.streampack.startrader.config.SimulationConfig
import dev.streampack.startrader.model.Commodity
import dev.streampack.startrader.model.Planet
import dev.streampack.startrader.model.UniverseState
import java.util.Random
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Component
class NpcDampeningEngine(private val random: Random = Random()) {
    private val logger = LoggerFactory.getLogger(this::class.java)

    /**
     * NPC dampening based on supply deviation from a healthy reference level. The reference is the
     * higher of the universe average and a configured floor. This means NPCs inject supply even
     * when the entire universe is in deficit for a commodity - representing external trade routes
     * and natural replenishment. Fire probability scales with deviation severity.
     */
    fun dampen(state: UniverseState, config: SimulationConfig): UniverseState {
        val npcConfig = config.npcDampening

        // Compute universe average supply per commodity
        val avgSupply = computeAverageSupply(state)

        val updatedPlanets =
            state.planets.map { planet -> dampenPlanet(planet, avgSupply, npcConfig) }
        return state.copy(planets = updatedPlanets)
    }

    private fun computeAverageSupply(state: UniverseState): Map<Commodity, Double> {
        val count = state.planets.size.toDouble()
        if (count == 0.0) return emptyMap()

        return Commodity.entries.associateWith { commodity ->
            state.planets.sumOf { it.supply[commodity] ?: 0.0 } / count
        }
    }

    private fun dampenPlanet(
        planet: Planet,
        avgSupply: Map<Commodity, Double>,
        npcConfig: NpcDampeningConfig,
    ): Planet {
        val supply = planet.supply.toMutableMap()

        for (commodity in Commodity.entries) {
            val localSupply = supply[commodity] ?: 0.0
            val universeAvg = avgSupply[commodity] ?: 0.0

            // Reference level: the higher of universe average and the configured floor.
            // This prevents universe-wide deficits from collapsing the reference itself.
            val referenceLevel = max(npcConfig.minimumReferenceSupply, universeAvg)
            val deviation = (localSupply - referenceLevel) / referenceLevel

            // Small deviations are normal market fluctuation, ignore them
            if (abs(deviation) < 0.1) continue

            // Fire probability scales with deviation severity
            val fireProbability =
                min(
                    0.95,
                    npcConfig.baseFireProbability +
                        abs(deviation) * npcConfig.priceDeviationMultiplier,
                )

            if (random.nextDouble() > fireProbability) continue

            val maxAdjustment = max(1.0, referenceLevel * npcConfig.maxAdjustmentFraction)

            if (deviation < 0) {
                // Supply below reference: NPCs import goods here
                val adjustment = maxAdjustment * min(1.0, abs(deviation))
                supply[commodity] = localSupply + adjustment
                logger.debug(
                    "NPC import: +{:.2f} {} at {} (supply {:.1f} vs ref {:.1f})",
                    adjustment,
                    commodity.displayName,
                    planet.name,
                    localSupply,
                    referenceLevel,
                )
            } else {
                // Supply above reference: NPCs export goods away
                val adjustment = min(localSupply * 0.5, maxAdjustment * min(1.0, abs(deviation)))
                supply[commodity] = localSupply - adjustment
                logger.debug(
                    "NPC export: -{:.2f} {} at {} (supply {:.1f} vs ref {:.1f})",
                    adjustment,
                    commodity.displayName,
                    planet.name,
                    localSupply,
                    referenceLevel,
                )
            }
        }

        return planet.copy(supply = supply)
    }
}
