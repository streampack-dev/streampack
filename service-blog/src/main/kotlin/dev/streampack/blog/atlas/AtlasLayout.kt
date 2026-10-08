/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.atlas

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * How tags go together: two tags are tied when the same posts or factoids carry both, weighted by
 * cosine (`count(a,b) / sqrt(count(a)·count(b))`), so ubiquitous tags (`ai`, `java`) don't pull
 * everything into one knot. [items] are each post's and factoid's tags.
 */
class Ties(items: List<Collection<String>>) {
    /** How many items carry each tag. */
    val count: Map<String, Int>
    private val pairs: Map<String, Map<String, Int>>

    init {
        val count = HashMap<String, Int>()
        val pairs = HashMap<String, HashMap<String, Int>>()
        for (item in items) {
            val tags = item.filter { it.isNotBlank() }.distinct()
            tags.forEach { count.merge(it, 1, Int::plus) }
            for (a in tags) for (b in tags) {
                if (a != b) pairs.getOrPut(a) { HashMap() }.merge(b, 1, Int::plus)
            }
        }
        this.count = count
        this.pairs = pairs
    }

    fun together(a: String, b: String): Int = pairs[a]?.get(b) ?: 0

    fun weight(a: String, b: String): Double {
        val ab = together(a, b)
        if (ab == 0) return 0.0
        return ab / sqrt(count.getValue(a).toDouble() * count.getValue(b))
    }

    /** [tag]'s ties, by weight. */
    fun neighbours(tag: String): Map<String, Double> =
        pairs[tag].orEmpty().keys.associateWith { weight(tag, it) }
}

data class LaidRegion(val id: Int, val name: String, val x: Double, val y: Double)

data class LaidPlace(val tag: String, val region: Int, val x: Double, val y: Double)

data class Layout(val regions: List<LaidRegion>, val places: List<LaidPlace>)

/**
 * The Atlas's geography (ui-pudl#184, moved here from ui-pudl), the prototype's two-level layout: a
 * plain force layout of every tag is a hairball, so tags are first grouped into regions (weighted
 * label propagation, tiny groups folded into the region they're most tied to), the regions are
 * placed by how strongly they're tied, and then each region's tags are placed around its own
 * centre.
 *
 * Everything here is deterministic: the same tags and ties always give the same map, whatever order
 * they arrive in, so a relayout of unchanged data changes nothing. Positions are world units; a
 * place is about [SPACING] from its neighbours.
 */
object AtlasLayout {
    /** The region of places tied to nothing: tags only ever used alone. */
    const val UNCHARTED = 0
    const val UNCHARTED_NAME = "Uncharted"

    /** Groups smaller than this are folded into the region they're most tied to. */
    const val MIN_REGION = 3
    const val SPACING = 34.0
    /** How close a newcomer may be put to a place already there. */
    const val ROOM = 26.0
    private const val GOLDEN = 2.399963229728653 // the golden angle, in radians
    private const val REGION_GAP = 40.0
    /** Room between a region's outermost place and its edge. */
    private const val REGION_PAD = 26.0

    /** A region's radius for [n] places: room for them on a Vogel spiral, and a margin. */
    fun radius(n: Int): Double = 30.0 + SPACING * sqrt(n.toDouble())

    /** The map for [tags], tied as [ties] says. */
    fun layout(ties: Ties, tags: Collection<String>): Layout {
        val order = ordered(ties, tags.toSet())
        if (order.isEmpty()) return Layout(emptyList(), emptyList())
        val (groups, loners) = regionsOf(ties, order)
        // Each region's tags around its own centre first, so the regions are placed by the room
        // they really take.
        val locals = groups.map { placeMembers(ties, it, 0.0, 0.0, radius(it.size)) }
        val reach = locals.map { l -> l.maxOf { (_, p) -> hypot(p.first, p.second) } + REGION_PAD }
        val centres = placeRegions(ties, groups, reach)
        val regions = mutableListOf<LaidRegion>()
        val places = mutableListOf<LaidPlace>()
        groups.forEachIndexed { i, members ->
            val id = i + 1
            val (cx, cy) = centres[i]
            regions += LaidRegion(id, name(members), cx, cy)
            places +=
                locals[i].map { (t, p) ->
                    LaidPlace(t, id, round(cx + p.first), round(cy + p.second))
                }
        }
        if (loners.isNotEmpty()) {
            val (ux, uy) = unchartedCentre(places, radius(loners.size))
            regions += LaidRegion(UNCHARTED, UNCHARTED_NAME, ux, uy)
            loners.forEachIndexed { i, t ->
                val (x, y) = spiral(i, ux, uy, 0.0)
                places += LaidPlace(t, UNCHARTED, x, y)
            }
        }
        return Layout(regions, places)
    }

    /**
     * Where a tag that arrived since the last layout goes, without moving anything already there:
     * beside its strongest relative, in that relative's region; with no relative, in the Uncharted
     * region (made, east of the map, if there isn't one). [placed] and [regions] are the map so
     * far; the answer is the place and, if one had to be made, the Uncharted region.
     */
    fun placeNewcomer(
        tag: String,
        ties: Ties,
        placed: List<LaidPlace>,
        regions: List<LaidRegion>,
    ): Pair<LaidPlace, LaidRegion?> {
        val byTag = placed.associateBy { it.tag }
        val anchor =
            ties
                .neighbours(tag)
                .filterKeys { it in byTag }
                .entries
                .sortedWith(
                    compareByDescending<Map.Entry<String, Double>> { it.value }.thenBy { it.key }
                )
                .firstOrNull()
                ?.let { byTag.getValue(it.key) }
        if (anchor != null) {
            val region = regions.firstOrNull { it.id == anchor.region }
            // Facing away from the region's centre first, so the region grows outward.
            val away =
                if (region == null || hypot(anchor.x - region.x, anchor.y - region.y) < 1.0) 0.0
                else kotlin.math.atan2(anchor.y - region.y, anchor.x - region.x)
            val (x, y) = freeSpot(anchor.x, anchor.y, away, placed)
            return LaidPlace(tag, anchor.region, x, y) to null
        }
        val existing = regions.firstOrNull { it.id == UNCHARTED }
        val uncharted =
            existing
                ?: run {
                    val (ux, uy) = unchartedCentre(placed, radius(1))
                    LaidRegion(UNCHARTED, UNCHARTED_NAME, ux, uy)
                }
        val (x, y) = freeSpot(uncharted.x, uncharted.y, 0.0, placed, startAtCentre = true)
        return LaidPlace(tag, UNCHARTED, x, y) to (if (existing == null) uncharted else null)
    }

    /** Biggest first, then by name: the order everything is visited in. */
    private fun ordered(ties: Ties, tags: Set<String>): List<String> =
        tags.sortedWith(compareByDescending<String> { ties.count[it] ?: 0 }.thenBy { it })

    /** A region's name: its two biggest tags. */
    fun name(members: List<String>): String = members.take(2).joinToString(" · ")

    /**
     * The regions, each its members biggest first, biggest region first; and the tags tied to
     * nothing.
     */
    private fun regionsOf(ties: Ties, order: List<String>): Pair<List<List<String>>, List<String>> {
        val index = order.withIndex().associate { it.value to it.index }
        val inMap = order.toSet()
        val nb = order.associateWith { t -> ties.neighbours(t).filterKeys { it in inMap } }
        val loners = order.filter { nb.getValue(it).isEmpty() }
        val label = HashMap<String, String>()
        order.forEach { label[it] = it }
        // Weighted label propagation, visiting in a fixed order and breaking ties toward the label
        // already held, then the biggest tag's label, so it always settles the same way.
        repeat(50) {
            var changed = false
            for (t in order) {
                val scores = HashMap<String, Double>()
                nb.getValue(t).forEach { (n, w) ->
                    scores.merge(label.getValue(n), w, Double::plus)
                }
                if (scores.isEmpty()) continue
                val best = scores.values.max()
                val current = label.getValue(t)
                val pick =
                    if ((scores[current] ?: -1.0) >= best - 1e-12) current
                    else
                        scores.filterValues { it >= best - 1e-12 }.keys.minBy { index.getValue(it) }
                if (pick != current) {
                    label[t] = pick
                    changed = true
                }
            }
            if (!changed) return@repeat
        }
        val groups =
            order
                .filter { nb.getValue(it).isNotEmpty() }
                .groupBy { label.getValue(it) }
                .values
                .map { it.toMutableList() }
                .toMutableList()
        // Fold tiny groups into the group they're most tied to, smallest first. One tied only
        // within itself stays a small region of its own.
        val settled = mutableListOf<List<String>>()
        while (true) {
            val small =
                groups
                    .filter { g -> g.size < MIN_REGION && settled.none { it === g } }
                    .minWithOrNull(
                        compareBy<List<String>> { it.size }.thenBy { index.getValue(it.first()) }
                    ) ?: break
            val target =
                groups
                    .filter { it !== small }
                    .map { g -> g to small.sumOf { a -> g.sumOf { b -> ties.weight(a, b) } } }
                    .filter { it.second > 0.0 }
                    .maxWithOrNull(
                        compareBy<Pair<MutableList<String>, Double>> { it.second }
                            .thenByDescending { index.getValue(it.first.first()) }
                    )
                    ?.first
            if (target == null) {
                settled += small
                continue
            }
            groups.removeIf { it === small }
            target.addAll(small)
        }
        val sorted =
            groups
                .map { g -> g.sortedBy { index.getValue(it) } }
                .sortedWith(
                    compareByDescending<List<String>> { it.size }
                        .thenBy { index.getValue(it.first()) }
                )
        return sorted to loners
    }

    /** Each region's centre: tied regions drawn together, none overlapping. */
    private fun placeRegions(
        ties: Ties,
        groups: List<List<String>>,
        radii: List<Double>,
    ): List<Pair<Double, Double>> {
        val n = groups.size
        val x = DoubleArray(n)
        val y = DoubleArray(n)
        for (i in 0 until n) {
            val d = 120.0 * sqrt(i.toDouble())
            x[i] = d * cos(i * GOLDEN)
            y[i] = d * sin(i * GOLDEN)
        }
        val tie = Array(n) { DoubleArray(n) }
        for (i in 0 until n) for (j in i + 1 until n) {
            val t = groups[i].sumOf { a -> groups[j].sumOf { b -> ties.weight(a, b) } }
            tie[i][j] = t
            tie[j][i] = t
        }
        val iterations = 400
        for (step in 0 until iterations) {
            val cool = 1.0 - step.toDouble() / iterations
            val fx = DoubleArray(n)
            val fy = DoubleArray(n)
            for (i in 0 until n) for (j in i + 1 until n) {
                var dx = x[j] - x[i]
                var dy = y[j] - y[i]
                var d = hypot(dx, dy)
                if (d < 1e-6) {
                    dx = 1.0
                    dy = 0.0
                    d = 1.0
                }
                val ux = dx / d
                val uy = dy / d
                val rest = radii[i] + radii[j] + REGION_GAP
                // Tied regions pull toward touching; every pair pushes apart when overlapping, and
                // gently at any distance, so untied regions spread out instead of stacking.
                var f = 0.0
                if (tie[i][j] > 0) f += 0.15 * tie[i][j] * (d - rest)
                if (d < rest) f -= 0.5 * (rest - d)
                f -= 300.0 / (d + 50.0)
                fx[i] += f * ux
                fy[i] += f * uy
                fx[j] -= f * ux
                fy[j] -= f * uy
            }
            for (i in 0 until n) {
                // A little gravity, so the map stays round and loose regions don't wander off.
                fx[i] -= 0.02 * x[i]
                fy[i] -= 0.02 * y[i]
                val len = hypot(fx[i], fy[i])
                val limit = 40.0 * cool + 1.0
                val scale = if (len > limit) limit / len else 1.0
                x[i] += fx[i] * scale
                y[i] += fy[i] * scale
            }
        }
        separate(x, y, radii, REGION_GAP / 2)
        return (0 until n).map { round(x[it]) to round(y[it]) }
    }

    /** Pushes overlapping circles apart until none overlap. */
    private fun separate(x: DoubleArray, y: DoubleArray, radii: List<Double>, gap: Double) {
        val n = x.size
        repeat(500) {
            var moved = false
            for (i in 0 until n) for (j in i + 1 until n) {
                var dx = x[j] - x[i]
                var dy = y[j] - y[i]
                var d = hypot(dx, dy)
                if (d < 1e-6) {
                    dx = 1.0
                    dy = 0.0
                    d = 1.0
                }
                val need = radii[i] + radii[j] + gap
                if (d < need) {
                    val push = (need - d) / 2 + 0.01
                    x[i] -= dx / d * push
                    y[i] -= dy / d * push
                    x[j] += dx / d * push
                    y[j] += dy / d * push
                    moved = true
                }
            }
            if (!moved) return
        }
    }

    /**
     * A region's tags around its centre: the biggest in the middle, the rest on a spiral, then
     * drawn toward the tags they're tied to and kept apart and inside the region.
     */
    private fun placeMembers(
        ties: Ties,
        members: List<String>,
        cx: Double,
        cy: Double,
        r: Double,
    ): List<Pair<String, Pair<Double, Double>>> {
        val n = members.size
        val x = DoubleArray(n)
        val y = DoubleArray(n)
        for (i in 0 until n) {
            val (px, py) = spiral(i, 0.0, 0.0, 0.0)
            x[i] = px
            y[i] = py
        }
        val w =
            Array(n) { i ->
                DoubleArray(n) { j -> if (i == j) 0.0 else ties.weight(members[i], members[j]) }
            }
        val mark =
            DoubleArray(n) { 1.25 * (4.0 + 2.6 * sqrt((ties.count[members[it]] ?: 1).toDouble())) }
        val inner = r - 24.0
        val iterations = 200
        for (step in 0 until iterations) {
            val cool = 1.0 - step.toDouble() / iterations
            val fx = DoubleArray(n)
            val fy = DoubleArray(n)
            for (i in 0 until n) for (j in i + 1 until n) {
                var dx = x[j] - x[i]
                var dy = y[j] - y[i]
                var d = hypot(dx, dy)
                if (d < 1e-6) {
                    dx = 1.0
                    dy = 0.0
                    d = 1.0
                }
                // Big places need more room than the spacing: their marks are bigger.
                val apart = max(SPACING, mark[i] + mark[j] + 14.0)
                var f = 0.0
                if (w[i][j] > 0) f += 0.08 * w[i][j] * (d - apart)
                if (d < apart) f -= 0.4 * (apart - d)
                fx[i] += f * dx / d
                fy[i] += f * dy / d
                fx[j] -= f * dx / d
                fy[j] -= f * dy / d
            }
            for (i in 0 until n) {
                // The biggest stays at the centre: it's what the region is named for.
                if (i == 0) continue
                val limit = 12.0 * cool + 0.5
                val len = hypot(fx[i], fy[i])
                val scale = if (len > limit) limit / len else 1.0
                x[i] += fx[i] * scale
                y[i] += fy[i] * scale
                val d = hypot(x[i], y[i])
                if (d > inner && d > 0) {
                    x[i] *= inner / d
                    y[i] *= inner / d
                }
            }
        }
        return members.mapIndexed { i, t -> t to (round(cx + x[i]) to round(cy + y[i])) }
    }

    /** The [i]th point of a Vogel spiral around (cx, cy): evenly filling a disc. */
    private fun spiral(i: Int, cx: Double, cy: Double, turn: Double): Pair<Double, Double> {
        val d = SPACING * sqrt(i.toDouble())
        return round(cx + d * cos(turn + i * GOLDEN)) to round(cy + d * sin(turn + i * GOLDEN))
    }

    /** East of everything placed, level with the middle: where the Uncharted region sits. */
    private fun unchartedCentre(placed: List<LaidPlace>, r: Double): Pair<Double, Double> {
        if (placed.isEmpty()) return 0.0 to 0.0
        val east = placed.maxOf { it.x }
        val mid = (placed.minOf { it.y } + placed.maxOf { it.y }) / 2
        return round(east + r + 2 * REGION_GAP) to round(mid)
    }

    /**
     * The first spot around (x, y), ring by ring, at least [ROOM] from every place: starting at
     * angle [from], stepping by the golden angle.
     */
    private fun freeSpot(
        x: Double,
        y: Double,
        from: Double,
        placed: List<LaidPlace>,
        startAtCentre: Boolean = false,
    ): Pair<Double, Double> {
        fun free(px: Double, py: Double) = placed.none { hypot(it.x - px, it.y - py) < ROOM }
        if (startAtCentre && free(x, y)) return round(x) to round(y)
        var ring = 1
        while (true) {
            val d = SPACING * ring * 0.8
            val tries = max(8, 6 * ring)
            for (k in 0 until tries) {
                val angle = from + (if (k % 2 == 0) 1 else -1) * ((k + 1) / 2) * (2 * PI / tries)
                val px = x + d * cos(angle)
                val py = y + d * sin(angle)
                if (free(px, py)) return round(px) to round(py)
            }
            ring++
        }
    }

    /** To a tenth of a unit: what's stored, so a stored map redraws exactly. */
    private fun round(v: Double): Double = Math.round(v * 10) / 10.0
}
