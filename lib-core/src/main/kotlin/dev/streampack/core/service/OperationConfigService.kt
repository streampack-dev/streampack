/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.service

import com.enigmastation.streampack.core.entity.OperationConfig
import com.enigmastation.streampack.core.repository.OperationConfigRepository
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import org.slf4j.LoggerFactory
import org.springframework.core.env.Environment
import org.springframework.stereotype.Service

/** Manages per-provenance operation enablement using three-tier cascade resolution */
@Service
class OperationConfigService(
    private val repository: OperationConfigRepository,
    private val environment: Environment,
) {
    private val logger = LoggerFactory.getLogger(OperationConfigService::class.java)
    private val enabledCache = ConcurrentHashMap<String, Boolean>()
    private val configCache = ConcurrentHashMap<String, Map<String, Any>>()

    /**
     * Resolves whether an operation group is enabled for a given provenance URI.
     *
     * Resolution picks the most specific matching row (longest provenance_pattern prefix match).
     * Returns true when no configuration exists (enabled by default).
     */
    fun isOperationEnabled(provenanceUri: String, operationGroup: String): Boolean {
        val cacheKey = "$provenanceUri:$operationGroup"
        return enabledCache.computeIfAbsent(cacheKey) {
            resolveEnabled(provenanceUri, operationGroup)
        }
    }

    /**
     * Returns the merged config map for an operation group at a given provenance URI.
     *
     * Config maps are merged from least specific to most specific, so more specific keys win.
     */
    fun getOperationConfig(provenanceUri: String, operationGroup: String): Map<String, Any> {
        val cacheKey = "$provenanceUri:$operationGroup"
        return configCache.computeIfAbsent(cacheKey) {
            resolveConfig(provenanceUri, operationGroup)
        }
    }

    /** Sets the enabled state for a (provenancePattern, operationGroup) pair */
    fun setEnabled(
        provenancePattern: String,
        operationGroup: String,
        enabled: Boolean,
    ): OperationConfig {
        val config = getOrCreate(provenancePattern, operationGroup)
        val updated = config.copy(enabled = enabled, updatedAt = Instant.now())
        val saved = repository.save(updated)
        clearCache()
        logger.info(
            "Set operation group '{}' {} for pattern '{}'",
            operationGroup,
            if (enabled) "enabled" else "disabled",
            provenancePattern.ifEmpty { "(global)" },
        )
        return saved
    }

    /** Sets a config key for a (provenancePattern, operationGroup) pair */
    fun setConfigValue(
        provenancePattern: String,
        operationGroup: String,
        key: String,
        value: String,
    ): OperationConfig {
        val config = getOrCreate(provenancePattern, operationGroup)
        val newConfigMap = config.config.toMutableMap()
        newConfigMap[key] = value
        val updated = config.copy(config = newConfigMap, updatedAt = Instant.now())
        val saved = repository.save(updated)
        clearCache()
        return saved
    }

    /** Returns the raw config row for a specific (pattern, group) pair, or null */
    fun findConfig(provenancePattern: String, operationGroup: String): OperationConfig? =
        repository.findByProvenancePatternAndOperationGroup(provenancePattern, operationGroup)

    /** Returns all config rows for a given operation group */
    fun findByGroup(operationGroup: String): List<OperationConfig> =
        repository.findByOperationGroup(operationGroup)

    /** Returns all config rows */
    fun findAll(): List<OperationConfig> = repository.findAll()

    /** Seeds service:* entries from current environment if none exist in DB yet */
    fun seedServiceConfigs(serviceNames: List<String>) {
        val existing = repository.findAll().any { it.operationGroup.startsWith("service:") }
        if (existing) return
        for (name in serviceNames) {
            val propKey = "streampack.$name.enabled"
            val enabled = environment.getProperty(propKey, Boolean::class.java, false)
            repository.save(
                OperationConfig(
                    provenancePattern = "",
                    operationGroup = "service:$name",
                    enabled = enabled,
                )
            )
            logger.info("Seeded service config: service:{} = {}", name, enabled)
        }
    }

    /** Clears the resolution cache (called after any write) */
    fun clearCache() {
        enabledCache.clear()
        configCache.clear()
    }

    private fun resolveEnabled(provenanceUri: String, operationGroup: String): Boolean {
        val rows = repository.findByOperationGroup(operationGroup)
        val matching = rows.filter { provenanceUri.startsWith(it.provenancePattern) }
        if (matching.isEmpty()) return true
        return matching.maxBy { it.provenancePattern.length }.enabled
    }

    private fun resolveConfig(provenanceUri: String, operationGroup: String): Map<String, Any> {
        val rows = repository.findByOperationGroup(operationGroup)
        val matching =
            rows
                .filter { provenanceUri.startsWith(it.provenancePattern) }
                .sortedBy { it.provenancePattern.length }
        val merged = mutableMapOf<String, Any>()
        for (row in matching) {
            merged.putAll(row.config)
        }
        return merged
    }

    private fun getOrCreate(provenancePattern: String, operationGroup: String): OperationConfig {
        val existing =
            repository.findByProvenancePatternAndOperationGroup(provenancePattern, operationGroup)
        if (existing != null) return existing
        return repository.save(
            OperationConfig(provenancePattern = provenancePattern, operationGroup = operationGroup)
        )
    }
}
