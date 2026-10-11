/* Joseph B. Ottinger (C)2026 */
package dev.streampack.specs.service

import dev.streampack.specs.config.CveProperties
import dev.streampack.specs.model.CveRecord
import dev.streampack.specs.model.CveRequest
import dev.streampack.specs.model.Ecosystem
import dev.streampack.specs.model.OsvVuln
import dev.streampack.specs.model.PackageRef
import dev.streampack.specs.model.Severity
import java.net.URLEncoder
import java.time.Instant
import org.springframework.stereotype.Service

/**
 * Answers a `cve` question in a chat line (a few, for an alias naming several packages).
 *
 * The term is tried, in order, as a CVE id, as package coordinates, as an alias, and as an
 * artifactId Maven Central has exactly one of: several get the candidates back, never a guess. Long
 * lists are cut with a count and OSV's page for the package. A package with nothing in OSV is "none
 * known to OSV", never "safe". An upstream that can't be asked gets one line saying so.
 */
@Service
class CveReporter(private val lookup: CveLookupService, private val properties: CveProperties) {

    /** A reply, and the factoid it may seed (selector to value); only a CVE id seeds one. */
    data class Answer(val text: String, val factoid: Pair<String, String>? = null)

    fun answer(request: CveRequest): Answer {
        val term = request.term.trim()
        ID.matchEntire(term)?.let {
            return guarded { byId("CVE-${it.groupValues[1]}-${it.groupValues[2]}") }
        }

        var coordinates = term
        var version = request.version
        if (version == null) {
            MAVEN_WITH_VERSION.matchEntire(term)?.let {
                coordinates = it.groupValues[1]
                version = it.groupValues[2]
            }
        }
        PackageRef.parse(coordinates)?.let { ref ->
            return guarded { Answer(packageLine(ref, version)) }
        }
        if (':' in term) {
            return Answer(
                "Ask by group:artifact, or with an ecosystem prefix: " +
                    Ecosystem.entries
                        .flatMap { it.prefixes.take(1) }
                        .joinToString(", ") { "$it:" } +
                    "."
            )
        }

        properties.effectiveAliases[term.lowercase()]?.let { aliased ->
            return Answer(aliasLines(aliased, version))
        }
        return guarded { byName(term, version) }
    }

    private fun guarded(answer: () -> Answer): Answer =
        try {
            answer()
        } catch (e: CveLookupFailed) {
            Answer(unavailable(e))
        }

    private fun unavailable(e: CveLookupFailed) =
        "Couldn't look that up right now: ${e.upstream} didn't answer."

    private fun byId(id: String): Answer {
        val record = lookup.record(id) ?: return Answer("CVE.org has no record of $id.")
        return when (record.state) {
            "REJECTED" ->
                Answer(
                    fit(
                        "$id was rejected: ",
                        record.description ?: "no reason given",
                        " ${record.url}",
                    )
                )
            "RESERVED" -> Answer("$id is reserved, and not yet published. ${record.url}")
            else -> Answer(publishedLine(record), "${id.lowercase()}.url" to record.url)
        }
    }

    private fun publishedLine(record: CveRecord): String {
        val rating =
            when {
                record.severity != null && record.score != null ->
                    " (${record.severity.label} ${record.score})"
                record.severity != null -> " (${record.severity.label})"
                else -> ""
            }
        val published = record.published?.let { " Published $it." } ?: ""
        return fit(
            "${record.id}$rating: ",
            record.description ?: "no description yet.",
            "$published ${record.url}",
        )
    }

    private fun byName(name: String, version: String?): Answer {
        val artifacts = lookup.mavenArtifacts(name)
        return when (artifacts.size) {
            0 ->
                Answer(
                    "No Maven artifact is named $name; ask by group:artifact, or with an ecosystem " +
                        "prefix such as npm:$name."
                )
            1 ->
                Answer(
                    packageLine(
                        PackageRef(Ecosystem.MAVEN, artifacts.single().coordinates),
                        version,
                    )
                )
            else -> {
                val shown = artifacts.take(LIST_LIMIT).joinToString(", ") { it.coordinates }
                val more = more(artifacts.size - LIST_LIMIT)
                Answer(
                    "$name is the artifactId of ${artifacts.size} Maven artifacts: $shown$more; " +
                        "ask again with group:artifact."
                )
            }
        }
    }

    private fun aliasLines(coordinates: List<String>, version: String?): String {
        val lines =
            coordinates.take(MAX_LINES).map { c ->
                val ref = PackageRef.parse(c) ?: return@map "$c: not coordinates I can look up."
                try {
                    packageLine(ref, version)
                } catch (e: CveLookupFailed) {
                    "${ref.display}: ${unavailable(e).replaceFirstChar { it.lowercase() }}"
                }
            }
        val dropped = coordinates.size - MAX_LINES
        return (lines + listOfNotNull(if (dropped > 0) "…and $dropped more packages." else null))
            .joinToString("\n")
    }

    /** One line about [ref]: what affects [version] when it's given, a summary when it isn't. */
    private fun packageLine(ref: PackageRef, version: String?): String {
        val vulns = lookup.vulnerabilities(ref, version)
        val label = if (version != null) "${ref.display} $version" else ref.display
        val link = osvLink(ref)
        if (vulns.isEmpty()) return "$label: none known to OSV. $link"

        val advisories = advisories(vulns).sortedWith(WORST_FIRST)
        val fix = Versions.lowestFixingAll(vulns, version, ref.ecosystem.scheme)
        val showFixes = fix is Versions.Fix.Unordered
        val fixText =
            when (fix) {
                is Versions.Fix.At -> "; all fixed in ${fix.version}"
                Versions.Fix.None -> "; no release fixes all of them yet"
                Versions.Fix.Unordered -> ""
            }
        val count = "$label: ${advisories.size} known to OSV"

        if (version == null && !showFixes) {
            val worst = advisories.first()
            val newest = advisories.maxBy { it.published ?: Instant.EPOCH }
            val detail =
                if (advisories.size == 1) ", ${worst.describe()}"
                else ", worst ${worst.describe()}, newest ${newest.describe()}"
            return "$count$detail$fixText. $link"
        }

        // Worst first, as many as fit.
        for (shown in minOf(LIST_LIMIT, advisories.size) downTo 1) {
            val listed = advisories.take(shown).joinToString(", ") { it.describe(showFixes) }
            val line = "$count, worst first: $listed${more(advisories.size - shown)}$fixText. $link"
            if (line.length <= MAX_LINE || shown == 1) return line
        }
        error("unreachable")
    }

    /**
     * OSV often has one vulnerability more than once (GitHub's advisory, the ecosystem's own and so
     * on, each naming the others as aliases): they're one advisory here, named by its CVE when it
     * has one.
     */
    private fun advisories(vulns: List<OsvVuln>): List<Advisory> {
        val parent = mutableMapOf<String, String>()
        fun root(id: String): String {
            var at = id
            while (parent[at] != null && parent[at] != at) at = parent.getValue(at)
            return at
        }
        for (vuln in vulns) {
            val ids = listOf(vuln.id) + vuln.aliases
            ids.forEach { parent.putIfAbsent(it, it) }
            ids.drop(1).forEach { parent[root(it)] = root(vuln.id) }
        }
        return vulns
            .groupBy { root(it.id) }
            .values
            .map { group ->
                val ids = group.flatMap { listOf(it.id) + it.aliases }.distinct()
                Advisory(
                    id =
                        ids.filter { ID.matches(it) }.minWithOrNull(CVE_ORDER)
                            ?: ids.firstOrNull { it.startsWith("GHSA-") }
                            ?: group.first().id,
                    severity = group.mapNotNull { it.severity }.maxOrNull(),
                    score = group.mapNotNull { it.score }.maxOrNull(),
                    published = group.mapNotNull { it.published }.minOrNull(),
                    fixes = group.flatMap { it.fixedVersions }.distinct(),
                )
            }
    }

    private data class Advisory(
        val id: String,
        val severity: Severity?,
        val score: Double?,
        val published: Instant?,
        val fixes: List<String>,
    ) {
        /** `CVE-2021-44228 (critical)`, with the versions fixing it when they're wanted. */
        fun describe(withFixes: Boolean = false): String {
            val fixNote =
                when {
                    !withFixes -> null
                    fixes.isEmpty() -> "no fix"
                    else -> "fixed ${fixes.joinToString("/")}"
                }
            val notes = listOfNotNull(severity?.label, fixNote)
            return if (notes.isEmpty()) id else "$id (${notes.joinToString(", ")})"
        }
    }

    private fun more(count: Int) = if (count > 0) ", …and $count more" else ""

    private fun osvLink(ref: PackageRef) =
        "https://osv.dev/list?ecosystem=${encode(ref.ecosystem.osvName)}&q=${encode(ref.name)}"

    private fun encode(text: String) = URLEncoder.encode(text, Charsets.UTF_8)

    /** [prefix], as much of [text] as fits, and [suffix], in one chat line. */
    private fun fit(prefix: String, text: String, suffix: String): String {
        val clean = text.replace(WHITESPACE, " ").trim()
        val room = MAX_LINE - prefix.length - suffix.length
        if (clean.length <= room) return prefix + clean + suffix
        val cut = clean.take(room - 1).substringBeforeLast(' ').trimEnd(',', ';', ':', '.', ' ')
        return "$prefix$cut…$suffix"
    }

    private companion object {
        /** IRC's limit is a little over this; replies stay within it. */
        private const val MAX_LINE = 400
        private const val MAX_LINES = 4
        private const val LIST_LIMIT = 5
        private val ID = Regex("(?:CVE-)?(\\d{4})-(\\d{4,})", RegexOption.IGNORE_CASE)
        private val MAVEN_WITH_VERSION =
            Regex("([A-Za-z0-9_.\\-]+:[A-Za-z0-9_.\\-]+):([A-Za-z0-9_.\\-]+)")
        private val WHITESPACE = Regex("\\s+")
        private val CVE_ORDER =
            compareBy<String>(
                { ID.matchEntire(it)!!.groupValues[1].toInt() },
                { ID.matchEntire(it)!!.groupValues[2].toLong() },
            )
        private val WORST_FIRST =
            compareByDescending<Advisory> { it.severity ?: Severity.NONE }
                .thenByDescending { it.score ?: 0.0 }
                .thenBy { it.published ?: Instant.MAX }
    }
}
