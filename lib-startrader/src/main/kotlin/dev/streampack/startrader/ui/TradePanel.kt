/* Joseph B. Ottinger (C)2026 */
package dev.streampack.startrader.ui

import dev.streampack.startrader.model.BuyOrder
import dev.streampack.startrader.model.Commodity
import dev.streampack.startrader.model.SellOrder
import dev.streampack.startrader.model.SupplyDelta
import dev.streampack.startrader.model.UniverseState
import java.awt.FlowLayout
import javax.swing.BorderFactory
import javax.swing.DefaultComboBoxModel
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JSpinner
import javax.swing.SpinnerNumberModel

/**
 * Trade panel for queuing buy/sell orders. Orders accumulate between ticks and are applied when the
 * next tick fires, mirroring the game flow where players submit orders between turns.
 */
class TradePanel : JPanel(FlowLayout(FlowLayout.LEFT)) {
    private val planetSelector = JComboBox<String>()
    private val commoditySelector =
        JComboBox(Commodity.entries.map { it.displayName }.toTypedArray())
    private val quantitySpinner = JSpinner(SpinnerNumberModel(10.0, 1.0, 1000.0, 1.0))
    private val pendingLabel = JLabel("Pending: 0")

    private val pendingOrders = mutableListOf<SupplyDelta>()

    init {
        border = BorderFactory.createTitledBorder("Trade")

        add(JLabel("Planet:"))
        add(planetSelector)
        add(JLabel("Commodity:"))
        add(commoditySelector)
        add(JLabel("Qty:"))
        add(quantitySpinner)

        val buyButton = JButton("Buy")
        buyButton.toolTipText = "Remove supply from planet (player buys goods)"
        buyButton.addActionListener { queueOrder(buy = true) }

        val sellButton = JButton("Sell")
        sellButton.toolTipText = "Add supply to planet (player sells goods)"
        sellButton.addActionListener { queueOrder(buy = false) }

        val clearButton = JButton("Clear")
        clearButton.toolTipText = "Clear all pending orders"
        clearButton.addActionListener { clearOrders() }

        add(buyButton)
        add(sellButton)
        add(clearButton)
        add(pendingLabel)
    }

    /** Update the planet selector when the universe changes */
    fun updateState(newState: UniverseState) {
        val selected = planetSelector.selectedItem as? String
        planetSelector.model = DefaultComboBoxModel(newState.planets.map { it.name }.toTypedArray())
        if (selected != null && newState.planets.any { it.name == selected }) {
            planetSelector.selectedItem = selected
        }
    }

    /** Drain all pending orders for the next tick, clearing the queue */
    fun drainOrders(): List<SupplyDelta> {
        val orders = pendingOrders.toList()
        pendingOrders.clear()
        updatePendingLabel()
        return orders
    }

    private fun queueOrder(buy: Boolean) {
        val planet = planetSelector.selectedItem as? String ?: return
        val commodityIndex = commoditySelector.selectedIndex
        if (commodityIndex < 0) return
        val commodity = Commodity.entries[commodityIndex]
        val quantity = (quantitySpinner.value as Number).toDouble()

        val order =
            if (buy) {
                BuyOrder(planet, commodity, quantity)
            } else {
                SellOrder(planet, commodity, quantity)
            }

        pendingOrders.add(order)
        updatePendingLabel()
    }

    private fun clearOrders() {
        pendingOrders.clear()
        updatePendingLabel()
    }

    private fun updatePendingLabel() {
        val count = pendingOrders.size
        pendingLabel.text =
            if (count == 0) {
                "Pending: 0"
            } else {
                val buys = pendingOrders.count { it is BuyOrder }
                val sells = count - buys
                "Pending: $count ($buys buys, $sells sells)"
            }
    }
}
