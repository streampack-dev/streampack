/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.atlas

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonValue
import io.swagger.v3.oas.annotations.media.Schema
import java.time.Instant
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * What lights a place: only articles, only factoids, or both (the shared core). ui-pudl draws them
 * as a disc, a ring and a diamond, so they're told apart by shape as well as colour.
 */
@Schema(description = "What is found at a place: articles only, factoids only, or both.")
enum class PlaceKind(@get:JsonValue val value: String) {
    ARTICLES("articles"),
    FACTOIDS("factoids"),
    SHARED("shared");

    companion object {
        fun of(articles: Long, factoids: Long): PlaceKind =
            when {
                articles > 0 && factoids > 0 -> SHARED
                factoids > 0 -> FACTOIDS
                else -> ARTICLES
            }
    }
}

/** What a pin is: an article (a published post) or a factoid. */
@Schema(description = "What a pin is: an article or a factoid.")
enum class PinKind(@get:JsonValue val value: String) {
    ARTICLE("article"),
    FACTOID("factoid"),
}

@Schema(
    description =
        "The Atlas: a map of the site's tags, grouped into regions by how they're used together."
)
data class AtlasResponse(
    @field:Schema(description = "The regions, biggest first, the Uncharted region (id 0) last.")
    val regions: List<AtlasRegionResponse>,
    @field:Schema(
        description =
            "Every tag in use, each with its region and position, in region order and then biggest first. " +
                "A stored tag no longer used is left out but keeps its position for when it's back."
    )
    val places: List<AtlasPlaceResponse>,
    @field:Schema(description = "The world the map spans, with a margin and room for region names.")
    val bounds: AtlasBounds,
    @field:Schema(
        description =
            "When the oldest place was laid out: the last full layout. Null for an empty map."
    )
    val laidOutAt: Instant? = null,
)

@Schema(description = "A rectangle in world units.")
data class AtlasBounds(val x: Double, val y: Double, val width: Double, val height: Double)

@Schema(description = "A region of the map: tags that go together.")
data class AtlasRegionResponse(
    @field:Schema(description = "Stable while the layout is kept; 0 is the Uncharted region.")
    val id: Int,
    @field:Schema(
        description =
            "The region's two biggest tags when laid out, joined by \" · \"; \"Uncharted\" for id 0."
    )
    val name: String,
    val x: Double,
    val y: Double,
    @field:Schema(
        description = "How far the region reaches: its outermost place's mark, and a margin."
    )
    val r: Double,
    @field:Schema(description = "True for the region of tags tied to nothing (id 0).")
    val uncharted: Boolean,
)

@Schema(description = "A place on the map: a tag, where it is, and what's found there.")
data class AtlasPlaceResponse(
    @field:Schema(description = "The tag, lowercased, as /taxonomy and /posts?tag= name it.")
    val tag: String,
    @field:Schema(description = "The id of the region it's in.") val region: Int,
    val x: Double,
    val y: Double,
    @field:Schema(description = "Published posts carrying the tag: taxonomy's tags count.")
    val articleCount: Long,
    @field:Schema(description = "Factoids carrying the tag: taxonomy's factoidTags count.")
    val factoidCount: Long,
    val kind: PlaceKind,
    @field:Schema(
        description = "The mark's radius, in world units: 4 + 2.6·√(articles + factoids)."
    )
    val r: Double,
    @field:Schema(description = "The label's font size, in pixels: 10 + √size, at most 20.")
    val labelSize: Double,
    @field:Schema(description = "A small place (size under 3): its name shows only when zoomed in.")
    val minor: Boolean,
    @field:Schema(
        description =
            "The newest articles and the factoids (by name) found here, at most 8 of each, each " +
                "placed around the mark. GET /atlas/places/{tag} lists them all."
    )
    val pins: List<AtlasPin>,
)

@Schema(description = "An article or factoid pinned around its place.")
@JsonInclude(JsonInclude.Include.NON_NULL)
data class AtlasPin(
    val kind: PinKind,
    @field:Schema(description = "An article's title, or a factoid's selector.") val title: String,
    @field:Schema(description = "An article's canonical slug path (GET /posts/{slug}).")
    val slug: String? = null,
    @field:Schema(description = "A factoid's selector (GET /factoids/{selector}).")
    val selector: String? = null,
    val x: Double,
    val y: Double,
    @field:Schema(description = "When an article was published.") val publishedAt: Instant? = null,
    @field:Schema(description = "When a factoid was first taught.") val createdAt: Instant? = null,
    @field:Schema(description = "When a factoid last changed.") val updatedAt: Instant? = null,
)

@Schema(description = "One place, with everything found there.")
data class AtlasPlaceDetailResponse(
    val place: AtlasPlaceResponse,
    @field:Schema(description = "The name of the place's region.") val regionName: String,
    @field:Schema(description = "Every published post carrying the tag, newest first.")
    val articles: List<AtlasArticle>,
    @field:Schema(description = "Every factoid carrying the tag, by selector.")
    val factoids: List<AtlasFactoid>,
)

@Schema(description = "A published post found at a place.")
data class AtlasArticle(
    val title: String,
    @field:Schema(description = "Its canonical slug path; null only for a post without one.")
    val slug: String? = null,
    val publishedAt: Instant,
    @field:Schema(description = "Every tag it carries: the places it's found at.")
    val tags: List<String>,
)

@Schema(description = "A factoid found at a place.")
data class AtlasFactoid(
    val selector: String,
    val createdAt: Instant,
    val updatedAt: Instant,
    @field:Schema(description = "Every tag it carries: the places it's found at.")
    val tags: List<String>,
)

/** A place's geometry, as ui-pudl drew it, so every front end draws marks the same size. */
internal object PlaceShape {
    fun r(size: Long): Double = round1(4.0 + 2.6 * sqrt(size.toDouble()))

    fun labelSize(size: Long): Double = round1(min(20.0, 10.0 + sqrt(size.toDouble())))

    fun minor(size: Long): Boolean = size < 3

    /** The edge pins orbit: a diamond reaches further than a disc. */
    fun edge(kind: PlaceKind, r: Double): Double = if (kind == PlaceKind.SHARED) r * 1.25 else r
}

/** Where pins go around a place, as ui-pudl drew them. */
internal object PinOrbit {
    /** The most of each kind pinned at a place; the rest are in GET /atlas/places/{tag}. */
    const val MOST = 8
    private const val STEP = 10.0

    /**
     * Where [n] pins go around a place at ([x], [y]) whose mark has radius [r]: rings outward from
     * its edge, starting at the top, each ring as full as its length allows.
     */
    fun orbit(x: Double, y: Double, r: Double, n: Int): List<Pair<Double, Double>> {
        val out = mutableListOf<Pair<Double, Double>>()
        var ring = 0
        while (out.size < n) {
            val radius = r + STEP + ring * STEP
            val room = max(6, (2 * Math.PI * radius / STEP).toInt())
            val k = min(room, n - out.size)
            for (j in 0 until k) {
                val a = -Math.PI / 2 + 2 * Math.PI * j / k + ring * 0.35
                out +=
                    round1(x + radius * kotlin.math.cos(a)) to
                        round1(y + radius * kotlin.math.sin(a))
            }
            ring++
        }
        return out
    }
}

internal fun round1(v: Double): Double = Math.round(v * 10) / 10.0
