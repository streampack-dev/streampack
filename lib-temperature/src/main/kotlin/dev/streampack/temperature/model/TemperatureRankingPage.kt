/* Joseph B. Ottinger (C)2026 */
package dev.streampack.temperature.model

/** A page of temperature-ranked subjects for one namespace and signal. */
data class TemperatureRankingPage(
    val scores: List<TemperatureScore>,
    val page: Int,
    val size: Int,
    val totalPages: Int,
    val totalCount: Long,
)
