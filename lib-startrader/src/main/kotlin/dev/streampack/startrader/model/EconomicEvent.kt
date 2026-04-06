/* Joseph B. Ottinger (C)2026 */
package dev.streampack.startrader.model

data class EconomicEvent(
    val id: String,
    val commodity: Commodity,
    val consumptionMultiplier: Double = 1.0,
    val minDuration: Int,
    val maxDuration: Int,
    val productionMultiplier: Double = 1.0,
    val weight: Double = 1.0,
)
