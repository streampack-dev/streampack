/* Joseph B. Ottinger (C)2026 */
package dev.streampack.temperature.model

import java.time.LocalDate

/** Decayed temperature score for a subject in one namespace and signal. */
data class TemperatureScore(
    val namespace: String,
    val subjectKey: String,
    val signal: String,
    val positiveScore: Double,
    val negativeScore: Double,
    val netScore: Double,
    val lastUpdated: LocalDate?,
)
