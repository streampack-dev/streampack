/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.atlas

import dev.streampack.blog.repository.ContentValidators
import dev.streampack.blog.repository.ContentValidators.Validator
import dev.streampack.blog.repository.PostTagRepository
import dev.streampack.core.integration.EventGateway
import dev.streampack.core.model.OperationResult
import dev.streampack.core.model.Protocol
import dev.streampack.core.model.Provenance
import dev.streampack.factoid.model.FactoidTagging
import dev.streampack.factoid.model.FindFactoidTaggingRequest
import dev.streampack.taxonomy.model.FindTaxonomySnapshotRequest
import dev.streampack.taxonomy.model.TaxonomySnapshot
import java.time.Instant
import kotlin.math.hypot
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.messaging.support.MessageBuilder
import org.springframework.stereotype.Component

/**
 * The Atlas (ui-pudl#184), moved here from ui-pudl so every front end draws the same map: the
 * stored geography ([AtlasStore]) with today's counts from the taxonomy, and each place's pins.
 *
 * The layout is computed on the first request that finds nothing stored, and stored. After that a
 * tag that arrived since is placed beside its strongest relative and stored, so nothing already
 * placed moves; a tag no longer used is left off the map and keeps its stored place. Only an
 * admin's [relayout] lays the whole map out again.
 *
 * The answer is kept in memory, keyed by [validator]: the taxonomy's validator (posts, their tags,
 * factoid tags), factoid and slug changes, and what's stored. A request whose data hasn't changed
 * is served from memory after those few cheap queries; any change to posts, factoids or the map
 * builds it afresh.
 */
@Component
class Atlas(
    private val store: AtlasStore,
    private val postTags: PostTagRepository,
    private val eventGateway: EventGateway,
    private val validators: ContentValidators,
    private val jdbc: JdbcTemplate,
) {
    private val log = LoggerFactory.getLogger(Atlas::class.java)
    private val lock = Any()

    private class Built(
        val fingerprint: String,
        val lastModified: Instant?,
        val map: AtlasResponse,
        val catalog: Catalog,
    )

    @Volatile private var built: Built? = null

    /**
     * The map's validator, for conditional GETs: taken once any newcomers are placed, so a reader's
     * ETag holds until the posts, factoids or stored map next change.
     */
    fun validator(now: Instant): Validator =
        current(now).let { Validator(it.fingerprint, it.lastModified) }

    /** What the map depends on, cheaply: the key of the in-memory copy. */
    private fun fingerprint(now: Instant): Validator {
        val taxonomy = validators.taxonomy(now)
        return Validator(
            listOf(taxonomy.fingerprint, store.fingerprint(), itemsFingerprint()).joinToString("|"),
            taxonomy.lastModified,
        )
    }

    /** The map: from memory when nothing it depends on has changed. */
    fun map(now: Instant = Instant.now()): AtlasResponse = current(now).map

    /** One place with everything found there; null if [tag] isn't on the map. */
    fun place(tag: String, now: Instant = Instant.now()): AtlasPlaceDetailResponse? {
        val held = current(now)
        val name = tag.trim().lowercase()
        val place = held.map.places.firstOrNull { it.tag == name } ?: return null
        val region = held.map.regions.first { it.id == place.region }
        return AtlasPlaceDetailResponse(
            place = place,
            regionName = region.name,
            articles = held.catalog.articlesAt(name),
            factoids = held.catalog.factoidsAt(name),
        )
    }

    /** Lays the whole map out again from every post and factoid: an admin's request. */
    fun relayout(now: Instant = Instant.now()): AtlasResponse {
        synchronized(lock) {
            val catalog = readCatalog(now)
            val counts = counts()
            store.replace(
                AtlasLayout.layout(catalog.ties(), catalog.tags() + counts.live),
                now,
            )
            built = null
            log.info("Atlas: laid out again")
        }
        return map(now)
    }

    private fun current(now: Instant): Built {
        val fingerprint = fingerprint(now).fingerprint
        built
            ?.takeIf { it.fingerprint == fingerprint }
            ?.let {
                return it
            }
        synchronized(lock) {
            built
                ?.takeIf { it.fingerprint == fingerprint(now).fingerprint }
                ?.let {
                    return it
                }
            val catalog = readCatalog(now)
            val counts = counts()
            val stored = ensurePlaced(counts.live, catalog, now)
            val map = assemble(stored, counts, catalog)
            // Placing newcomers changed what's stored, so the key is taken after.
            val after = fingerprint(now)
            return Built(after.fingerprint, after.lastModified, map, catalog).also { built = it }
        }
    }

    /**
     * The stored map with every tag in [live] placed: laid out from nothing if nothing is stored,
     * otherwise with the newcomers placed beside their strongest relatives.
     */
    private fun ensurePlaced(live: Set<String>, catalog: Catalog, now: Instant): StoredMap {
        val stored = store.load()
        val known = stored.places.map { it.tag }.toSet()
        val newcomers = live.filter { it !in known }
        if (newcomers.isEmpty()) return stored
        if (stored.isEmpty) {
            store.replace(AtlasLayout.layout(catalog.ties(), catalog.tags() + live), now)
            log.info("Atlas: laid out {} places", live.size)
        } else {
            placeNewcomers(newcomers, catalog.ties(), stored, now)
        }
        return store.load()
    }

    private fun placeNewcomers(newcomers: List<String>, ties: Ties, map: StoredMap, now: Instant) {
        val placed = map.places.toMutableList()
        val regions = map.regions.toMutableList()
        val added = mutableListOf<LaidPlace>()
        val made = mutableListOf<LaidRegion>()
        newcomers
            .sortedWith(compareByDescending<String> { ties.count[it] ?: 0 }.thenBy { it })
            .forEach { tag ->
                val (place, region) = AtlasLayout.placeNewcomer(tag, ties, placed, regions)
                placed += place
                added += place
                region?.let {
                    regions += it
                    made += it
                }
            }
        store.add(added, made, now)
        log.info("Atlas: placed {} new tags", added.size)
    }

    private fun assemble(stored: StoredMap, counts: Counts, catalog: Catalog): AtlasResponse {
        val byRegion = stored.places.filter { it.tag in counts.live }.groupBy { it.region }
        val regions =
            stored.regions.mapNotNull { region ->
                val members =
                    byRegion[region.id]
                        ?.map { place(it, counts, catalog) }
                        ?.sortedWith(
                            compareByDescending<AtlasPlaceResponse> {
                                    it.articleCount + it.factoidCount
                                }
                                .thenBy { it.tag }
                        ) ?: return@mapNotNull null
                val reach =
                    members.maxOf { hypot(it.x - region.x, it.y - region.y) + it.r } + REGION_PAD
                AtlasRegionResponse(
                    region.id,
                    region.name,
                    region.x,
                    region.y,
                    round1(reach),
                    region.id == AtlasLayout.UNCHARTED,
                ) to members
            }
        // Biggest first, as a list shows them; the Uncharted region last.
        val ordered =
            regions.sortedWith(
                compareBy<Pair<AtlasRegionResponse, List<AtlasPlaceResponse>>> {
                        it.first.uncharted
                    }
                    .thenByDescending { it.second.size }
                    .thenBy { it.first.name }
            )
        return AtlasResponse(
            regions = ordered.map { it.first },
            places = ordered.flatMap { it.second },
            bounds = bounds(ordered.map { it.first }),
            laidOutAt = stored.laidOutAt,
        )
    }

    private fun place(laid: LaidPlace, counts: Counts, catalog: Catalog): AtlasPlaceResponse {
        val articles = counts.articles[laid.tag] ?: 0
        val factoids = counts.factoids[laid.tag] ?: 0
        val size = articles + factoids
        val kind = PlaceKind.of(articles, factoids)
        val r = PlaceShape.r(size)
        return AtlasPlaceResponse(
            tag = laid.tag,
            region = laid.region,
            x = laid.x,
            y = laid.y,
            articleCount = articles,
            factoidCount = factoids,
            kind = kind,
            r = r,
            labelSize = PlaceShape.labelSize(size),
            minor = PlaceShape.minor(size),
            pins = pins(laid, PlaceShape.edge(kind, r), catalog),
        )
    }

    /** The newest articles and the factoids, by name, around a place: at most 8 of each. */
    private fun pins(laid: LaidPlace, edge: Double, catalog: Catalog): List<AtlasPin> {
        val posts = catalog.articlesAt(laid.tag).take(PinOrbit.MOST)
        val factoids = catalog.factoidsAt(laid.tag).take(PinOrbit.MOST)
        val spots = PinOrbit.orbit(laid.x, laid.y, edge, posts.size + factoids.size)
        return posts.mapIndexed { i, p ->
            AtlasPin(
                kind = PinKind.ARTICLE,
                title = p.title,
                slug = p.slug,
                x = spots[i].first,
                y = spots[i].second,
                publishedAt = p.publishedAt,
            )
        } +
            factoids.mapIndexed { i, f ->
                val (x, y) = spots[posts.size + i]
                AtlasPin(
                    kind = PinKind.FACTOID,
                    title = f.selector,
                    selector = f.selector,
                    x = x,
                    y = y,
                    createdAt = f.createdAt,
                    updatedAt = f.updatedAt,
                )
            }
    }

    /** The world's bounds, with a margin and room above each region for its name. */
    private fun bounds(regions: List<AtlasRegionResponse>): AtlasBounds {
        if (regions.isEmpty()) return AtlasBounds(-500.0, -400.0, 1000.0, 800.0)
        val x0 = regions.minOf { it.x - it.r } - MARGIN
        val x1 = regions.maxOf { it.x + it.r } + MARGIN
        val y0 = regions.minOf { round1(it.y - it.r - 14) - 30 } - MARGIN
        val y1 = regions.maxOf { it.y + it.r } + MARGIN
        return AtlasBounds(round1(x0), round1(y0), round1(x1 - x0), round1(y1 - y0))
    }

    /** Today's counts, from the taxonomy: what /taxonomy says, so the two always agree. */
    private class Counts(val articles: Map<String, Long>, val factoids: Map<String, Long>) {
        val live: Set<String> = (articles.keys + factoids.keys).filter { it.isNotBlank() }.toSet()
    }

    private fun counts(): Counts {
        val snapshot =
            (eventGateway.process(message(FindTaxonomySnapshotRequest)) as? OperationResult.Success)
                ?.payload as? TaxonomySnapshot
        return Counts(
            snapshot?.tags.orEmpty().filterValues { it > 0 },
            snapshot?.factoidTags.orEmpty().filterValues { it > 0 },
        )
    }

    /** Every published post's tags (the posts /taxonomy counts) and every tagged factoid's. */
    private fun readCatalog(now: Instant): Catalog {
        val rows = postTags.findPublishedTagging(now)
        val articles =
            rows
                .groupBy { it.postId }
                .map { (_, tagged) ->
                    val first = tagged.first()
                    AtlasArticle(
                        title = first.title,
                        slug = first.slug,
                        publishedAt = first.publishedAt,
                        tags = tagged.map { it.tag }.distinct().sorted(),
                    )
                }
        // Factoids are an optional module: without it, the map is drawn from posts alone.
        val factoids =
            ((eventGateway.process(message(FindFactoidTaggingRequest)) as? OperationResult.Success)
                    ?.payload as? FactoidTagging)
                ?.entries
                .orEmpty()
                .map { AtlasFactoid(it.selector, it.createdAt, it.updatedAt, it.tags) }
        return Catalog(articles, factoids)
    }

    private fun message(payload: Any) =
        MessageBuilder.withPayload(payload)
            .setHeader(
                Provenance.HEADER,
                Provenance(protocol = Protocol.HTTP, serviceId = "blog-service", replyTo = "atlas"),
            )
            .build()

    /**
     * What pins show and the validator's taxonomy part doesn't cover: factoids' times (pins carry
     * them) and canonical slugs (pins link by them).
     */
    private fun itemsFingerprint(): String {
        val slugs =
            jdbc.queryForObject(
                "SELECT count(*) || ':' || coalesce(sum(hashtext(path || canonical::text)), 0) FROM slugs",
                String::class.java,
            )
        val factoids =
            if (factoidTablesPresent) {
                jdbc.queryForObject(
                    "SELECT count(*) || ':' || coalesce(max(updated_at)::text, '') FROM factoids",
                    String::class.java,
                )
            } else ""
        return "$slugs|$factoids"
    }

    private val factoidTablesPresent: Boolean by lazy {
        jdbc.queryForObject(
            "SELECT to_regclass('public.factoids') IS NOT NULL",
            Boolean::class.java,
        ) == true
    }

    private companion object {
        /** Room between a region's outermost place and its edge. */
        const val REGION_PAD = 22.0
        const val MARGIN = 40.0
    }
}

/** Everything the Atlas ties tags by and pins: every published post and every tagged factoid. */
internal class Catalog(val articles: List<AtlasArticle>, val factoids: List<AtlasFactoid>) {
    private val articlesByTag: Map<String, List<AtlasArticle>> by lazy {
        val out = HashMap<String, MutableList<AtlasArticle>>()
        articles.forEach { a -> a.tags.forEach { out.getOrPut(it) { mutableListOf() } += a } }
        out.mapValues { (_, list) ->
            list.sortedWith(
                compareByDescending<AtlasArticle> { it.publishedAt }.thenBy { it.title }
            )
        }
    }

    private val factoidsByTag: Map<String, List<AtlasFactoid>> by lazy {
        val out = HashMap<String, MutableList<AtlasFactoid>>()
        factoids.forEach { f -> f.tags.forEach { out.getOrPut(it) { mutableListOf() } += f } }
        out.mapValues { (_, list) -> list.sortedBy { it.selector.lowercase() } }
    }

    /** The articles carrying [tag], newest first. */
    fun articlesAt(tag: String): List<AtlasArticle> = articlesByTag[tag].orEmpty()

    /** The factoids carrying [tag], by selector. */
    fun factoidsAt(tag: String): List<AtlasFactoid> = factoidsByTag[tag].orEmpty()

    /** Each item's tags: what the layout's ties are counted over. */
    fun ties(): Ties = Ties(articles.map { it.tags } + factoids.map { it.tags })

    /** Every tag carried by something. */
    fun tags(): Set<String> = (articles.flatMap { it.tags } + factoids.flatMap { it.tags }).toSet()
}
