/* Joseph B. Ottinger (C)2026 */
package dev.streampack.karma.dto

import java.time.LocalDate

data class KarmaLeaderboardResponse(
    val top: List<KarmaLeaderboardEntryResponse>,
    val bottom: List<KarmaLeaderboardEntryResponse>,
    val limit: Int,
)

data class KarmaLeaderboardEntryResponse(
    val subject: String,
    val score: Int,
    val lastUpdated: LocalDate,
)
