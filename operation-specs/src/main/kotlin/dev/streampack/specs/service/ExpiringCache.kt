/* Joseph B. Ottinger (C)2026 */
package dev.streampack.specs.service

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * A small cache whose entries expire [ttl] after they're loaded. streampack has no shared cache
 * yet; this one is enough for a few lookups an hour. A loader that throws stores nothing, so a
 * failure is tried again next time. Past [maxEntries], expired entries are dropped, then the
 * oldest.
 */
class ExpiringCache<K : Any, V : Any>(
    private val ttl: Duration,
    private val clock: Clock,
    private val maxEntries: Int = 500,
) {
    private data class Entry<V>(val value: V, val expires: Instant)

    private val entries = ConcurrentHashMap<K, Entry<V>>()

    /** The value for [key], loaded with [loader] if it's absent or expired. */
    fun get(key: K, loader: () -> V): V {
        val now = clock.instant()
        entries[key]
            ?.takeIf { it.expires.isAfter(now) }
            ?.let {
                return it.value
            }
        val value = loader()
        entries[key] = Entry(value, now.plus(ttl))
        if (entries.size > maxEntries) prune(now)
        return value
    }

    fun clear() = entries.clear()

    private fun prune(now: Instant) {
        entries.entries.removeIf { !it.value.expires.isAfter(now) }
        val excess = entries.size - maxEntries
        if (excess > 0) {
            entries.entries
                .sortedBy { it.value.expires }
                .take(excess)
                .forEach { entries.remove(it.key) }
        }
    }
}
