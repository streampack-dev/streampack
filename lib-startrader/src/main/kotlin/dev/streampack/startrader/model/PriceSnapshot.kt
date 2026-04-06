/* Joseph B. Ottinger (C)2026 */
package dev.streampack.startrader.model

/** Snapshot of prices across all planets for a single point in time */
data class PriceSnapshot(val tickCount: Int, val prices: Map<String, Map<Commodity, Double>>) {
    fun priceAt(planet: String, commodity: Commodity): Double =
        prices[planet]?.get(commodity) ?: 0.0
}
