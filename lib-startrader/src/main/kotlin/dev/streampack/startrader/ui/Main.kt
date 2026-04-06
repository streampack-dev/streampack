/* Joseph B. Ottinger (C)2026 */
package dev.streampack.startrader.ui

import dev.streampack.startrader.config.ConfigLoader
import dev.streampack.startrader.engine.EventEngine
import dev.streampack.startrader.engine.NpcDampeningEngine
import dev.streampack.startrader.engine.PriceEngine
import dev.streampack.startrader.engine.ProductionEngine
import dev.streampack.startrader.engine.SimulationEngine
import dev.streampack.startrader.engine.UniverseSeeder
import javax.swing.SwingUtilities
import javax.swing.UIManager

fun main() {
    // Wire up components manually (no Spring context needed for the UI harness)
    val configLoader = ConfigLoader()
    val productionEngine = ProductionEngine()
    val npcDampeningEngine = NpcDampeningEngine()
    val eventEngine = EventEngine()
    val priceEngine = PriceEngine()
    val simulationEngine =
        SimulationEngine(productionEngine, npcDampeningEngine, eventEngine, priceEngine)
    val seeder = UniverseSeeder()

    SwingUtilities.invokeLater {
        UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName())
        val frame = StarTraderFrame(simulationEngine, seeder, configLoader)
        frame.isVisible = true
    }
}
