/* Joseph B. Ottinger (C)2026 */
package dev.streampack.startrader.config

import dev.streampack.startrader.model.Commodity

data class PlanetConfig(
    val name: String,
    val fixed: Boolean = false,
    val x: Double? = null,
    val y: Double? = null,
    val z: Double? = null,
    val production: Map<dev.streampack.startrader.model.Commodity, Double>,
    val population: Double,
)
