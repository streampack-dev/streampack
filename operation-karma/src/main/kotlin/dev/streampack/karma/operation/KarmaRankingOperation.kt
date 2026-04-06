/* Joseph B. Ottinger (C)2026 */
package dev.streampack.karma.operation

import dev.streampack.core.model.OperationOutcome
import dev.streampack.core.model.OperationResult
import dev.streampack.core.service.TranslatingOperation
import dev.streampack.karma.model.KarmaRankingRequest
import dev.streampack.karma.model.RankingDirection
import dev.streampack.karma.service.KarmaService
import org.springframework.messaging.Message
import org.springframework.stereotype.Component

/** Handles addressed karma ranking queries: "top karma [N]" or "bottom [N] karma" */
@Component
class KarmaRankingOperation(private val karmaService: KarmaService) :
    TranslatingOperation<KarmaRankingRequest>(KarmaRankingRequest::class) {

    override val priority: Int = 50
    override val addressed: Boolean = true
    override val operationGroup: String = "karma"

    override fun translate(payload: String, message: Message<*>): KarmaRankingRequest? {
        val tokens = payload.trim().split("\\s+".toRegex())
        if (tokens.size < 2) return null

        val direction =
            when (tokens[0].lowercase()) {
                "top" -> RankingDirection.TOP
                "bottom" -> RankingDirection.BOTTOM
                else -> return null
            }

        if (!tokens.any { it.equals("karma", ignoreCase = true) }) return null

        val count =
            tokens
                .filter {
                    !it.equals("top", true) &&
                        !it.equals("bottom", true) &&
                        !it.equals("karma", true)
                }
                .firstNotNullOfOrNull { it.toIntOrNull() } ?: DEFAULT_LIMIT

        val clamped = count.coerceIn(1, MAX_LIMIT)
        return KarmaRankingRequest(direction, clamped)
    }

    override fun handle(payload: KarmaRankingRequest, message: Message<*>): OperationOutcome {
        val ascending = payload.direction == RankingDirection.BOTTOM
        val ranking = karmaService.getRanking(payload.limit, ascending)

        if (ranking.isEmpty()) {
            return OperationResult.Success("No karma data yet.")
        }

        val label = if (ascending) "Bottom" else "Top"
        val prefix = "$label karma: "

        val rendered = buildFittingResponse(prefix, ranking)
        return OperationResult.Success(rendered)
    }

    /** Builds the response string, adding entries until the next would exceed the line budget */
    private fun buildFittingResponse(prefix: String, entries: List<Pair<String, Int>>): String {
        val sb = StringBuilder(prefix)
        var count = 0
        for ((subject, score) in entries) {
            val entry = if (count == 0) "$subject($score)" else ", $subject($score)"
            if (sb.length + entry.length > MAX_LINE_LENGTH) break
            sb.append(entry)
            count++
        }
        if (count == 0) {
            return "${prefix}(none fit within line limit)"
        }
        return sb.toString()
    }

    companion object {
        const val DEFAULT_LIMIT = 5
        const val MAX_LIMIT = 10
        const val MAX_LINE_LENGTH = 400
    }
}
