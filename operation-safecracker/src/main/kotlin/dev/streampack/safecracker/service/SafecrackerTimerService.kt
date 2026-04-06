/* Joseph B. Ottinger (C)2026 */
package dev.streampack.safecracker.service

import dev.streampack.core.integration.TickListener
import dev.streampack.core.json.JacksonMappers
import dev.streampack.core.model.OperationResult
import dev.streampack.core.model.Provenance
import dev.streampack.core.service.ProvenanceStateService
import dev.streampack.core.service.TransformerChainService
import dev.streampack.safecracker.model.SafecrackerGameState
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.messaging.MessageChannel
import org.springframework.messaging.support.MessageBuilder
import org.springframework.stereotype.Component
import tools.jackson.module.kotlin.convertValue

/** Monitors active safecracker games for 30-second countdown and 5-minute timeout */
@Component
class SafecrackerTimerService(
    @Qualifier("egressChannel") private val egressChannel: MessageChannel,
    private val stateService: ProvenanceStateService,
    private val transformerChain: TransformerChainService,
) : TickListener {

    private val logger = LoggerFactory.getLogger(SafecrackerTimerService::class.java)
    private val objectMapper = JacksonMappers.standard()
    private val activeGames = ConcurrentHashMap<String, ActiveGame>()

    fun registerGame(provenanceUri: String, startedAt: Instant) {
        activeGames[provenanceUri] = ActiveGame(provenanceUri, startedAt, startedAt)
    }

    fun unregisterGame(provenanceUri: String) {
        activeGames.remove(provenanceUri)
    }

    override fun onTick(now: Instant) {
        activeGames.values.toList().forEach { game -> checkGame(game, now) }
    }

    private fun checkGame(game: ActiveGame, now: Instant) {
        val elapsed = Duration.between(game.startedAt, now)

        if (elapsed >= SafecrackerGameState.GAME_DURATION) {
            handleTimeout(game)
            return
        }

        val sinceLastAnnouncement = Duration.between(game.lastAnnouncementAt, now)
        if (sinceLastAnnouncement >= SafecrackerGameState.ANNOUNCEMENT_INTERVAL) {
            handleAnnouncement(game, now)
        }
    }

    private fun handleTimeout(game: ActiveGame) {
        val data = stateService.getState(game.provenanceUri, SafecrackerGameState.STATE_KEY)
        if (data == null) {
            activeGames.remove(game.provenanceUri)
            return
        }

        val state = objectMapper.convertValue<SafecrackerGameState>(data)
        stateService.clearState(game.provenanceUri, SafecrackerGameState.STATE_KEY)
        activeGames.remove(game.provenanceUri)

        val answer = state.combination.joinToString(" ")
        sendToEgress("Time's up! The combination was: $answer", game.provenanceUri)
        logger.info("Safecracker game timed out for {}", game.provenanceUri)
    }

    private fun handleAnnouncement(game: ActiveGame, now: Instant) {
        val data = stateService.getState(game.provenanceUri, SafecrackerGameState.STATE_KEY)
        if (data == null) {
            activeGames.remove(game.provenanceUri)
            return
        }

        val state = objectMapper.convertValue<SafecrackerGameState>(data)
        val formatted = state.formatTimeRemaining(now)
        game.lastAnnouncementAt =
            game.lastAnnouncementAt.plus(SafecrackerGameState.ANNOUNCEMENT_INTERVAL)
        sendToEgress("Safecracker: $formatted remaining!", game.provenanceUri)
    }

    private fun sendToEgress(text: String, destinationUri: String) {
        try {
            val provenance = Provenance.decode(destinationUri)
            val raw = OperationResult.Success(text)
            val transformed = transformerChain.apply(raw, provenance)
            val message =
                MessageBuilder.withPayload(transformed as Any)
                    .setHeader(Provenance.HEADER, provenance)
                    .build()
            egressChannel.send(message)
        } catch (e: Exception) {
            logger.warn(
                "Failed to send safecracker notification to {}: {}",
                destinationUri,
                e.message,
            )
        }
    }
}
