/* Joseph B. Ottinger (C)2026 */
package dev.streampack.startrader.model

/** Maps each commodity to the inputs it consumes during production (commodity -> rate) */
data class ProductionMatrix(val inputs: Map<Commodity, Map<Commodity, Double>>)
