/* Joseph B. Ottinger (C)2026 */
package dev.streampack.specs.config

import java.time.Duration
import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * How `cve` looks things up (#194): where CVE.org, OSV.dev and Maven Central are, how long a call
 * may take, and how long an answer is kept. CVE records and Maven Central searches change rarely
 * and are kept for [recordTtl] and [searchTtl]; a package's vulnerabilities are where new CVEs
 * appear, so they're kept only for [packageTtl]. A failed call is never kept.
 *
 * [aliases] are names that aren't an artifact's, each mapped to one or more coordinates, added to
 * (or, for the same name, replacing) [DEFAULT_ALIASES]; an empty list drops a default. Coordinates
 * are `group:artifact`, or an ecosystem prefix and a name (`npm:lodash`).
 *
 * `cve` is throttled to [throttleRequests] per [throttleWindow] in each channel (#197). Maven
 * Central's search, the fragile upstream, is asked once at a time, at least [mavenSearchGap] apart,
 * and not at all for [mavenBackOff] after it refuses a search without saying how long to wait, or
 * for as long as it says, up to [mavenBackOffMax].
 */
@ConfigurationProperties(prefix = "streampack.specs.cve")
data class CveProperties(
    val cveUrl: String = "https://cveawg.mitre.org/api/cve",
    val osvUrl: String = "https://api.osv.dev/v1/query",
    val mavenSearchUrl: String = "https://search.maven.org/solrsearch/select",
    val connectTimeout: Duration = Duration.ofSeconds(5),
    /** Timeout for a whole call to CVE.org or OSV. */
    val requestTimeout: Duration = Duration.ofSeconds(10),
    /** Maven Central's search is often slow (half a minute isn't rare), so it gets longer. */
    val mavenSearchTimeout: Duration = Duration.ofSeconds(30),
    val recordTtl: Duration = Duration.ofDays(1),
    val searchTtl: Duration = Duration.ofDays(1),
    val packageTtl: Duration = Duration.ofHours(4),
    val aliases: Map<String, List<String>> = emptyMap(),
    /** How many `cve` commands a channel may run in [throttleWindow]. */
    val throttleRequests: Int = 10,
    val throttleWindow: Duration = Duration.ofHours(1),
    /** The least time between the end of one Maven Central search and the start of the next. */
    val mavenSearchGap: Duration = Duration.ofSeconds(2),
    /** How long to stop searching after a 429 or 503 that has no `Retry-After`. */
    val mavenBackOff: Duration = Duration.ofMinutes(15),
    /** The longest a `Retry-After` may stop searches for; a longer one is cut to this. */
    val mavenBackOffMax: Duration = Duration.ofHours(24),
) {
    /** The aliases in force: the defaults, with configured ones added or replacing them. */
    val effectiveAliases: Map<String, List<String>>
        get() =
            (DEFAULT_ALIASES + aliases.mapKeys { it.key.lowercase() })
                .mapValues { (_, coordinates) ->
                    coordinates.map { it.trim() }.filter { it.isNotEmpty() }
                }
                .filterValues { it.isNotEmpty() }

    companion object {
        /**
         * Well-known names that aren't an artifact's, or that many artifacts share. Only names
         * whose coordinates are unambiguous: `spring` and `netty` are many artifacts, so aren't
         * here.
         */
        val DEFAULT_ALIASES: Map<String, List<String>> =
            mapOf(
                "log4j" to listOf("log4j:log4j", "org.apache.logging.log4j:log4j-core"),
                "log4j2" to listOf("org.apache.logging.log4j:log4j-core"),
                "logback" to
                    listOf("ch.qos.logback:logback-classic", "ch.qos.logback:logback-core"),
                "jackson-databind" to
                    listOf(
                        "com.fasterxml.jackson.core:jackson-databind",
                        "tools.jackson.core:jackson-databind",
                    ),
                "commons-text" to listOf("org.apache.commons:commons-text"),
                "commons-collections" to
                    listOf(
                        "commons-collections:commons-collections",
                        "org.apache.commons:commons-collections4",
                    ),
                "snakeyaml" to listOf("org.yaml:snakeyaml"),
                "xstream" to listOf("com.thoughtworks.xstream:xstream"),
                "struts2" to listOf("org.apache.struts:struts2-core"),
                "h2" to listOf("com.h2database:h2"),
                "guava" to listOf("com.google.guava:guava"),
            )
    }
}
