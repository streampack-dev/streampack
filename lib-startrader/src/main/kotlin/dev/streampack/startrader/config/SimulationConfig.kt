/* Joseph B. Ottinger (C)2026 */
package dev.streampack.startrader.config

import dev.streampack.startrader.model.Commodity
import dev.streampack.startrader.model.EconomicEvent
import dev.streampack.startrader.model.ProductionMatrix

data class SimulationConfig(
    val commodityBasePrices: Map<dev.streampack.startrader.model.Commodity, Double>,
    val productionMatrix: dev.streampack.startrader.model.ProductionMatrix,
    val planets: List<dev.streampack.startrader.config.PlanetConfig>,
    val npcDampening: dev.streampack.startrader.config.NpcDampeningConfig,
    val events: List<dev.streampack.startrader.model.EconomicEvent>,
    val populationConsumptionRates: Map<dev.streampack.startrader.model.Commodity, Double>,
)
