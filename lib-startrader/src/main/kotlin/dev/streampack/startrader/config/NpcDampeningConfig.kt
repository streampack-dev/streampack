/* Joseph B. Ottinger (C)2026 */
package dev.streampack.startrader.config

data class NpcDampeningConfig(
    val baseFireProbability: Double,
    val priceDeviationMultiplier: Double,
    val maxAdjustmentFraction: Double,
    val minimumReferenceSupply: Double = 25.0,
)
