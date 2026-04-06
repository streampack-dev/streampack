/* Joseph B. Ottinger (C)2026 */
package dev.streampack.startrader.model

data class UniverseState(
    val planets: List<Planet>,
    val tickCount: Int = 0,
    val activeEvents: List<ActiveEvent> = emptyList(),
    val eventLog: List<String> = emptyList(),
)
