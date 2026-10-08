/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.atlas

import java.sql.Timestamp
import java.time.Instant
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate

/** The stored map: its regions and places, and when the oldest place was laid out. */
data class StoredMap(
    val regions: List<LaidRegion>,
    val places: List<LaidPlace>,
    val laidOutAt: Instant?,
) {
    val isEmpty: Boolean
        get() = places.isEmpty()
}

/**
 * The Atlas's geography (`atlas_region`, `atlas_place`, V69), as ui-pudl kept it in its own schema
 * before it moved here. A relayout replaces it; newcomers are added beside it; nothing else writes.
 * Reads aren't cached here: [Atlas] keeps the whole answer, keyed by [fingerprint].
 */
@Component
class AtlasStore(private val jdbc: JdbcTemplate, private val transactions: TransactionTemplate) {
    fun load(): StoredMap {
        val regions =
            jdbc.query("SELECT id, name, x, y FROM atlas_region ORDER BY id") { rs, _ ->
                LaidRegion(
                    rs.getInt("id"),
                    rs.getString("name"),
                    rs.getDouble("x"),
                    rs.getDouble("y"),
                )
            }
        var oldest: Instant? = null
        val places =
            jdbc.query("SELECT tag, region, x, y, placed_at FROM atlas_place ORDER BY tag") { rs, _
                ->
                val at = rs.getTimestamp("placed_at").toInstant()
                if (oldest == null || at.isBefore(oldest)) oldest = at
                LaidPlace(
                    rs.getString("tag"),
                    rs.getInt("region"),
                    rs.getDouble("x"),
                    rs.getDouble("y"),
                )
            }
        return StoredMap(regions, places, oldest)
    }

    /** What's stored, cheaply: changes whenever a place or region is written. */
    fun fingerprint(): String =
        jdbc.queryForObject(
            """
            SELECT (SELECT count(*) FROM atlas_place) || ':'
                || coalesce((SELECT max(placed_at) FROM atlas_place)::text, '') || ':'
                || coalesce((SELECT min(placed_at) FROM atlas_place)::text, '') || ':'
                || (SELECT count(*) FROM atlas_region)
            """,
            String::class.java,
        )!!

    /** Replaces the whole map: a relayout, or the first layout. */
    fun replace(layout: Layout, now: Instant) {
        val at = Timestamp.from(now)
        transactions.executeWithoutResult {
            jdbc.update("DELETE FROM atlas_place")
            jdbc.update("DELETE FROM atlas_region")
            jdbc.batchUpdate(
                "INSERT INTO atlas_region (id, name, x, y) VALUES (?, ?, ?, ?)",
                layout.regions.map { arrayOf<Any>(it.id, it.name, it.x, it.y) },
            )
            jdbc.batchUpdate(
                "INSERT INTO atlas_place (tag, region, x, y, placed_at) VALUES (?, ?, ?, ?, ?)",
                layout.places.map { arrayOf<Any>(it.tag, it.region, it.x, it.y, at) },
            )
        }
    }

    /**
     * Adds places for newcomers, and any region they needed, leaving every place already stored
     * where it is. A tag stored meanwhile (another instance placing it first) keeps its first
     * place.
     */
    fun add(places: List<LaidPlace>, regions: List<LaidRegion>, now: Instant) {
        if (places.isEmpty() && regions.isEmpty()) return
        val at = Timestamp.from(now)
        transactions.executeWithoutResult {
            regions.forEach {
                jdbc.update(
                    "INSERT INTO atlas_region (id, name, x, y) VALUES (?, ?, ?, ?) ON CONFLICT (id) DO NOTHING",
                    it.id,
                    it.name,
                    it.x,
                    it.y,
                )
            }
            places.forEach {
                jdbc.update(
                    "INSERT INTO atlas_place (tag, region, x, y, placed_at) VALUES (?, ?, ?, ?, ?) ON CONFLICT (tag) DO NOTHING",
                    it.tag,
                    it.region,
                    it.x,
                    it.y,
                    at,
                )
            }
        }
    }
}
