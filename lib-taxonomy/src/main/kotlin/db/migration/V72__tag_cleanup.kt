/* Joseph B. Ottinger (C)2026 */
@file:Suppress("ClassName")

package db.migration

import dev.streampack.taxonomy.TagNames
import dev.streampack.taxonomy.TagVocabulary
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import org.flywaydb.core.api.migration.BaseJavaMigration
import org.flywaydb.core.api.migration.Context
import org.slf4j.LoggerFactory

/**
 * The tag cleanup decided for #140 (PR 3): hyphenated tags renamed to their spaced form, chosen
 * merges, and the removals. The list is fixed here, as decided on 2026-10-08; nothing else is
 * touched.
 *
 * Each rename or merge does what an admin's alias does (`TagCuration.alias`, with the SQL of
 * `PostTagUsages` and `FactoidTagUsages`): the kept tag's row is made when there's none;
 * `post_tags` are re-pointed, one row per post; factoid tag lists have the old name replaced where
 * it stood, without repeats; aliases of the old row move to the kept one, and the old row goes; the
 * old name becomes an alias when it isn't already the kept name normalized (a hyphenated name is,
 * and normalizing finds the kept tag without one). It also moves the old name's Atlas place to the
 * kept tag, or drops it when the kept tag has one. A hyphen rename (the old name normalizes to the
 * kept one) whose kept tag has no row yet renames the old row in place, so its id stays and its
 * slug still fits; the `tags` trigger (V47) refreshes the search.
 *
 * A removal drops the tag from posts, factoids, `tags` and the Atlas, and stoplists it. `auth` is
 * dropped from factoids and its unused row and place go, but it's neither aliased nor stoplisted;
 * `kotauth` carries `identity` in its place.
 *
 * Every change is recorded in `tag_action` as done by `migration`. A name that's nowhere is
 * skipped, so a development database missing some of these tags is fine, and running it again
 * changes nothing. Tables from modules that aren't present (posts, factoids, the Atlas) are skipped
 * too.
 *
 * It logs as it goes (`V72: start`, a line for each entry it changes, then a summary), and reads
 * only what carries a listed name: `post_tags` by tag, and the factoid tag lists that contain one
 * of the names, matched element by element in code.
 *
 * Kotlin rather than SQL, as V62 was, so that factoid tag lists are matched element by element with
 * [TagNames.stored] (`auth` never touches `oauth2`) and new slugs use the vocabulary's own
 * [TagVocabulary.slugify].
 */
class V72__tag_cleanup : BaseJavaMigration() {

    override fun migrate(context: Context) {
        cleanup(context.connection)
    }

    companion object {
        /** Who the changes are recorded as made by. */
        const val ACTOR = "migration"

        /** Hyphens become spaces. */
        val RENAMES: List<Pair<String, String>> =
            listOf(
                "load-testing" to "load testing",
                "content-extraction" to "content extraction",
                "jakarta-ee" to "jakarta ee",
                "anti-pattern" to "anti pattern",
                "remote-access" to "remote access",
                "formal-languages" to "formal languages",
                "access-control" to "access control",
            )

        /** The old name becomes an alias of the kept one. After [RENAMES]: `jakarta ee` exists. */
        val MERGES: List<Pair<String, String>> =
            listOf(
                "build tools" to "build tool",
                "compilers" to "compiler",
                "frameworks" to "framework",
                "tools" to "tooling",
                "tunneling" to "tunnel",
                "distributed" to "distributed computing",
                "jakarta" to "jakarta ee",
                "rdms" to "rdbms",
                "consoleio" to "console",
                "ioc" to "dependency injection",
                "di" to "dependency injection",
                "scm" to "version control",
                "standard" to "specification",
                "authorization" to "security",
                "authentication" to "identity",
            )

        /** Dropped everywhere and stoplisted. */
        val REMOVALS: List<String> = listOf("self-hosted", "new", "26")

        /**
         * Dropped from factoids, neither aliased nor stoplisted; [AUTH_HOLDER] gains [AUTH_GAIN].
         */
        const val AUTH = "auth"
        const val AUTH_HOLDER = "kotauth"
        const val AUTH_GAIN = "identity"

        /** How often a long loop reports progress, in rows. */
        const val PROGRESS_EVERY = 500

        private val log = LoggerFactory.getLogger(V72__tag_cleanup::class.java)

        /** Applies the cleanup on [connection]; returns what changed, a line each, as logged. */
        fun cleanup(connection: Connection): List<String> = Cleanup(connection).run()

        /** Every name, as stored, that the list touches: what a factoid tag list is selected by. */
        private fun listedNames(): Set<String> =
            (RENAMES.map { it.first } + MERGES.map { it.first } + REMOVALS + AUTH)
                .flatMap { setOfNotNull(TagNames.stored(it), TagNames.normalize(it)) }
                .toSet()
    }

    private class Factoid(val attributeId: UUID, val selector: String, var tags: String)

    private class Cleanup(private val c: Connection) {
        private val started = System.currentTimeMillis()
        private val changes = mutableListOf<String>()
        private var totalPosts = 0
        private var totalFactoids = 0
        private var totalTags = 0
        private var totalPlaces = 0
        private var factoidWrites = 0

        init {
            log.info("V72: start")
        }

        private val hasPosts = exists("post_tags")
        private val hasAtlas = exists("atlas_place")
        private val factoids: List<Factoid> =
            if (exists("factoid_attributes")) loadFactoids() else emptyList()

        fun run(): List<String> {
            log.info(
                "V72: {} factoid tag lists carry a listed name{}{}",
                factoids.size,
                if (hasPosts) "" else "; no post_tags here",
                if (hasAtlas) "" else "; no atlas_place here",
            )
            RENAMES.forEach { (old, kept) -> merge(old, kept) }
            MERGES.forEach { (old, kept) -> merge(old, kept) }
            REMOVALS.forEach(::remove)
            auth()
            log.info(
                "V72: done in {} ms: {} changes, {} posts, {} factoids, {} tags, {} atlas rows",
                System.currentTimeMillis() - started,
                changes.size,
                totalPosts,
                totalFactoids,
                totalTags,
                totalPlaces,
            )
            return changes
        }

        private fun changed(line: String) {
            changes += line
            log.info("V72: {}", line)
        }

        /** [old] onto [kept], as an admin's alias does, and its Atlas place. */
        private fun merge(old: String, kept: String) {
            val key = TagNames.normalize(old)!!
            val sources = setOfNotNull(TagNames.stored(old), key) - kept
            var oldIds = tagIds(sources)
            val factoidHits = factoids.count { carries(it, sources) }
            val placeHits = places(sources)
            if (oldIds.isEmpty() && factoidHits == 0 && placeHits.isEmpty()) return

            var keptId = tagId(kept)
            var renamed = false
            if (keptId == null && key == kept && oldIds.size == 1) {
                totalTags += update("UPDATE tags SET name = ? WHERE id = ?", kept, oldIds.single())
                keptId = oldIds.single()
                oldIds = emptyList()
                renamed = true
            }
            val target = keptId ?: create(kept).also { totalTags++ }

            var posts = 0
            if (hasPosts) {
                if (renamed) posts = postsCarrying(target)
                for (oldId in oldIds) {
                    posts += postsCarrying(oldId)
                    update(
                        """
                        INSERT INTO post_tags (id, post_id, tag_id)
                        SELECT gen_random_uuid(), pt.post_id, ?
                        FROM post_tags pt
                        WHERE pt.tag_id = ?
                          AND NOT EXISTS (
                            SELECT 1 FROM post_tags x WHERE x.post_id = pt.post_id AND x.tag_id = ?
                          )
                        """,
                        target,
                        oldId,
                        target,
                    )
                    update("DELETE FROM post_tags WHERE tag_id = ?", oldId)
                }
            }
            for (oldId in oldIds) {
                update("UPDATE tag_alias SET tag_id = ? WHERE tag_id = ?", target, oldId)
                totalTags += update("DELETE FROM tags WHERE id = ?", oldId)
            }
            val aliased =
                key != kept &&
                    update(
                        """
                        INSERT INTO tag_alias (alias, tag_id, created_by, created_at)
                        VALUES (?, ?, ?, ?) ON CONFLICT (alias) DO NOTHING
                        """,
                        key,
                        target,
                        ACTOR,
                        now(),
                    ) > 0
            val factoidsChanged = retagFactoids(sources) { listOf(kept) }
            val place = movePlace(sources, kept)
            closeReview(sources + key - kept, "ALIASED")
            record("ALIAS", old, kept)
            totalPosts += posts
            changed(
                "$old -> $kept: " +
                    listOfNotNull(
                            if (renamed) "row renamed" else null,
                            "$posts posts",
                            "$factoidsChanged factoids",
                            if (aliased) "alias added" else null,
                            place,
                        )
                        .joinToString(", ")
            )
        }

        /** [term] dropped everywhere, and stoplisted; nothing when it's nowhere. */
        private fun remove(term: String) {
            val key = TagNames.normalize(term)!!
            val sources = setOfNotNull(TagNames.stored(term), key)
            val oldIds = tagIds(sources)
            var posts = 0
            if (hasPosts) {
                for (oldId in oldIds) {
                    posts += postsCarrying(oldId)
                    update("DELETE FROM post_tags WHERE tag_id = ?", oldId)
                }
            }
            oldIds.forEach { totalTags += update("DELETE FROM tags WHERE id = ?", it) }
            val factoidsChanged = retagFactoids(sources) { emptyList() }
            val places = deletePlaces(sources)
            val removed = oldIds.isNotEmpty() || factoidsChanged > 0 || places > 0
            if (!removed) return
            val stopped =
                update(
                    """
                    INSERT INTO tag_stop (term, created_by, created_at)
                    VALUES (?, ?, ?) ON CONFLICT (term) DO NOTHING
                    """,
                    key,
                    ACTOR,
                    now(),
                ) > 0
            closeReview(sources, "DISMISSED")
            record("REMOVE", term, "$posts posts, $factoidsChanged factoids")
            if (stopped) record("STOP", key, null)
            totalPosts += posts
            changed(
                "$term removed: $posts posts, $factoidsChanged factoids, $places places" +
                    if (stopped) ", stoplisted as '$key'" else ""
            )
        }

        /**
         * `auth` out of factoids, `kotauth` carrying `identity` instead; its unused row and place
         * go.
         */
        private fun auth() {
            val sources = setOf(AUTH)
            val holderChanged = factoids.any { isHolder(it) && carries(it, sources) }
            if (holderChanged && tagId(AUTH_GAIN) == null) {
                create(AUTH_GAIN)
                totalTags++
            }
            val factoidsChanged =
                retagFactoids(sources) { if (isHolder(it)) listOf(AUTH_GAIN) else emptyList() }
            var rows = 0
            for (id in tagIds(sources)) {
                if (hasPosts && postsCarrying(id) > 0) continue
                rows += update("DELETE FROM tags WHERE id = ?", id)
            }
            totalTags += rows
            val places = deletePlaces(sources)
            if (factoidsChanged == 0 && rows == 0 && places == 0) return
            val detail =
                "$factoidsChanged factoids" +
                    if (holderChanged) "; $AUTH_HOLDER gains $AUTH_GAIN" else ""
            record("REMOVE", AUTH, detail)
            changed("$AUTH removed: $detail, $rows rows, $places places")
        }

        private fun isHolder(f: Factoid) = f.selector.trim().lowercase() == AUTH_HOLDER

        private fun carries(f: Factoid, sources: Set<String>) =
            f.tags.split(',').any { TagNames.stored(it) in sources }

        /**
         * Each factoid tag list carrying one of [sources] with it replaced where it stood by
         * [replacement] (for that factoid's selector), without repeats, the rest kept as stored:
         * `FactoidTagUsages.retag`. Returns how many changed.
         */
        private fun retagFactoids(
            sources: Set<String>,
            replacement: (Factoid) -> List<String>,
        ): Int {
            var changed = 0
            for (f in factoids) {
                val stored = f.tags.split(',').map { it.trim() }
                if (stored.none { TagNames.stored(it) in sources }) continue
                val rewritten =
                    stored
                        .flatMap {
                            if (TagNames.stored(it) in sources) replacement(f) else listOf(it)
                        }
                        .filter { it.isNotEmpty() }
                        .distinctBy { TagNames.stored(it) }
                        .joinToString(",")
                f.tags = rewritten
                update(
                    "UPDATE factoid_attributes SET attribute_value = ?, updated_by = ?, updated_at = ? WHERE id = ?",
                    rewritten,
                    ACTOR,
                    now(),
                    f.attributeId,
                )
                changed++
                if (++factoidWrites % PROGRESS_EVERY == 0) {
                    log.info("V72: {} factoid tag lists rewritten so far", factoidWrites)
                }
            }
            totalFactoids += changed
            return changed
        }

        /** The old names' places go to [kept] when it has none, and are dropped otherwise. */
        private fun movePlace(sources: Set<String>, kept: String): String? {
            val old = places(sources)
            if (old.isEmpty()) return null
            return if (places(setOf(kept)).isEmpty()) {
                totalPlaces +=
                    update("UPDATE atlas_place SET tag = ? WHERE tag = ?", kept, old.first())
                old.drop(1).forEach {
                    totalPlaces += update("DELETE FROM atlas_place WHERE tag = ?", it)
                }
                "place moved"
            } else {
                old.forEach { totalPlaces += update("DELETE FROM atlas_place WHERE tag = ?", it) }
                "place dropped (kept has one)"
            }
        }

        private fun deletePlaces(sources: Set<String>): Int =
            places(sources)
                .sumOf { update("DELETE FROM atlas_place WHERE tag = ?", it) }
                .also { totalPlaces += it }

        /** The Atlas place keys, as stored, that read as one of [names]. */
        private fun places(names: Set<String>): List<String> =
            if (!hasAtlas) emptyList()
            else
                query(
                    "SELECT tag FROM atlas_place WHERE LOWER(TRIM(tag)) = ANY(?) ORDER BY tag",
                    names,
                ) {
                    it.getString(1)
                }

        /** The `tags` rows read (as `PostTagUsages` reads them) as one of [names]. */
        private fun tagIds(names: Set<String>): List<UUID> =
            query("SELECT id FROM tags WHERE LOWER(TRIM(name)) = ANY(?) ORDER BY name", names) {
                it.getObject(1, UUID::class.java)
            }

        private fun tagId(name: String): UUID? = tagIds(setOf(name)).firstOrNull()

        /** A new `tags` row for [name], its slug unique as `TagVocabulary.uniqueSlug` makes it. */
        private fun create(name: String): UUID {
            val base = TagVocabulary.slugify(name).ifEmpty { "tag" }
            var slug = base
            var suffix = 2
            while (query("SELECT 1 FROM tags WHERE slug = ?", slug) { 1 }.isNotEmpty()) {
                slug = "$base-${suffix++}"
            }
            val id = UUID.randomUUID()
            update(
                "INSERT INTO tags (id, name, slug, deleted) VALUES (?, ?, ?, FALSE)",
                id,
                name,
                slug,
            )
            return id
        }

        private fun postsCarrying(tagId: UUID): Int =
            query("SELECT COUNT(DISTINCT post_id) FROM post_tags WHERE tag_id = ?", tagId) {
                    it.getInt(1)
                }
                .single()

        private fun closeReview(names: Set<String>, status: String) {
            update(
                """
                UPDATE tag_review SET status = ?, acted_by = ?, acted_at = ?
                WHERE tag = ANY(?) AND status = 'OPEN'
                """,
                status,
                ACTOR,
                now(),
                names,
            )
        }

        private fun record(action: String, subject: String, detail: String?) {
            update(
                """
                INSERT INTO tag_action (id, action, subject, detail, actor, acted_at)
                VALUES (?, ?, ?, ?, ?, clock_timestamp())
                """,
                UUID.randomUUID(),
                action,
                subject,
                detail,
                ACTOR,
            )
        }

        /**
         * The factoid tag lists that contain a listed name anywhere: a superset, narrowed element
         * by element in code ([carries]).
         */
        private fun loadFactoids(): List<Factoid> =
            query(
                """
                SELECT fa.id, f.selector, fa.attribute_value
                FROM factoid_attributes fa JOIN factoids f ON f.id = fa.factoid_id
                WHERE fa.attribute_type = 'TAGS'
                  AND LOWER(fa.attribute_value) LIKE ANY(?)
                ORDER BY f.selector
                """,
                listedNames()
                    .map {
                        "%" + it.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
                    }
                    .toSet(),
            ) {
                Factoid(it.getObject(1, UUID::class.java), it.getString(2), it.getString(3))
            }

        private fun exists(table: String): Boolean =
            query("SELECT to_regclass(?) IS NOT NULL", table) { it.getBoolean(1) }.single()

        private fun now() = Timestamp.from(Instant.now())

        private fun bind(statement: PreparedStatement, params: Array<out Any?>) {
            params.forEachIndexed { i, p ->
                when (p) {
                    is Set<*> ->
                        statement.setArray(
                            i + 1,
                            c.createArrayOf("text", p.map { it.toString() }.toTypedArray()),
                        )
                    else -> statement.setObject(i + 1, p)
                }
            }
        }

        private fun update(sql: String, vararg params: Any?): Int =
            c.prepareStatement(sql.trimIndent()).use {
                bind(it, params)
                it.executeUpdate()
            }

        private fun <T> query(
            sql: String,
            vararg params: Any?,
            row: (java.sql.ResultSet) -> T,
        ): List<T> =
            c.prepareStatement(sql.trimIndent()).use {
                bind(it, params)
                it.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) add(row(rs))
                    }
                }
            }
    }
}
