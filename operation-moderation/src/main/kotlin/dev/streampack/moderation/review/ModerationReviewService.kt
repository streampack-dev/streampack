/* Joseph B. Ottinger (C)2026 */
package dev.streampack.moderation.review

import dev.streampack.ai.config.AiProperties
import dev.streampack.ai.service.AiService
import dev.streampack.core.entity.MessageLog
import dev.streampack.core.model.MessageDirection
import dev.streampack.core.model.Provenance
import dev.streampack.core.service.ChannelControlService
import dev.streampack.core.service.DirectConversations
import dev.streampack.core.service.MessageLogService
import dev.streampack.moderation.config.ModerationProperties
import dev.streampack.moderation.entity.ModerationReport
import dev.streampack.moderation.repository.ModerationReportRepository
import dev.streampack.moderation.signal.ModerationScores
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Service

/**
 * The hourly review (#150). For each person whose score reached the threshold since the last one,
 * it reads their recent lines in that channel with the lines around them, asks the moderation model
 * whether they're being hostile to people, and records a report. Usually nobody is marked, and
 * nothing is asked.
 *
 * It reads the log only through [MessageLogService], which never returns a direct or hidden line,
 * and skips any channel that's direct, unlogged or opted out. With AI off, or when the model
 * doesn't answer, the report is recorded from the signals alone. A report decides nothing: an admin
 * does.
 */
@Service
class ModerationReviewService(
    private val scores: ModerationScores,
    private val properties: ModerationProperties,
    private val messageLogService: MessageLogService,
    private val channelControlService: ChannelControlService,
    private val directConversations: DirectConversations,
    private val reports: ModerationReportRepository,
    private val aiServiceProvider: ObjectProvider<AiService>,
    private val aiPropertiesProvider: ObjectProvider<AiProperties>,
) {
    private val logger = LoggerFactory.getLogger(ModerationReviewService::class.java)

    /** Reviews everyone marked since the last review, returning the reports recorded. */
    fun review(now: Instant = Instant.now()): List<ModerationReport> {
        val candidates = scores.takeForReview(now)
        if (candidates.isEmpty()) return emptyList()
        logger.info("Moderation review: {} person(s) to look at", candidates.size)
        return candidates.mapNotNull { candidate ->
            try {
                review(candidate, now)
            } catch (e: Exception) {
                logger.warn(
                    "Moderation review of {} in {} failed: {}",
                    candidate.speaker.sender,
                    candidate.speaker.provenanceUri,
                    e.message,
                )
                null
            }
        }
    }

    private fun review(candidate: ModerationScores.Candidate, now: Instant): ModerationReport? {
        val speaker = candidate.speaker
        val uri = speaker.provenanceUri
        val provenance = runCatching { Provenance.decode(uri) }.getOrNull() ?: return null
        // Checked again here: a channel may have opted out since, and a direct conversation is
        // never reviewed, whatever marked it
        if (directConversations.isDirect(provenance)) return null
        if (!channelControlService.isModerated(provenance)) return null

        val from = minOf(candidate.firstSignalAt, now.minus(properties.window))
        val windowStart = from.minus(properties.window)
        val log =
            messageLogService.findLatestMessages(uri, windowStart, now.plusSeconds(1), LOG_LIMIT)
        val excerpt = excerpt(log, speaker.sender, from, candidate.signalLines)
        val flagged = excerpt.filter {
            isTheirs(it, speaker.sender) && it.content in candidate.signalLines
        }

        val verdict = ask(speaker.sender, provenance, excerpt)
        val cited =
            verdict
                ?.lines
                .orEmpty()
                .mapNotNull { excerpt.getOrNull(it - 1) }
                .filter { isTheirs(it, speaker.sender) }

        val report =
            reports.save(
                ModerationReport(
                    provenanceUri = uri,
                    protocol = speaker.protocol,
                    serviceId = speaker.serviceId,
                    sender = speaker.sender,
                    userId = speaker.userId,
                    score = candidate.peak,
                    signals = candidate.signals.mapKeys { it.key.name },
                    verdictAbusive = verdict?.abusive,
                    verdictReason = verdict?.reason?.trim()?.take(MAX_REASON),
                    verdictModel = verdict?.let { moderationModel() },
                    excerptLineIds = excerpt.map { it.id.toString() },
                    flaggedLineIds = flagged.map { it.id.toString() },
                    citedLineIds = cited.map { it.id.toString() },
                    windowStart = excerpt.firstOrNull()?.timestamp ?: from,
                    windowEnd = excerpt.lastOrNull()?.timestamp ?: now,
                    createdAt = now,
                    updatedAt = now,
                )
            )
        logger.info(
            "Moderation report {} for {} in {}: signals {}, verdict {}",
            report.id,
            speaker.sender,
            uri,
            candidate.signals,
            verdict?.abusive ?: "none",
        )
        return report
    }

    /**
     * Their lines since [from], at most [ModerationProperties.reviewLines] of them, each with
     * [ModerationProperties.contextLines] lines either side, in order. The lines that raised
     * signals are chosen first, the strongest first, and the rest is filled with their most recent:
     * someone who keeps talking after the trouble would otherwise push it out, and the model would
     * judge the chatter instead.
     */
    private fun excerpt(
        log: List<MessageLog>,
        sender: String,
        from: Instant,
        signalLines: Map<String, Double>,
    ): List<MessageLog> {
        val theirs = log.indices.filter { isTheirs(log[it], sender) && log[it].timestamp >= from }
        val signalled =
            theirs
                .filter { log[it].content in signalLines }
                .sortedWith(
                    compareByDescending<Int> { signalLines.getValue(log[it].content) }
                        .thenByDescending { it }
                )
                .take(properties.reviewLines)
        val recent =
            theirs.filterNot { it in signalled }.takeLast(properties.reviewLines - signalled.size)
        val around = sortedSetOf<Int>()
        (signalled + recent).forEach { i ->
            val first = maxOf(0, i - properties.contextLines)
            val last = minOf(log.lastIndex, i + properties.contextLines)
            (first..last).forEach { around += it }
        }
        return around.map { log[it] }
    }

    private fun isTheirs(line: MessageLog, sender: String) =
        line.direction == MessageDirection.INBOUND && line.sender.equals(sender, ignoreCase = true)

    /** The moderation model's verdict, or null when AI is off or it didn't answer. */
    private fun ask(sender: String, provenance: Provenance, excerpt: List<MessageLog>): Verdict? {
        if (excerpt.isEmpty()) return null
        val ai = aiServiceProvider.getIfAvailable() ?: return null
        val transcript =
            excerpt
                .mapIndexed { i, line ->
                    val mark = if (isTheirs(line, sender)) "*" else " "
                    "[${i + 1}]$mark ${TIME.format(line.timestamp)} <${line.sender}> ${line.content}"
                }
                .joinToString("\n")
        val prompt =
            """
            |Person to judge: $sender
            |Channel: ${provenance.replyTo}
            |Their lines are marked with *; the rest are context.
            |
            |$transcript
            """
                .trimMargin()
        return ai.moderation().promptForObject(SYSTEM, prompt, Verdict::class.java)?.takeIf {
            it.reason.isNotBlank()
        }
    }

    private fun moderationModel(): String? = aiPropertiesProvider.getIfAvailable()?.moderationModel

    /** The model's answer: whether it's abuse, why, and the line numbers that show it. */
    data class Verdict(
        val abusive: Boolean = false,
        val reason: String = "",
        val lines: List<Int> = emptyList(),
    )

    companion object {
        private const val LOG_LIMIT = 2000
        private const val MAX_REASON = 2000
        private val TIME = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneOffset.UTC)

        const val SYSTEM =
            """You help the moderators of a public chat channel. You're shown a stretch of the
channel's log and one participant. Decide whether that person is being hostile or abusive toward
other people: insulting, demeaning, threatening or harassing them, using slurs, posting someone's
personal details, or flooding the channel to drown others out. Swearing at code, tools or
themselves, frustration, banter the others are plainly in on, and quoting someone else are not
abuse. Judge only the named person; the other lines are context. Give a short reason, a sentence
or two, and the numbers of their lines that show it (none if it isn't abuse)."""
    }
}
