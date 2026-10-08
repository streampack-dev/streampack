/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.atlas

import kotlin.math.hypot
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The Atlas's layout (ui-pudl#184): deterministic, grouped into regions by ties, and stable, so a
 * newcomer never moves what's already placed.
 */
class AtlasLayoutTest {
    /** Three neighbourhoods, a pair tied only to each other, and a tag only ever used alone. */
    private val items: List<List<String>> =
        List(4) { listOf("java", "spring", "jvm") } +
            List(3) { listOf("kotlin", "jvm") } +
            listOf(listOf("java", "kotlin")) +
            List(4) { listOf("security", "auth", "oauth2") } +
            List(2) { listOf("auth", "tls") } +
            List(3) { listOf("javascript", "bun", "node") } +
            listOf(listOf("javascript", "typescript")) +
            List(2) { listOf("zig", "c") } +
            listOf(listOf("6502"))

    private val tags = items.flatten().toSet()

    private fun layout(items: List<List<String>> = this.items, tags: Set<String> = this.tags) =
        AtlasLayout.layout(Ties(items), tags)

    private fun Layout.at(tag: String) = places.first { it.tag == tag }

    private fun Layout.regionOf(tag: String) = regions.first { it.id == at(tag).region }

    @Test
    fun `the same tags and ties always give the same map, whatever order they come in`() {
        val first = layout()
        val again = layout()
        val shuffled = layout(items.reversed().map { it.reversed() }, tags.reversed().toSet())

        assertThat(again).isEqualTo(first)
        assertThat(shuffled.places.sortedBy { it.tag }).isEqualTo(first.places.sortedBy { it.tag })
        assertThat(shuffled.regions).isEqualTo(first.regions)
    }

    @Test
    fun `tags carried together share a region, named for its two biggest tags`() {
        val map = layout()

        assertThat(map.regionOf("spring")).isEqualTo(map.regionOf("java"))
        assertThat(map.regionOf("oauth2")).isEqualTo(map.regionOf("security"))
        assertThat(map.regionOf("bun")).isEqualTo(map.regionOf("javascript"))
        assertThat(map.regionOf("java")).isNotEqualTo(map.regionOf("security"))
        assertThat(map.regionOf("security").name).isEqualTo("auth · oauth2")
        assertThat(map.regionOf("javascript").name).isEqualTo("javascript · bun")
    }

    @Test
    fun `a tiny group is folded into the region it's most tied to, and tags tied to nothing are Uncharted`() {
        val map = layout()

        // tls is tied only to auth: too few for a region of its own.
        assertThat(map.regionOf("tls")).isEqualTo(map.regionOf("auth"))
        // zig and c are tied only to each other: a small region is still theirs.
        assertThat(map.regionOf("zig")).isEqualTo(map.regionOf("c"))
        assertThat(map.regionOf("6502").id).isEqualTo(AtlasLayout.UNCHARTED)
        assertThat(map.regionOf("6502").name).isEqualTo(AtlasLayout.UNCHARTED_NAME)
        assertThat(map.places.map { it.tag }).containsExactlyInAnyOrderElementsOf(tags)
    }

    @Test
    fun `places are kept apart, and regions don't overlap`() {
        val map = layout()
        for (a in map.places) for (b in map.places) {
            if (a.tag < b.tag) {
                assertThat(hypot(a.x - b.x, a.y - b.y))
                    .describedAs("${a.tag}, ${b.tag}")
                    .isGreaterThan(10.0)
            }
        }
        val reach =
            map.regions.associate { r ->
                r.id to
                    map.places.filter { it.region == r.id }.maxOf { hypot(it.x - r.x, it.y - r.y) }
            }
        for (a in map.regions) for (b in map.regions) {
            if (a.id < b.id) {
                assertThat(hypot(a.x - b.x, a.y - b.y))
                    .describedAs("${a.name}, ${b.name}")
                    .isGreaterThan(reach.getValue(a.id) + reach.getValue(b.id))
            }
        }
    }

    @Test
    fun `a newcomer goes beside its strongest relative, in its region, and nothing else moves`() {
        val before = layout()
        val withNew = items + List(3) { listOf("spring", "spring boot") }
        val ties = Ties(withNew)

        val (place, made) =
            AtlasLayout.placeNewcomer("spring boot", ties, before.places, before.regions)

        assertThat(made).isNull()
        assertThat(place.region).isEqualTo(before.at("spring").region)
        val spring = before.at("spring")
        assertThat(hypot(place.x - spring.x, place.y - spring.y))
            .isLessThan(4 * AtlasLayout.SPACING)
        assertThat(before.places.minOf { hypot(it.x - place.x, it.y - place.y) })
            .isGreaterThanOrEqualTo(AtlasLayout.ROOM)
    }

    @Test
    fun `a newcomer tied to nothing placed goes to the Uncharted region, made if there's none`() {
        val tied = items.filter { "6502" !in it }
        val before = layout(tied, tied.flatten().toSet())
        assertThat(before.regions.map { it.id }).doesNotContain(AtlasLayout.UNCHARTED)

        val (place, made) =
            AtlasLayout.placeNewcomer(
                "cobol",
                Ties(tied + listOf(listOf("cobol"))),
                before.places,
                before.regions,
            )

        assertThat(place.region).isEqualTo(AtlasLayout.UNCHARTED)
        assertThat(made!!.name).isEqualTo(AtlasLayout.UNCHARTED_NAME)
        assertThat(made.x).isGreaterThan(before.places.maxOf { it.x })
    }

    @Test
    fun `newcomers placed one after another each find room`() {
        val before = layout()
        val placed = before.places.toMutableList()
        val extra = (1..12).map { "spring-$it" }
        val ties = Ties(items + extra.map { listOf("spring", it) })
        extra.forEach {
            val (p, _) = AtlasLayout.placeNewcomer(it, ties, placed, before.regions)
            assertThat(placed.minOf { q -> hypot(q.x - p.x, q.y - p.y) })
                .isGreaterThanOrEqualTo(AtlasLayout.ROOM)
            placed += p
        }
        // The map as it was is untouched.
        assertThat(placed.take(before.places.size)).isEqualTo(before.places)
    }

    @Test
    fun `nothing to place is an empty map`() {
        assertThat(AtlasLayout.layout(Ties(emptyList()), emptySet()).places).isEmpty()
    }
}
