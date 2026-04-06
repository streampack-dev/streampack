/* Joseph B. Ottinger (C)2026 */
package dev.streampack.startrader.engine

import dev.streampack.startrader.config.ConfigLoader
import dev.streampack.startrader.model.ActiveEvent
import dev.streampack.startrader.model.Commodity
import dev.streampack.startrader.model.EconomicEvent
import dev.streampack.startrader.model.Planet
import dev.streampack.startrader.model.UniverseState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EventEngineTests {
    private val config = ConfigLoader().load()

    @Test
    fun `active events decrement remaining ticks`() {
        val event = EconomicEvent("TEST_EVENT", Commodity.FOOD, 2.0, 3, 6)
        val active = ActiveEvent(event, "SOL", 5)
        val state = createStateWithEvents(listOf(active))

        // Use a seeded random that will NOT spawn a new event
        val engine = EventEngine(java.util.Random(999))
        val result = engine.processEvents(state, config)

        val remaining = result.activeEvents.firstOrNull { it.event.id == "TEST_EVENT" }
        assertTrue(remaining != null, "Event should still be active")
        assertEquals(4, remaining!!.remainingTicks)
    }

    @Test
    fun `events expire when ticks reach zero`() {
        val event = EconomicEvent("EXPIRING", Commodity.MEDICINE, 3.0, 1, 1)
        val active = ActiveEvent(event, "SOL", 1)
        val state = createStateWithEvents(listOf(active))

        val engine = EventEngine(java.util.Random(999))
        val result = engine.processEvents(state, config)

        assertTrue(
            result.activeEvents.none { it.event.id == "EXPIRING" },
            "Event should have expired",
        )
        assertTrue(
            result.eventLog.any { it.contains("EXPIRING") && it.contains("ended") },
            "Event log should record expiration",
        )
    }

    @Test
    fun `active events increase consumption of target commodity`() {
        val event = EconomicEvent("FAMINE", Commodity.FOOD, 3.0, 5, 10)
        val active = ActiveEvent(event, "TEST", 5)

        val supply = Commodity.entries.associateWith { 100.0 }
        val planet = Planet("TEST", 0.0, 0.0, 0.0, emptyMap(), 500.0, supply)
        val state = UniverseState(planets = listOf(planet), activeEvents = listOf(active))

        val engine = EventEngine(java.util.Random(999))
        val result = engine.processEvents(state, config)
        val updatedPlanet = result.planets.first()

        // With a 3x consumption multiplier, the extra consumption should reduce FOOD
        assertTrue(
            updatedPlanet.supply[Commodity.FOOD]!! < 100.0,
            "FOOD supply should decrease from event consumption",
        )
    }

    @Test
    fun `max concurrent events is respected`() {
        val events =
            (1..EventEngine.MAX_ACTIVE_EVENTS).map { i ->
                val event = EconomicEvent("EVENT_$i", Commodity.ORE, 2.0, 5, 10)
                ActiveEvent(event, "SOL", 10)
            }
        val state = createStateWithEvents(events)

        // Even with a random that always fires, no new events should spawn
        val engine = EventEngine(java.util.Random(0))
        val result = engine.processEvents(state, config)

        assertTrue(
            result.activeEvents.size <= EventEngine.MAX_ACTIVE_EVENTS,
            "Should not exceed max concurrent events",
        )
    }

    @Test
    fun `consumption multiplier calculation is correct`() {
        val engine = EventEngine()
        val event1 = EconomicEvent("E1", Commodity.FOOD, 2.0, 5, 10)
        val event2 = EconomicEvent("E2", Commodity.FOOD, 1.5, 3, 6)
        val events = listOf(ActiveEvent(event1, "SOL", 5), ActiveEvent(event2, "SOL", 3))

        val multiplier = engine.consumptionMultiplier(events, "SOL", Commodity.FOOD)
        assertEquals(3.0, multiplier, 0.01)
    }

    @Test
    fun `consumption multiplier ignores events on other planets`() {
        val engine = EventEngine()
        val event = EconomicEvent("E1", Commodity.FOOD, 2.0, 5, 10)
        val events = listOf(ActiveEvent(event, "KIRK", 5))

        val multiplier = engine.consumptionMultiplier(events, "SOL", Commodity.FOOD)
        assertEquals(1.0, multiplier)
    }

    private fun createStateWithEvents(events: List<ActiveEvent>): UniverseState {
        val supply = Commodity.entries.associateWith { 100.0 }
        val prices = Commodity.entries.associateWith { config.commodityBasePrices[it] ?: 10.0 }
        val planet = Planet("SOL", 50.0, 50.0, 50.0, emptyMap(), 500.0, supply, prices)
        return UniverseState(planets = listOf(planet), activeEvents = events)
    }
}
