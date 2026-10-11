/* Joseph B. Ottinger (C)2026 */
package dev.streampack.specs.service

import kotlin.math.floor
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToLong

/**
 * Base scores from CVSS 3.0 and 3.1 vectors, which is all OSV gives for many advisories (GitHub's
 * also carry a rating, and are read for that). 4.0 scores come from lookup tables, not a formula,
 * so a 4.0 vector alone gives no score.
 */
object Cvss {

    /** The base score of a `CVSS:3.x/...` vector, or null for any other or a malformed one. */
    fun baseScore(vector: String): Double? {
        if (!vector.startsWith("CVSS:3.")) return null
        val metrics =
            vector
                .split('/')
                .drop(1)
                .mapNotNull { part ->
                    part.split(':').takeIf { it.size == 2 }?.let { it[0] to it[1] }
                }
                .toMap()
        val changed =
            when (metrics["S"]) {
                "U" -> false
                "C" -> true
                else -> return null
            }
        val av =
            mapOf("N" to 0.85, "A" to 0.62, "L" to 0.55, "P" to 0.2)[metrics["AV"]] ?: return null
        val ac = mapOf("L" to 0.77, "H" to 0.44)[metrics["AC"]] ?: return null
        val pr =
            when (metrics["PR"]) {
                "N" -> 0.85
                "L" -> if (changed) 0.68 else 0.62
                "H" -> if (changed) 0.5 else 0.27
                else -> return null
            }
        val ui = mapOf("N" to 0.85, "R" to 0.62)[metrics["UI"]] ?: return null
        val cia = mapOf("H" to 0.56, "L" to 0.22, "N" to 0.0)
        val c = cia[metrics["C"]] ?: return null
        val i = cia[metrics["I"]] ?: return null
        val a = cia[metrics["A"]] ?: return null

        val iss = 1 - (1 - c) * (1 - i) * (1 - a)
        val impact = if (changed) 7.52 * (iss - 0.029) - 3.25 * (iss - 0.02).pow(15) else 6.42 * iss
        if (impact <= 0) return 0.0
        val exploitability = 8.22 * av * ac * pr * ui
        return roundUp(
            min(if (changed) 1.08 * (impact + exploitability) else impact + exploitability, 10.0)
        )
    }

    /** CVSS 3.1's round-up to one decimal, which avoids floating-point surprises. */
    private fun roundUp(value: Double): Double {
        val scaled = (value * 100_000).roundToLong()
        return if (scaled % 10_000 == 0L) scaled / 100_000.0
        else (floor(scaled / 10_000.0) + 1) / 10.0
    }
}
