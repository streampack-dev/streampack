/* Joseph B. Ottinger (C)2026 */
package dev.streampack.startrader.ui

import dev.streampack.startrader.model.Commodity
import dev.streampack.startrader.model.UniverseState
import javax.swing.table.AbstractTableModel

class PriceTableModel : AbstractTableModel() {
    private var state: UniverseState? = null
    private var averagePrices: Map<Commodity, Double> = emptyMap()
    private val commodities = Commodity.entries.toList()

    fun updateState(newState: UniverseState) {
        state = newState
        averagePrices = computeAveragePrices(newState)
        fireTableDataChanged()
    }

    override fun getRowCount(): Int = state?.planets?.size ?: 0

    override fun getColumnCount(): Int = commodities.size + 1

    override fun getColumnName(column: Int): String =
        if (column == 0) "Planet" else commodities[column - 1].displayName

    override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
        val planet = state?.planets?.get(rowIndex) ?: return ""
        if (columnIndex == 0) return planet.name
        val commodity = commodities[columnIndex - 1]
        val price = planet.prices[commodity] ?: 0.0
        return String.format("%.1f", price)
    }

    /**
     * Returns fractional deviation from universe average price. Negative means cheaper than average
     * (good buy), positive means more expensive (good sell target).
     */
    fun priceDeviation(row: Int, col: Int): Double {
        if (col == 0) return 0.0
        val planet = state?.planets?.get(row) ?: return 0.0
        val commodity = commodities[col - 1]
        val localPrice = planet.prices[commodity] ?: 0.0
        val avgPrice = averagePrices[commodity] ?: return 0.0
        if (avgPrice <= 0.0) return 0.0
        return (localPrice - avgPrice) / avgPrice
    }

    private fun computeAveragePrices(state: UniverseState): Map<Commodity, Double> {
        val planetCount = state.planets.size.toDouble()
        if (planetCount == 0.0) return emptyMap()
        return Commodity.entries.associateWith { commodity ->
            state.planets.sumOf { it.prices[commodity] ?: 0.0 } / planetCount
        }
    }

    override fun getColumnClass(columnIndex: Int): Class<*> = String::class.java
}
