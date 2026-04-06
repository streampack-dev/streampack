/* Joseph B. Ottinger (C)2026 */
package dev.streampack.startrader.model

data class Planet(
    val name: String,
    val x: Double,
    val y: Double,
    val z: Double,
    val production: Map<Commodity, Double>,
    val population: Double,
    val supply: Map<Commodity, Double> = Commodity.entries.associateWith { 0.0 },
    val prices: Map<Commodity, Double> = emptyMap(),
)
