/* Joseph B. Ottinger (C)2026 */
package dev.streampack.startrader.model

/** Represents an external modification to a planet's supply */
sealed class SupplyDelta(
    open val planet: String,
    open val commodity: Commodity,
    open val quantity: Double,
) {
    /** Net effect on planet supply: positive adds, negative removes */
    abstract fun supplyChange(): Double
}

data class SellOrder(
    override val planet: String,
    override val commodity: Commodity,
    override val quantity: Double,
) : SupplyDelta(planet, commodity, quantity) {
    /** Selling adds supply to the planet */
    override fun supplyChange() = quantity
}

data class BuyOrder(
    override val planet: String,
    override val commodity: Commodity,
    override val quantity: Double,
) : SupplyDelta(planet, commodity, quantity) {
    /** Buying removes supply from the planet */
    override fun supplyChange() = -quantity
}
