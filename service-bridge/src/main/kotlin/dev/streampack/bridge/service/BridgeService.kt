/* Joseph B. Ottinger (C)2026 */
package dev.streampack.bridge.service

import dev.streampack.bridge.entity.BridgePair
import dev.streampack.bridge.repository.BridgePairRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Manages exclusive directional bridge pairs between provenance endpoints.
 *
 * Each provenance URI can participate in at most one pair. The pair tracks copy direction(s).
 */
@Service
@Transactional
class BridgeService(private val pairRepository: BridgePairRepository) {
    private val logger = LoggerFactory.getLogger(BridgeService::class.java)

    /**
     * Establishes a one-way copy from sourceUri to targetUri.
     *
     * If the two URIs are already paired, updates the direction flag. If neither is paired, creates
     * a new pair. If either URI is already paired with a different URI, returns an error message.
     */
    fun copy(sourceUri: String, targetUri: String): CopyResult {
        if (sourceUri == targetUri) {
            return CopyResult.Error("Cannot bridge a channel to itself")
        }

        val existingPair = findPairContaining(sourceUri, targetUri)

        if (existingPair != null) {
            // Both are in the same pair - just update direction
            val updated =
                if (existingPair.firstUri == sourceUri) {
                    if (existingPair.copyFirstToSecond) {
                        return CopyResult.AlreadyExists(existingPair)
                    }
                    existingPair.copy(copyFirstToSecond = true)
                } else {
                    if (existingPair.copySecondToFirst) {
                        return CopyResult.AlreadyExists(existingPair)
                    }
                    existingPair.copy(copySecondToFirst = true)
                }
            pairRepository.save(updated)
            logger.info("Updated bridge direction: {} -> {}", sourceUri, targetUri)
            return CopyResult.Success(updated)
        }

        // Check if either URI is already paired with someone else
        val sourceConflict = findPairFor(sourceUri)
        if (sourceConflict != null) {
            val partner = partnerOf(sourceConflict, sourceUri)
            return CopyResult.Error("$sourceUri is already paired with $partner")
        }
        val targetConflict = findPairFor(targetUri)
        if (targetConflict != null) {
            val partner = partnerOf(targetConflict, targetUri)
            return CopyResult.Error("$targetUri is already paired with $partner")
        }

        // Create new pair
        val pair =
            pairRepository.save(
                BridgePair(firstUri = sourceUri, secondUri = targetUri, copyFirstToSecond = true)
            )
        logger.info("Created bridge pair: {} -> {}", sourceUri, targetUri)
        return CopyResult.Success(pair)
    }

    /** Removes a one-way copy. If both directions are gone, dissolves the pair. */
    fun removeCopy(sourceUri: String, targetUri: String): Boolean {
        val pair = findPairContaining(sourceUri, targetUri) ?: return false

        val updated =
            if (pair.firstUri == sourceUri) {
                pair.copy(copyFirstToSecond = false)
            } else {
                pair.copy(copySecondToFirst = false)
            }

        if (!updated.copyFirstToSecond && !updated.copySecondToFirst) {
            pairRepository.save(updated.copy(deleted = true))
            logger.info("Dissolved bridge pair between {} and {}", pair.firstUri, pair.secondUri)
        } else {
            pairRepository.save(updated)
            logger.info("Removed bridge direction: {} -> {}", sourceUri, targetUri)
        }
        return true
    }

    /** Returns the URIs that the given provenance copies TO */
    @Transactional(readOnly = true)
    fun getCopyTargets(provenanceUri: String): List<String> {
        val pair = findPairFor(provenanceUri) ?: return emptyList()
        val targets = mutableListOf<String>()
        if (pair.firstUri == provenanceUri && pair.copyFirstToSecond) {
            targets.add(pair.secondUri)
        }
        if (pair.secondUri == provenanceUri && pair.copySecondToFirst) {
            targets.add(pair.firstUri)
        }
        return targets
    }

    /** Returns true if the given provenance has any copy targets */
    @Transactional(readOnly = true)
    fun hasCopyTargets(provenanceUri: String): Boolean {
        return getCopyTargets(provenanceUri).isNotEmpty()
    }

    /** Lists all active bridge pairs */
    @Transactional(readOnly = true)
    fun listAll(): List<BridgePair> = pairRepository.findByDeletedFalse()

    /** Returns the pair containing the given URI, or null */
    @Transactional(readOnly = true)
    fun findPairFor(provenanceUri: String): BridgePair? {
        return pairRepository.findByFirstUriAndDeletedFalse(provenanceUri)
            ?: pairRepository.findBySecondUriAndDeletedFalse(provenanceUri)
    }

    /** Returns the pair containing both URIs, or null if they are not paired together */
    private fun findPairContaining(uriA: String, uriB: String): BridgePair? {
        return pairRepository.findByFirstUriAndSecondUriAndDeletedFalse(uriA, uriB)
            ?: pairRepository.findByFirstUriAndSecondUriAndDeletedFalse(uriB, uriA)
    }

    /** Returns the other URI in the pair */
    private fun partnerOf(pair: BridgePair, uri: String): String {
        return if (pair.firstUri == uri) pair.secondUri else pair.firstUri
    }

    /** Result of a copy operation */
    sealed class CopyResult {
        data class Success(val pair: BridgePair) : CopyResult()

        data class AlreadyExists(val pair: BridgePair) : CopyResult()

        data class Error(val message: String) : CopyResult()
    }
}
