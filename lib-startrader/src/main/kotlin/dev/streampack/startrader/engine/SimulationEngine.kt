/* Joseph B. Ottinger (C)2026 */
package dev.streampack.startrader.engine

import dev.streampack.startrader.config.SimulationConfig
import dev.streampack.startrader.model.Commodity
import dev.streampack.startrader.model.SupplyDelta
import dev.streampack.startrader.model.UniverseState
import kotlin.math.abs
import kotlin.math.max
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

@Service
class SimulationEngine(
    private val productionEngine: ProductionEngine,
    private val npcDampeningEngine: NpcDampeningEngine,
    private val eventEngine: EventEngine,
    private val priceEngine: PriceEngine,
) {
    private val logger = LoggerFactory.getLogger(SimulationEngine::class.java)

    /**
     * Execute one tick of the economic simulation. Phases:
     * 1. Apply external deltas (player trades, test inputs)
     * 2. Produce - planets generate output constrained by inputs
     * 3. Consume - population demand
     * 4. NPC dampening - phantom supply/demand toward universe average
     * 5. Events - apply/decrement/expire/spawn events
     * 6. Converge prices - two-tier exchange model (market + local)
     * 7. Generate tick summary for event log
     */
    fun tick(
        state: UniverseState,
        config: SimulationConfig,
        deltas: List<SupplyDelta> = emptyList(),
    ): UniverseState {
        logger.info("Tick {} starting ({} external deltas)", state.tickCount + 1, deltas.size)

        var current = state.copy(tickCount = state.tickCount + 1)
        val preTickSupply = snapshotSupply(current)

        // Phase 1: Apply external deltas
        current = applyDeltas(current, deltas)

        // Phase 2: Produce
        current = productionEngine.produce(current, config)

        // Phase 3: Consume (population demand)
        current = productionEngine.consume(current, config)

        // Phase 4: NPC dampening
        current = npcDampeningEngine.dampen(current, config)

        // Phase 5: Events
        current = eventEngine.processEvents(current, config)

        // Phase 6: Converge prices
        current = priceEngine.convergePrices(current, config)

        // Phase 7: Tick summary
        current = appendTickSummary(current, preTickSupply, config)

        logger.info("Tick {} complete", current.tickCount)
        return current
    }

    /** Snapshot total supply per commodity across all planets */
    private fun snapshotSupply(state: UniverseState): Map<Commodity, Double> =
        Commodity.entries.associateWith { commodity ->
            state.planets.sumOf { it.supply[commodity] ?: 0.0 }
        }

    /** Generate a per-tick summary showing what changed in the economy */
    private fun appendTickSummary(
        state: UniverseState,
        preTickSupply: Map<Commodity, Double>,
        config: SimulationConfig,
    ): UniverseState {
        val log = state.eventLog.toMutableList()
        val tick = state.tickCount

        // Supply changes: which commodities saw the biggest moves?
        val supplyChanges = mutableListOf<String>()
        for (commodity in Commodity.entries) {
            val before = preTickSupply[commodity] ?: 0.0
            val after = state.planets.sumOf { it.supply[commodity] ?: 0.0 }
            val delta = after - before
            if (abs(delta) > 1.0) {
                val direction = if (delta > 0) "+" else ""
                supplyChanges.add(
                    "${commodity.displayName} ${direction}${String.format("%.0f", delta)}"
                )
            }
        }
        if (supplyChanges.isNotEmpty()) {
            log.add("Tick $tick supply: ${supplyChanges.joinToString(", ")}")
        }

        // Price highlights: most expensive and cheapest across the universe
        val priceExtremes = mutableListOf<String>()
        for (commodity in Commodity.entries) {
            val basePrice = config.commodityBasePrices[commodity] ?: continue
            val prices = state.planets.mapNotNull { it.prices[commodity] }
            if (prices.isEmpty()) continue
            val maxPrice = prices.max()
            val minPrice = prices.min()
            val maxPlanet = state.planets.first { (it.prices[commodity] ?: 0.0) == maxPrice }
            val minPlanet = state.planets.first { (it.prices[commodity] ?: 0.0) == minPrice }

            // Only log if there's meaningful spread
            if (maxPrice > basePrice * 1.5 || minPrice < basePrice * 0.7) {
                priceExtremes.add(
                    "${commodity.displayName}: ${String.format("%.0f", minPrice)} (${minPlanet.name}) - ${String.format("%.0f", maxPrice)} (${maxPlanet.name})"
                )
            }
        }
        if (priceExtremes.isNotEmpty()) {
            log.add("Tick $tick prices: ${priceExtremes.joinToString("; ")}")
        }

        // Active events summary
        if (state.activeEvents.isNotEmpty()) {
            val eventSummary =
                state.activeEvents.joinToString(", ") {
                    "${it.event.id}@${it.planet}(${it.remainingTicks}t)"
                }
            log.add("Tick $tick events: $eventSummary")
        }

        return state.copy(eventLog = log)
    }

    /** Apply external supply/demand changes to planet inventories */
    private fun applyDeltas(state: UniverseState, deltas: List<SupplyDelta>): UniverseState {
        if (deltas.isEmpty()) return state

        val planets = state.planets.toMutableList()
        val eventLog = state.eventLog.toMutableList()

        for (delta in deltas) {
            val planetIndex = planets.indexOfFirst { it.name == delta.planet }
            if (planetIndex < 0) {
                logger.warn("Delta references unknown planet: {}", delta.planet)
                continue
            }

            val planet = planets[planetIndex]
            val supply = planet.supply.toMutableMap()
            val currentSupply = supply[delta.commodity] ?: 0.0
            val change = delta.supplyChange()
            supply[delta.commodity] = max(0.0, currentSupply + change)
            planets[planetIndex] = planet.copy(supply = supply)

            val action = if (change > 0) "added" else "removed"
            eventLog.add(
                "Tick ${state.tickCount + 1}: $action ${String.format("%.1f", abs(change))} ${delta.commodity.displayName} at ${delta.planet}"
            )
        }

        return state.copy(planets = planets, eventLog = eventLog)
    }
}
