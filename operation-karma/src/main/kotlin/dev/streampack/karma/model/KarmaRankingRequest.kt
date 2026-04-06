/* Joseph B. Ottinger (C)2026 */
package dev.streampack.karma.model

/** Typed request for karma leaderboard: top or bottom N subjects by decayed score */
data class KarmaRankingRequest(val direction: RankingDirection, val limit: Int = 5)
