/* Joseph B. Ottinger (C)2026 */
package dev.streampack.specs.service

import dev.streampack.core.fetch.FetchProperties
import dev.streampack.core.json.JacksonMappers
import dev.streampack.specs.config.CveProperties
import dev.streampack.specs.model.CveRecord
import dev.streampack.specs.model.MavenArtifact
import dev.streampack.specs.model.OsvEvent
import dev.streampack.specs.model.OsvRange
import dev.streampack.specs.model.OsvVuln
import dev.streampack.specs.model.PackageRef
import dev.streampack.specs.model.Severity
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.locks.ReentrantLock
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service
import tools.jackson.databind.JsonNode

/**
 * Reads CVE records from CVE.org, a package's vulnerabilities from OSV.dev and artifacts by name
 * from Maven Central, keeping each answer for as long as [CveProperties] says.
 *
 * Every call has a timeout. One that fails (unreachable, slow, rate-limited, an error status, a
 * body that can't be read) is logged once at WARN and thrown as [CveLookupFailed]; nothing is kept
 * for it. A CVE id CVE.org doesn't know isn't a failure: it's a `null` record, and is kept.
 *
 * Maven Central's search is slow at times and may refuse a client that asks too often, so it's
 * asked gently (#197): one search at a time, a second failing at once rather than queueing behind
 * one that may take half a minute; at least [CveProperties.mavenSearchGap] between searches; and
 * after a 429, a 503 or an error with a `Retry-After`, no searches until the time it gives (at most
 * [CveProperties.mavenBackOffMax] away), or for [CveProperties.mavenBackOff]. The back-off is
 * logged once at WARN; a search refused during it isn't logged (one refused while another is in
 * flight is, at DEBUG), and fails as any other would, not kept. Everything else (CVE.org, OSV, kept
 * searches) carries on as normal.
 *
 * [sleeper] waits out the gap; tests pass one that moves their [clock] instead.
 */
@Service
class CveLookupService(
    private val properties: CveProperties,
    private val userAgent: String,
    private val clock: Clock,
    private val sleeper: (Duration) -> Unit = { Thread.sleep(it.toMillis()) },
) {
    @Autowired
    constructor(
        properties: CveProperties,
        fetchProperties: FetchProperties,
    ) : this(properties, fetchProperties.userAgent, Clock.systemUTC())

    private val logger = LoggerFactory.getLogger(CveLookupService::class.java)
    private val mapper = JacksonMappers.standard()
    private val client: HttpClient =
        HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(properties.connectTimeout)
            .build()

    /** A record, or a known absence: a cache can't hold a null. */
    private data class RecordLookup(val record: CveRecord?)

    private val records = ExpiringCache<String, RecordLookup>(properties.recordTtl, clock)
    private val packages = ExpiringCache<String, List<OsvVuln>>(properties.packageTtl, clock)
    private val searches = ExpiringCache<String, List<MavenArtifact>>(properties.searchTtl, clock)

    /** Held for the length of a Maven Central search. */
    private val searchLock = ReentrantLock()
    /** When the last search ended. */
    @Volatile private var lastSearch: Instant? = null
    /** No searches before this. */
    @Volatile private var backOffUntil: Instant? = null

    /** The record for [id] (`CVE-2021-44228`), or null when CVE.org has none. */
    fun record(id: String): CveRecord? =
        records
            .get(id) {
                val response =
                    send("CVE.org", HttpRequest.newBuilder(URI("${properties.cveUrl}/$id")).GET())
                when (response.statusCode()) {
                    404 -> RecordLookup(null)
                    else ->
                        RecordLookup(
                            parse("CVE.org", ok("CVE.org", response)) { parseRecord(id, it) },
                        )
                }
            }
            .record

    /** The vulnerabilities OSV knows in [ref], only those affecting [version] when it's given. */
    fun vulnerabilities(ref: PackageRef, version: String?): List<OsvVuln> =
        packages.get("${ref.ecosystem.osvName}|${ref.name}|${version.orEmpty()}") {
            val vulns = mutableListOf<OsvVuln>()
            var pageToken: String? = null
            var pages = 0
            do {
                val query =
                    buildMap<String, Any> {
                        put(
                            "package",
                            mapOf("name" to ref.name, "ecosystem" to ref.ecosystem.osvName),
                        )
                        version?.let { put("version", it) }
                        pageToken?.let { put("page_token", it) }
                    }
                val request =
                    HttpRequest.newBuilder(URI(properties.osvUrl))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(query)))
                val body = ok("OSV", send("OSV", request))
                pageToken =
                    parse("OSV", body) { root ->
                        root.path("vulns").values().mapTo(vulns) { parseVuln(it, ref) }
                        root.path("next_page_token").asString().ifBlank { null }
                    }
            } while (pageToken != null && ++pages < MAX_OSV_PAGES)
            vulns
        }

    /** Maven Central artifacts whose artifactId is exactly [artifactId], most versions first. */
    fun mavenArtifacts(artifactId: String): List<MavenArtifact> =
        searches.get(artifactId) {
            val query = URLEncoder.encode("a:\"$artifactId\"", Charsets.UTF_8)
            val uri = URI("${properties.mavenSearchUrl}?q=$query&rows=$MAVEN_ROWS&wt=json")
            val body = search(uri)
            parse(MAVEN, body) { root ->
                root
                    .path("response")
                    .path("docs")
                    .values()
                    .filter { it.path("a").asString() == artifactId }
                    .map {
                        MavenArtifact(it.path("id").asString(), it.path("versionCount").asInt())
                    }
                    .sortedByDescending { it.versionCount }
            }
        }

    /** Forgets every kept answer, and any Maven Central back-off. */
    fun clearCache() {
        records.clear()
        packages.clear()
        searches.clear()
        lastSearch = null
        backOffUntil = null
    }

    /** One Maven Central search's body, asked as gently as the class comment says. */
    private fun search(uri: URI): String {
        if (!searchLock.tryLock()) {
            logger.debug("cve: a Maven Central search is in flight; refusing another")
            throw CveLookupFailed(MAVEN, "another search is in flight")
        }
        try {
            backOffUntil?.let { until ->
                if (clock.instant().isBefore(until)) {
                    throw CveLookupFailed(MAVEN, "backing off until $until")
                }
            }
            lastSearch?.let { last ->
                val wait = Duration.between(clock.instant(), last.plus(properties.mavenSearchGap))
                if (wait.isPositive) pause(wait)
            }
            try {
                // Maven Central's search is often slow, so it has a timeout of its own.
                val response =
                    send(MAVEN, HttpRequest.newBuilder(uri).GET(), properties.mavenSearchTimeout)
                backOff(response)
                return ok(MAVEN, response)
            } finally {
                lastSearch = clock.instant()
            }
        } finally {
            searchLock.unlock()
        }
    }

    private fun pause(wait: Duration) {
        try {
            sleeper(wait)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw CveLookupFailed(MAVEN, "interrupted")
        }
    }

    /**
     * Starts a back-off when [response] asks for one: a 429 or 503, or any error with a
     * `Retry-After` (seconds, or an HTTP date). Throws, so the response isn't read.
     */
    private fun backOff(response: HttpResponse<String>) {
        val status = response.statusCode()
        val retryAfter = response.headers().firstValue("Retry-After").orElse(null)
        if (status !in BACK_OFF_STATUSES && (status in 200..299 || retryAfter == null)) return
        val now = clock.instant()
        val until =
            retryAfter
                ?.let { retryAt(it.trim(), now) }
                ?.let {
                    minOf(it, now.plus(properties.mavenBackOffMax))
                } ?: now.plus(properties.mavenBackOff)
        backOffUntil = until
        logger.warn(
            "cve: Maven Central answered HTTP {}{}; not searching it until {}",
            status,
            retryAfter?.let { " (Retry-After: $it)" }.orEmpty(),
            until,
        )
        throw CveLookupFailed(MAVEN, "HTTP $status from ${response.uri()}")
    }

    /** When a `Retry-After` of [value] ends, or null when it can't be read. */
    private fun retryAt(value: String, now: Instant): Instant? =
        value.toLongOrNull()?.let { now.plusSeconds(it.coerceAtLeast(0)) }
            ?: runCatching {
                ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()
            }
                .getOrNull()

    private fun send(
        upstream: String,
        request: HttpRequest.Builder,
        timeout: Duration = properties.requestTimeout,
    ): HttpResponse<String> =
        try {
            client.send(
                request.timeout(timeout).header("User-Agent", userAgent).build(),
                HttpResponse.BodyHandlers.ofString(),
            )
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw failed(upstream, "interrupted")
        } catch (e: Exception) {
            throw failed(upstream, "${e.javaClass.simpleName}: ${e.message}")
        }

    private fun ok(upstream: String, response: HttpResponse<String>): String {
        if (response.statusCode() !in 200..299) {
            throw failed(upstream, "HTTP ${response.statusCode()} from ${response.uri()}")
        }
        return response.body()
    }

    private fun <T> parse(upstream: String, body: String, reader: (JsonNode) -> T): T =
        try {
            reader(mapper.readTree(body))
        } catch (e: Exception) {
            throw failed(upstream, "unreadable response: ${e.message}")
        }

    private fun failed(upstream: String, why: String): CveLookupFailed {
        logger.warn("cve: {} lookup failed: {}", upstream, why)
        return CveLookupFailed(upstream, why)
    }

    /**
     * A CVE JSON 5 record. Its severity is the CNA's when the CNA scored it, else an ADP's (CISA's
     * Vulnrichment scores many the CNA didn't), preferring the newest CVSS version in each.
     */
    private fun parseRecord(id: String, root: JsonNode): CveRecord {
        val metadata = root.path("cveMetadata")
        val cna = root.path("containers").path("cna")
        val state = metadata.path("state").asString().ifBlank { "PUBLISHED" }
        val texts =
            if (state == "REJECTED") cna.path("rejectedReasons") else cna.path("descriptions")
        val description =
            texts
                .values()
                .let { all ->
                    all.firstOrNull { it.path("lang").asString().startsWith("en") }
                        ?: all.firstOrNull()
                }
                ?.path("value")
                ?.asString()
                ?.ifBlank { null }
        val published =
            metadata
                .path("datePublished")
                .asString()
                .takeIf { it.length >= 10 }
                ?.let {
                    runCatching { LocalDate.parse(it.substring(0, 10)) }.getOrNull()
                }
        val containers = listOf(cna) + root.path("containers").path("adp").values()
        val metric = containers.firstNotNullOfOrNull { container ->
            CVSS_KEYS.firstNotNullOfOrNull { key ->
                container
                    .path("metrics")
                    .values()
                    .map { it.path(key) }
                    .firstOrNull { it.has("baseScore") }
            }
        }
        val score = metric?.path("baseScore")?.asDouble()
        val severity =
            Severity.parse(metric?.path("baseSeverity")?.asString())
                ?: score?.let(Severity::ofScore)
        return CveRecord(
            id = metadata.path("cveId").asString().ifBlank { id },
            state = state,
            description = description,
            published = published,
            severity = severity,
            score = score,
        )
    }

    /**
     * An OSV vulnerability, with only the ranges and versions that are [ref]'s. Its rating is the
     * advisory's own (`database_specific.severity`, as GitHub's carry) or else worked out from a
     * CVSS 3 vector; its score comes from the vector. Only version ranges are kept.
     */
    private fun parseVuln(node: JsonNode, ref: PackageRef): OsvVuln {
        val affected = node.path("affected").values()
        val ours =
            affected
                .filter {
                    val pkg = it.path("package")
                    pkg.path("name").asString().equals(ref.name, ignoreCase = true) &&
                        pkg.path("ecosystem").asString().substringBefore(':') ==
                            ref.ecosystem.osvName
                }
                .ifEmpty { affected }
        val score =
            node
                .path("severity")
                .values()
                .mapNotNull { Cvss.baseScore(it.path("score").asString()) }
                .maxOrNull()
        val severity =
            Severity.parse(node.path("database_specific").path("severity").asString())
                ?: ours.firstNotNullOfOrNull {
                    Severity.parse(it.path("database_specific").path("severity").asString())
                }
                ?: score?.let(Severity::ofScore)
        return OsvVuln(
            id = node.path("id").asString(),
            aliases = node.path("aliases").values().map { it.asString() },
            published =
                node
                    .path("published")
                    .asString()
                    .ifBlank { null }
                    ?.let {
                        runCatching { OffsetDateTime.parse(it).toInstant() }.getOrNull()
                    },
            severity = severity,
            score = score,
            ranges =
                ours
                    .flatMap { it.path("ranges").values() }
                    // A GIT range's events are commits, which can't be compared without the repo.
                    .filter { it.path("type").asString() in VERSION_RANGES }
                    .map { range ->
                        OsvRange(
                            type = range.path("type").asString(),
                            events =
                                range.path("events").values().flatMap { event ->
                                    EVENT_KINDS.mapNotNull { kind ->
                                        event
                                            .path(kind)
                                            .asString()
                                            .ifBlank { null }
                                            ?.let { OsvEvent(kind, it) }
                                    }
                                },
                        )
                    },
            versions = ours.flatMap { it.path("versions").values().map { v -> v.asString() } },
        )
    }

    private companion object {
        private const val MAVEN = "Maven Central"
        private val BACK_OFF_STATUSES = setOf(429, 503)
        private const val MAX_OSV_PAGES = 5
        private const val MAVEN_ROWS = 20
        private val CVSS_KEYS = listOf("cvssV4_0", "cvssV3_1", "cvssV3_0", "cvssV2_0")
        private val VERSION_RANGES = setOf("ECOSYSTEM", "SEMVER")
        private val EVENT_KINDS = listOf("introduced", "fixed", "last_affected", "limit")
    }
}

/** An upstream couldn't be asked: it's been logged; the asker gets a one-line apology. */
class CveLookupFailed(val upstream: String, why: String) : RuntimeException("$upstream: $why")
