/* Joseph B. Ottinger (C)2026 */
package dev.streampack.startrader.model

data class ActiveEvent(val event: EconomicEvent, val planet: String, val remainingTicks: Int)
