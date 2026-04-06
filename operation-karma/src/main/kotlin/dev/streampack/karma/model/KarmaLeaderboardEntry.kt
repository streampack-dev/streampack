/* Joseph B. Ottinger (C)2026 */
package dev.streampack.karma.model

import java.time.LocalDate

/** One leaderboard row with decayed score and last update date. */
data class KarmaLeaderboardEntry(val subject: String, val score: Int, val lastUpdated: LocalDate)
