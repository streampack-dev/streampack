/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.service

import java.util.concurrent.ConcurrentHashMap
import org.springframework.stereotype.Service

@Service
class FactoidUpdateBuffer {
    private val pendingSelectors = ConcurrentHashMap.newKeySet<String>()

    fun record(selector: String) {
        val normalized = selector.trim().lowercase()
        if (normalized.isNotBlank()) {
            pendingSelectors += normalized
        }
    }

    fun drain(): Set<String> {
        if (pendingSelectors.isEmpty()) return emptySet()
        val drained = pendingSelectors.toSet()
        pendingSelectors.removeAll(drained)
        return drained
    }

    fun pendingSelectors(): Set<String> = pendingSelectors.toSet()
}
