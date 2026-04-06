/* Joseph B. Ottinger (C)2026 */
package dev.streampack.startrader.ui

import dev.streampack.startrader.config.ConfigLoader
import dev.streampack.startrader.config.SimulationConfig
import dev.streampack.startrader.engine.SimulationEngine
import dev.streampack.startrader.engine.UniverseSeeder
import dev.streampack.startrader.model.UniverseState
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTable
import javax.swing.JTextArea
import javax.swing.SwingConstants
import javax.swing.WindowConstants
import javax.swing.table.DefaultTableCellRenderer
import kotlin.math.abs
import kotlin.math.min

class StarTraderFrame(
    private val engine: SimulationEngine,
    private val seeder: UniverseSeeder,
    private val configLoader: ConfigLoader,
) : JFrame("Star Trader Economic Simulation") {

    private val priceTableModel = PriceTableModel()
    private val priceTable = JTable(priceTableModel)
    private val eventLog = JTextArea(8, 80)
    private val statusLabel = JLabel("Tick: 0 | GDP: 0")
    private val tradePanel = TradePanel()
    private val mapPanel = UniverseMapPanel()
    private val mapFrame = JFrame("Star Trader - Universe Map")
    private lateinit var config: SimulationConfig
    private lateinit var state: UniverseState
    private var lastEventLogSize = 0

    init {
        defaultCloseOperation = EXIT_ON_CLOSE
        preferredSize = Dimension(1200, 700)
        layout = BorderLayout()

        setupMapFrame()
        setupTable()
        setupUI()
        initializeUniverse()

        pack()
        setLocationRelativeTo(null)
    }

    private fun setupMapFrame() {
        mapFrame.defaultCloseOperation = WindowConstants.HIDE_ON_CLOSE
        mapFrame.preferredSize = Dimension(700, 700)
        mapFrame.layout = BorderLayout()
        mapFrame.add(mapPanel, BorderLayout.CENTER)
        mapFrame.pack()
    }

    private fun setupTable() {
        priceTable.autoResizeMode = JTable.AUTO_RESIZE_ALL_COLUMNS
        priceTable.setDefaultRenderer(
            String::class.java,
            object : DefaultTableCellRenderer() {
                override fun getTableCellRendererComponent(
                    table: JTable,
                    value: Any?,
                    isSelected: Boolean,
                    hasFocus: Boolean,
                    row: Int,
                    column: Int,
                ): Component {
                    val component =
                        super.getTableCellRendererComponent(
                            table,
                            value,
                            isSelected,
                            hasFocus,
                            row,
                            column,
                        )

                    if (column > 0) {
                        horizontalAlignment = SwingConstants.RIGHT
                        val deviation = priceTableModel.priceDeviation(row, column)
                        // Scale color intensity: full saturation at 30% deviation from average
                        val intensity = (min(1.0, abs(deviation) / 0.3) * 55).toInt()
                        background =
                            when {
                                deviation > 0.02 -> Color(255, 200 - intensity, 200 - intensity)
                                deviation < -0.02 -> Color(200 - intensity, 255, 200 - intensity)
                                else -> Color.WHITE
                            }
                    } else {
                        horizontalAlignment = SwingConstants.LEFT
                        background = Color.WHITE
                    }

                    if (isSelected) {
                        background = background.darker()
                    }

                    return component
                }
            },
        )
    }

    private fun setupUI() {
        // Price table
        val tableScroll = JScrollPane(priceTable)
        add(tableScroll, BorderLayout.CENTER)

        // Event log
        eventLog.isEditable = false
        eventLog.lineWrap = true
        val logScroll = JScrollPane(eventLog)
        logScroll.preferredSize = Dimension(1200, 150)
        add(logScroll, BorderLayout.SOUTH)

        // Controls
        val controlPanel = JPanel(FlowLayout(FlowLayout.LEFT))
        val tickButton = JButton("Tick")
        tickButton.addActionListener { doTick(1) }

        val tick10Button = JButton("Tick x10")
        tick10Button.addActionListener { doTick(10) }

        val tick100Button = JButton("Tick x100")
        tick100Button.addActionListener { doTick(100) }

        val seedButton = JButton("Seed New Universe")
        seedButton.addActionListener { initializeUniverse() }

        val mapButton = JButton("Toggle Map")
        mapButton.addActionListener {
            if (mapFrame.isVisible) {
                mapFrame.isVisible = false
            } else {
                mapFrame.setLocationRelativeTo(this)
                mapFrame.isVisible = true
            }
        }

        controlPanel.add(tickButton)
        controlPanel.add(tick10Button)
        controlPanel.add(tick100Button)
        controlPanel.add(seedButton)
        controlPanel.add(mapButton)
        controlPanel.add(statusLabel)

        // Stack controls and trade panel vertically at the top
        val topPanel = JPanel()
        topPanel.layout = BoxLayout(topPanel, BoxLayout.Y_AXIS)
        topPanel.add(controlPanel)
        topPanel.add(tradePanel)

        add(topPanel, BorderLayout.NORTH)
    }

    private fun initializeUniverse() {
        config = configLoader.load()
        state = seeder.seed(config)
        // Run an initial price convergence so prices reflect starting supply
        state =
            state.copy(
                planets =
                    state.planets.map { planet ->
                        // PriceEngine will be called during the first tick
                        planet
                    }
            )
        lastEventLogSize = 0
        eventLog.text = "Universe seeded with ${state.planets.size} planets\n"
        mapPanel.updateState(state)
        tradePanel.updateState(state)
        updateDisplay()
    }

    private fun doTick(count: Int) {
        // Apply pending trade orders on the first tick, then run remaining ticks clean
        val orders = tradePanel.drainOrders()
        state = engine.tick(state, config, orders)
        for (i in 2..count) {
            state = engine.tick(state, config)
        }
        updateDisplay()
    }

    private fun updateDisplay() {
        priceTableModel.updateState(state)
        mapPanel.updateState(state)

        // Append only new event log entries
        if (state.eventLog.size > lastEventLogSize) {
            val newEntries = state.eventLog.subList(lastEventLogSize, state.eventLog.size)
            for (entry in newEntries) {
                eventLog.append(entry + "\n")
            }
            lastEventLogSize = state.eventLog.size
            eventLog.caretPosition = eventLog.document.length
        }

        // Calculate GDP (sum of all prices * supply across all planets)
        val gdp =
            state.planets.sumOf { planet ->
                planet.prices.entries.sumOf { (commodity, price) ->
                    price * (planet.supply[commodity] ?: 0.0)
                }
            }
        statusLabel.text =
            "Tick: ${state.tickCount} | GDP: ${String.format("%,.0f", gdp)} | Events: ${state.activeEvents.size}"
    }
}
