/* Joseph B. Ottinger (C)2026 */
package dev.streampack.safecracker.service

import dev.streampack.core.integration.TickListener
import dev.streampack.core.json.JacksonMappers
import dev.streampack.core.service.Egress
import dev.streampack.core.service.ProvenanceStateService
import dev.streampack.safecracker.model.SafecrackerGameState
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import tools.jackson.module.kotlin.convertValue

/** Monitors active safecracker games for 30-second countdown and 5-minute timeout */
@Component
class SafecrackerTimerService(
    private val egress: Egress,
    private val stateService: ProvenanceStateService,
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
        egress.send("Time's up! The combination was: $answer", game.provenanceUri)
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
        egress.send("Safecracker: $formatted remaining!", game.provenanceUri)
    }
}
