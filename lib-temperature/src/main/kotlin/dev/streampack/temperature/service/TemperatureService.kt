/* Joseph B. Ottinger (C)2026 */
package dev.streampack.temperature.service

import dev.streampack.temperature.config.TemperatureProperties
import dev.streampack.temperature.entity.TemperatureBucket
import dev.streampack.temperature.model.TemperatureRankingPage
import dev.streampack.temperature.model.TemperatureScore
import dev.streampack.temperature.repository.TemperatureBucketRepository
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.ceil
import kotlin.math.pow
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/** Records and scores generic time-decayed activity buckets. */
@Service
class TemperatureService(
    private val repository: TemperatureBucketRepository,
    private val properties: TemperatureProperties,
) {
    private val logger = LoggerFactory.getLogger(TemperatureService::class.java)

    @Transactional
    fun accrue(
        namespace: String,
        subjectKey: String,
        signal: String,
        positiveDelta: Long = 1,
        negativeDelta: Long = 0,
        occurredOn: LocalDate = LocalDate.now(),
    ): TemperatureScore {
        require(namespace.isNotBlank()) { "namespace must not be blank" }
        require(subjectKey.isNotBlank()) { "subjectKey must not be blank" }
        require(signal.isNotBlank()) { "signal must not be blank" }
        require(positiveDelta >= 0) { "positiveDelta must not be negative" }
        require(negativeDelta >= 0) { "negativeDelta must not be negative" }
        require(positiveDelta > 0 || negativeDelta > 0) { "at least one delta must be positive" }

        purgeStaleBuckets(occurredOn)
        val normalizedNamespace = namespace.trim()
        val normalizedSubjectKey = subjectKey.trim()
        val normalizedSignal = signal.trim()
        val existing =
            repository.findByNamespaceAndSubjectKeyAndSignalAndBucketDate(
                normalizedNamespace,
                normalizedSubjectKey,
                normalizedSignal,
                occurredOn,
            )

        if (existing == null) {
            repository.save(
                TemperatureBucket(
                    namespace = normalizedNamespace,
                    subjectKey = normalizedSubjectKey,
                    signal = normalizedSignal,
                    bucketDate = occurredOn,
                    positiveDelta = positiveDelta,
                    negativeDelta = negativeDelta,
                )
            )
        } else {
            repository.save(
                existing.copy(
                    positiveDelta = existing.positiveDelta + positiveDelta,
                    negativeDelta = existing.negativeDelta + negativeDelta,
                    updatedAt = Instant.now(),
                )
            )
        }

        return score(normalizedNamespace, normalizedSubjectKey, normalizedSignal, occurredOn)
    }

    @Transactional(readOnly = true)
    fun score(
        namespace: String,
        subjectKey: String,
        signal: String,
        asOf: LocalDate = LocalDate.now(),
    ): TemperatureScore {
        val buckets =
            repository.findByNamespaceAndSubjectKeyAndSignal(
                namespace.trim(),
                subjectKey.trim(),
                signal.trim(),
            )
        return computeScore(namespace.trim(), subjectKey.trim(), signal.trim(), buckets, asOf)
    }

    @Transactional(readOnly = true)
    fun ranking(
        namespace: String,
        signal: String,
        asOf: LocalDate = LocalDate.now(),
        page: Int = 0,
        size: Int = 10,
        ascending: Boolean = false,
    ): TemperatureRankingPage {
        val normalizedNamespace = namespace.trim()
        val normalizedSignal = signal.trim()
        val normalizedPage = page.coerceAtLeast(0)
        val normalizedSize = size.coerceAtLeast(1)
        val comparator =
            if (ascending) {
                compareBy<TemperatureScore> { it.netScore }.thenBy { it.subjectKey }
            } else {
                compareByDescending<TemperatureScore> { it.netScore }.thenBy { it.subjectKey }
            }
        val scores =
            repository
                .findDistinctSubjectKeys(normalizedNamespace, normalizedSignal)
                .map { subjectKey ->
                    val buckets =
                        repository.findByNamespaceAndSubjectKeyAndSignal(
                            normalizedNamespace,
                            subjectKey,
                            normalizedSignal,
                        )
                    computeScore(normalizedNamespace, subjectKey, normalizedSignal, buckets, asOf)
                }
                .filter { it.netScore != 0.0 }
                .sortedWith(comparator)
        val fromIndex = (normalizedPage * normalizedSize).coerceAtMost(scores.size)
        val toIndex = (fromIndex + normalizedSize).coerceAtMost(scores.size)
        val totalPages =
            if (scores.isEmpty()) 0 else ceil(scores.size.toDouble() / normalizedSize).toInt()
        return TemperatureRankingPage(
            scores = scores.subList(fromIndex, toIndex),
            page = normalizedPage,
            size = normalizedSize,
            totalPages = totalPages,
            totalCount = scores.size.toLong(),
        )
    }

    private fun computeScore(
        namespace: String,
        subjectKey: String,
        signal: String,
        buckets: List<TemperatureBucket>,
        asOf: LocalDate,
    ): TemperatureScore {
        var positive = 0.0
        var negative = 0.0
        for (bucket in buckets) {
            val weight = decayWeight(bucket.bucketDate, asOf)
            positive += bucket.positiveDelta * weight
            negative += bucket.negativeDelta * weight
        }
        return TemperatureScore(
            namespace = namespace,
            subjectKey = subjectKey,
            signal = signal,
            positiveScore = positive,
            negativeScore = negative,
            netScore = positive - negative,
            lastUpdated = buckets.maxOfOrNull { it.bucketDate },
        )
    }

    private fun decayWeight(bucketDate: LocalDate, asOf: LocalDate): Double {
        val ageDays = ChronoUnit.DAYS.between(bucketDate, asOf).coerceAtLeast(0)
        return 0.5.pow(ageDays / properties.halfLifeDays)
    }

    private fun purgeStaleBuckets(asOf: LocalDate) {
        val cutoff = asOf.minusDays(properties.retentionDays)
        val removed = repository.deleteByBucketDateBefore(cutoff)
        if (removed > 0) {
            logger.info("Removed {} temperature bucket(s) older than {}", removed, cutoff)
        }
    }
}
