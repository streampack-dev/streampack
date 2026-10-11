/* Joseph B. Ottinger (C)2026 */
package dev.streampack.specs.service

import dev.streampack.specs.model.OsvRange
import dev.streampack.specs.model.OsvVuln
import dev.streampack.specs.model.VersionScheme
import java.math.BigInteger

/**
 * Orders versions the way an ecosystem does, and so finds whether a version is affected by a
 * vulnerability and the lowest version that fixes every one.
 *
 * Maven's order follows `ComparableVersion` (which isn't on the classpath) closely enough for fixed
 * versions: numbers numerically, `alpha` < `beta` < `milestone` < `rc` < `snapshot` < a release <
 * `sp`, other qualifiers after those, alphabetically. Semantic versions compare by their numbers
 * and then their pre-release, as npm, Go and Cargo do. Other ecosystems' rules aren't modeled, so
 * there only plain dotted numbers are compared, and anything else can't be.
 */
object Versions {

    /**
     * A comparator for [scheme] that can order every one of [versions], or null if one can't be
     * ordered with confidence.
     */
    fun comparator(scheme: VersionScheme, versions: Collection<String>): Comparator<String>? =
        when (scheme) {
            VersionScheme.MAVEN -> Comparator(::compareMaven)
            VersionScheme.SEMVER ->
                if (versions.all { it == ZERO || SEMVER.matches(it) }) Comparator(::compareSemver)
                else null
            VersionScheme.NUMERIC ->
                if (versions.all { NUMERIC.matches(it) }) Comparator(::compareNumeric) else null
        }

    /**
     * Whether [vuln] affects [version]: it's listed, or an `ECOSYSTEM` or `SEMVER` range takes it
     * in. Events are read in version order, as OSV's schema describes; `introduced: 0` is the start
     * of time. `GIT` ranges can't be read without the repository and are skipped.
     */
    fun affects(vuln: OsvVuln, version: String, order: Comparator<String>): Boolean =
        version in vuln.versions || vuln.ranges.any { inRange(it, version, order) }

    private fun inRange(range: OsvRange, version: String, order: Comparator<String>): Boolean {
        if (range.type != "ECOSYSTEM" && range.type != "SEMVER") return false
        val events =
            range.events.sortedWith { a, b ->
                when {
                    a.version == ZERO && b.version == ZERO -> 0
                    a.version == ZERO -> -1
                    b.version == ZERO -> 1
                    else -> order.compare(a.version, b.version)
                }
            }
        var affected = false
        for (event in events) {
            val atOrAfter = event.version == ZERO || order.compare(version, event.version) >= 0
            when (event.kind) {
                "introduced" -> if (atOrAfter) affected = true
                "fixed",
                "limit" -> if (atOrAfter) affected = false
                "last_affected" ->
                    if (event.version != ZERO && order.compare(version, event.version) > 0) {
                        affected = false
                    }
            }
        }
        return affected
    }

    /**
     * The lowest version that some range says is fixed, is above [from] (when given), and that no
     * one of [vulns] affects: [Fix.None] when there's no such version, and [Fix.Unordered] when the
     * versions can't be ordered under [scheme].
     */
    fun lowestFixingAll(vulns: List<OsvVuln>, from: String?, scheme: VersionScheme): Fix {
        val fixes = vulns.flatMap { it.fixedVersions }.distinct()
        val all =
            fixes + vulns.flatMap { v -> v.ranges.flatMap { r -> r.events.map { it.version } } }
        val order = comparator(scheme, all + listOfNotNull(from)) ?: return Fix.Unordered
        val candidate =
            fixes
                .filter { from == null || order.compare(it, from) > 0 }
                .sortedWith(order)
                .firstOrNull { fix -> vulns.none { affects(it, fix, order) } }
        return candidate?.let { Fix.At(it) } ?: Fix.None
    }

    /** What [lowestFixingAll] found. */
    sealed interface Fix {
        data class At(val version: String) : Fix

        /** No version fixes them all. */
        data object None : Fix

        /** The versions can't be ordered, so it can't be told. */
        data object Unordered : Fix
    }

    fun compareMaven(a: String, b: String): Int {
        val left = mavenItems(a)
        val right = mavenItems(b)
        for (i in 0 until maxOf(left.size, right.size)) {
            val result = compareItem(left.getOrNull(i), right.getOrNull(i))
            if (result != 0) return result
        }
        return 0
    }

    /** Numbers and qualifiers, with trailing zeros and release qualifiers dropped. */
    private fun mavenItems(version: String): List<Any> {
        val items =
            TOKEN.findAll(version.lowercase())
                .map { token ->
                    val text = token.value
                    if (text[0].isDigit()) BigInteger(text) else QUALIFIER_ALIASES[text] ?: text
                }
                .toMutableList<Any>()
        while (items.isNotEmpty() && (items.last() == BigInteger.ZERO || items.last() == "")) {
            items.removeAt(items.lastIndex)
        }
        return items
    }

    /** Numbers outrank qualifiers; a missing item is a zero or a release. */
    private fun compareItem(a: Any?, b: Any?): Int =
        when {
            a == null && b == null -> 0
            a == null -> -compareItem(b, null)
            a is BigInteger && b is BigInteger -> a.compareTo(b)
            a is BigInteger && b == null -> a.signum()
            a is BigInteger -> 1
            b is BigInteger -> -1
            else -> compareQualifier(a as String, (b as String?) ?: "")
        }

    private fun compareQualifier(a: String, b: String): Int {
        val ranks = qualifierRank(a).compareTo(qualifierRank(b))
        return if (ranks != 0 || qualifierRank(a) != UNKNOWN_QUALIFIER) ranks else a.compareTo(b)
    }

    private fun qualifierRank(qualifier: String): Int =
        QUALIFIERS.indexOf(qualifier).let { if (it < 0) UNKNOWN_QUALIFIER else it }

    fun compareSemver(a: String, b: String): Int {
        val (coreA, preA) = semverParts(a)
        val (coreB, preB) = semverParts(b)
        for (i in 0 until maxOf(coreA.size, coreB.size)) {
            val result =
                (coreA.getOrNull(i) ?: BigInteger.ZERO).compareTo(
                    coreB.getOrNull(i) ?: BigInteger.ZERO
                )
            if (result != 0) return result
        }
        if (preA == null || preB == null) {
            return if (preA == preB) 0 else if (preA == null) 1 else -1
        }
        val idsA = preA.split('.')
        val idsB = preB.split('.')
        for (i in 0 until minOf(idsA.size, idsB.size)) {
            val x = idsA[i]
            val y = idsB[i]
            val result =
                when {
                    x.all(Char::isDigit) && y.all(Char::isDigit) ->
                        BigInteger(x).compareTo(BigInteger(y))
                    x.all(Char::isDigit) -> -1
                    y.all(Char::isDigit) -> 1
                    else -> x.compareTo(y)
                }
            if (result != 0) return result
        }
        return idsA.size.compareTo(idsB.size)
    }

    private fun semverParts(version: String): Pair<List<BigInteger>, String?> {
        val bare = version.removePrefix("v").substringBefore('+')
        val core = bare.substringBefore('-')
        val pre = if ('-' in bare) bare.substringAfter('-') else null
        return core.split('.').map { BigInteger(it) } to pre
    }

    fun compareNumeric(a: String, b: String): Int = compareSemver(a, b)

    private const val ZERO = "0"
    private const val UNKNOWN_QUALIFIER = 99
    private val TOKEN = Regex("\\d+|[a-z]+")
    private val SEMVER = Regex("v?\\d+(\\.\\d+)*(-[0-9A-Za-z.-]+)?(\\+[0-9A-Za-z.-]+)?")
    private val NUMERIC = Regex("\\d+(\\.\\d+)*")
    private val QUALIFIERS = listOf("alpha", "beta", "milestone", "rc", "snapshot", "", "sp")
    private val QUALIFIER_ALIASES =
        mapOf(
            "a" to "alpha",
            "b" to "beta",
            "m" to "milestone",
            "cr" to "rc",
            "ga" to "",
            "final" to "",
            "release" to "",
        )
}
