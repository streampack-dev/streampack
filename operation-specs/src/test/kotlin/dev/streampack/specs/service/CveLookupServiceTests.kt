/* Joseph B. Ottinger (C)2026 */
package dev.streampack.specs.service

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import dev.streampack.specs.config.CveProperties
import dev.streampack.specs.model.Ecosystem
import dev.streampack.specs.model.PackageRef
import dev.streampack.specs.model.Severity
import dev.streampack.specs.service.CveStubServer.Companion.cvePath
import dev.streampack.specs.service.CveStubServer.Companion.fixture
import dev.streampack.specs.service.CveStubServer.Companion.mavenSearch
import dev.streampack.specs.service.CveStubServer.Reply
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.slf4j.LoggerFactory

/**
 * The upstream calls, against canned responses: parsing, caching, failure, and going easy on Maven
 * Central.
 */
class CveLookupServiceTests {

    private val stub = CveStubServer()

    /** A clock the test moves forward. */
    private class ManualClock(var now: Instant = Instant.parse("2026-10-11T12:00:00Z")) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId?): Clock = this

        override fun instant(): Instant = now
    }

    private val clock = ManualClock()

    /** The waits for Maven Central's gap; each moves [clock] on instead of sleeping. */
    private val pauses = mutableListOf<Duration>()

    private fun service(
        timeout: Duration = Duration.ofSeconds(5),
        properties: CveProperties = stub.properties(timeout),
    ) =
        CveLookupService(properties, "test", clock) {
            pauses += it
            clock.now = clock.now.plus(it)
        }

    private val log4jCore = PackageRef(Ecosystem.MAVEN, "org.apache.logging.log4j:log4j-core")

    @AfterEach
    fun tearDown() {
        stub.stop()
    }

    @Test
    fun `a record's score comes from an ADP when the CNA gave none`() {
        stub.routes[cvePath("CVE-2021-44228")] = { Reply(200, fixture("CVE-2021-44228.json")) }

        val record = service().record("CVE-2021-44228")!!
        assertEquals(Severity.CRITICAL, record.severity)
        assertEquals(10.0, record.score)
        assertEquals(LocalDate.of(2021, 12, 10), record.published)
        assertEquals("PUBLISHED", record.state)
    }

    @Test
    fun `a record's own CNA score is used`() {
        stub.routes[cvePath("CVE-2024-38816")] = { Reply(200, fixture("CVE-2024-38816.json")) }

        val record = service().record("CVE-2024-38816")!!
        assertEquals(Severity.HIGH, record.severity)
        assertEquals(7.5, record.score)
    }

    @Test
    fun `an unknown id is no record, not a failure`() {
        stub.routes[cvePath("CVE-2021-99999999")] = {
            Reply(
                404,
                """{"error":"CVE_RECORD_DNE","message":"The cve record for the cve id does not exist."}""",
            )
        }

        assertNull(service().record("CVE-2021-99999999"))
    }

    @Test
    fun `OSV vulnerabilities keep only the package's version ranges`() {
        stub.routes[CveStubServer.OSV] = { Reply(200, fixture("osv-requests-2.19.0.json")) }

        val vulns = service().vulnerabilities(PackageRef(Ecosystem.PYPI, "requests"), "2.19.0")
        assert(vulns.isNotEmpty())
        // OSV gives these a GIT range too; its events are commits, not versions.
        assert(vulns.all { v -> v.ranges.all { it.type == "ECOSYSTEM" } }) { vulns }
        assert(vulns.flatMap { it.fixedVersions }.all { Regex("[\\d.]+").matches(it) })
    }

    @Test
    fun `a second call within the expiry is served from the cache, and after it upstream is asked again`() {
        stub.routes[CveStubServer.OSV] = { Reply(200, fixture("osv-log4j-core.json")) }
        val service = service()

        service.vulnerabilities(log4jCore, null)
        clock.now = clock.now.plus(Duration.ofHours(3))
        service.vulnerabilities(log4jCore, null)
        assertEquals(1, stub.osvQueries().size)

        clock.now = clock.now.plus(Duration.ofHours(2))
        service.vulnerabilities(log4jCore, null)
        assertEquals(2, stub.osvQueries().size)
    }

    @Test
    fun `records and searches are kept for a day`() {
        stub.routes[cvePath("CVE-2021-44228")] = { Reply(200, fixture("CVE-2021-44228.json")) }
        stub.routes[CveStubServer.MAVEN] = { Reply(200, fixture("maven-log4j-core.json")) }
        val service = service()

        repeat(2) {
            service.record("CVE-2021-44228")
            service.mavenArtifacts("log4j-core")
            clock.now = clock.now.plus(Duration.ofHours(23))
        }
        assertEquals(2, stub.requests.size)

        clock.now = clock.now.plus(Duration.ofHours(2))
        service.record("CVE-2021-44228")
        service.mavenArtifacts("log4j-core")
        assertEquals(4, stub.requests.size)
    }

    @Test
    fun `a failure isn't cached`() {
        var status = 503
        stub.routes[CveStubServer.OSV] = { Reply(status, if (status == 200) "{}" else "busy") }
        val service = service()

        assertThrows<CveLookupFailed> { service.vulnerabilities(log4jCore, "2.14.1") }
        status = 200
        assertEquals(emptyList<Any>(), service.vulnerabilities(log4jCore, "2.14.1"))
        assertEquals(2, stub.osvQueries().size)
    }

    @Test
    fun `a slow upstream times out as a failure`() {
        stub.routes[cvePath("CVE-2021-44228")] = {
            Reply(200, fixture("CVE-2021-44228.json"), delay = Duration.ofSeconds(2))
        }

        val failure =
            assertThrows<CveLookupFailed> {
                service(Duration.ofMillis(200)).record("CVE-2021-44228")
            }
        assertEquals("CVE.org", failure.upstream)
    }

    @Test
    fun `Maven Central has a longer timeout of its own`() {
        assertEquals(
            Duration.ofSeconds(30),
            dev.streampack.specs.config.CveProperties().mavenSearchTimeout,
        )
        stub.routes[CveStubServer.MAVEN] = {
            Reply(
                200,
                CveStubServer.mavenSearch("com.google.guava:guava"),
                delay = Duration.ofMillis(600),
            )
        }
        val properties =
            stub.properties(Duration.ofMillis(200)).copy(mavenSearchTimeout = Duration.ofSeconds(5))

        val artifacts = CveLookupService(properties, "test", clock).mavenArtifacts("guava")
        assertEquals(listOf("com.google.guava:guava"), artifacts.map { it.coordinates })
    }

    @Test
    fun `a body that isn't JSON is a failure`() {
        stub.routes[CveStubServer.MAVEN] = { Reply(200, "<html>rate limited</html>") }

        assertThrows<CveLookupFailed> { service().mavenArtifacts("guava") }
    }

    @Test
    fun `Maven Central candidates are those with exactly that artifactId, most versions first`() {
        stub.routes[CveStubServer.MAVEN] = { Reply(200, fixture("maven-log4j-core.json")) }

        val artifacts = service().mavenArtifacts("log4j-core")
        assertEquals(5, artifacts.size)
        assert(artifacts.zipWithNext().all { (a, b) -> a.versionCount >= b.versionCount })
        val query = stub.requests.single().query!!
        assert(query.contains("q=a:\"log4j-core\"")) { query }
    }

    @Test
    fun `searches in quick succession are spaced by the minimum gap`() {
        assertEquals(Duration.ofSeconds(2), CveProperties().mavenSearchGap)
        stub.routes[CveStubServer.MAVEN] = { Reply(200, mavenSearch("a:b")) }
        val service = service()

        service.mavenArtifacts("one")
        service.mavenArtifacts("two")
        assertEquals(listOf(Duration.ofSeconds(2)), pauses)

        clock.now = clock.now.plusMillis(500)
        service.mavenArtifacts("three")
        assertEquals(listOf(Duration.ofSeconds(2), Duration.ofMillis(1500)), pauses)

        clock.now = clock.now.plusSeconds(5)
        service.mavenArtifacts("four")
        assertEquals(2, pauses.size)
        assertEquals(4, stub.mavenSearches().size)

        // Only searches are spaced.
        stub.routes[CveStubServer.OSV] = { Reply(200, CveStubServer.NO_VULNS) }
        service.vulnerabilities(log4jCore, null)
        assertEquals(2, pauses.size)
    }

    @Test
    fun `a search while another is in flight fails at once rather than overlapping`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        stub.routes[CveStubServer.MAVEN] = {
            entered.countDown()
            release.await(10, TimeUnit.SECONDS)
            Reply(200, mavenSearch("org.yaml:snakeyaml"))
        }
        val service = service()
        val executor = Executors.newSingleThreadExecutor()
        try {
            val first =
                executor.submit<List<String>> {
                    service.mavenArtifacts("snakeyaml").map { it.coordinates }
                }
            assert(entered.await(10, TimeUnit.SECONDS))

            val refused = assertThrows<CveLookupFailed> { service.mavenArtifacts("other") }
            assertEquals("Maven Central", refused.upstream)
            release.countDown()
            assertEquals(listOf("org.yaml:snakeyaml"), first.get(10, TimeUnit.SECONDS))
            assertEquals(1, stub.mavenSearches().size)
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `a 429 with Retry-After in seconds stops searches until then, logged once`() {
        var reply = Reply(429, "slow down", headers = mapOf("Retry-After" to "120"))
        stub.routes[CveStubServer.MAVEN] = { reply }
        val service = service()
        val logger = LoggerFactory.getLogger(CveLookupService::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
        try {
            assertThrows<CveLookupFailed> { service.mavenArtifacts("one") }
            reply = Reply(200, mavenSearch("a:one"))
            clock.now = clock.now.plusSeconds(119)
            repeat(3) { assertThrows<CveLookupFailed> { service.mavenArtifacts("one") } }
            assertEquals(1, stub.mavenSearches().size)
            assertEquals(
                1,
                appender.list.count { it.level == Level.WARN },
                appender.list.toString(),
            )
        } finally {
            logger.detachAppender(appender)
        }

        // The refused searches weren't kept: once the back-off ends, the name is searched.
        clock.now = clock.now.plusSeconds(1)
        assertEquals(listOf("a:one"), service.mavenArtifacts("one").map { it.coordinates })
        assertEquals(2, stub.mavenSearches().size)
    }

    @Test
    fun `a Retry-After date is waited for`() {
        val until = clock.now.plus(Duration.ofMinutes(42))
        val date = DateTimeFormatter.RFC_1123_DATE_TIME.format(until.atZone(ZoneOffset.UTC))
        var reply = Reply(503, "", headers = mapOf("Retry-After" to date))
        stub.routes[CveStubServer.MAVEN] = { reply }
        val service = service()

        assertThrows<CveLookupFailed> { service.mavenArtifacts("one") }
        reply = Reply(200, mavenSearch("a:one"))
        clock.now = until.minusSeconds(1)
        assertThrows<CveLookupFailed> { service.mavenArtifacts("one") }
        clock.now = until
        service.mavenArtifacts("one")
        assertEquals(2, stub.mavenSearches().size)
    }

    @Test
    fun `a 429 without Retry-After stops searches for the configured back-off`() {
        assertEquals(Duration.ofMinutes(15), CveProperties().mavenBackOff)
        var reply = Reply(429, "slow down")
        stub.routes[CveStubServer.MAVEN] = { reply }
        val service =
            service(properties = stub.properties().copy(mavenBackOff = Duration.ofMinutes(5)))

        assertThrows<CveLookupFailed> { service.mavenArtifacts("one") }
        reply = Reply(200, mavenSearch("a:one"))
        clock.now = clock.now.plus(Duration.ofMinutes(5)).minusSeconds(1)
        assertThrows<CveLookupFailed> { service.mavenArtifacts("one") }
        clock.now = clock.now.plusSeconds(1)
        service.mavenArtifacts("one")
        assertEquals(2, stub.mavenSearches().size)
    }

    @Test
    fun `another error without Retry-After starts no back-off`() {
        var reply = Reply(500, "oops")
        stub.routes[CveStubServer.MAVEN] = { reply }
        val service = service()

        assertThrows<CveLookupFailed> { service.mavenArtifacts("one") }
        reply = Reply(200, mavenSearch("a:one"))
        service.mavenArtifacts("one")
        assertEquals(2, stub.mavenSearches().size)
    }

    @Test
    fun `during a back-off kept searches, records and packages still answer`() {
        stub.routes[CveStubServer.MAVEN] = { Reply(200, fixture("maven-log4j-core.json")) }
        stub.routes[cvePath("CVE-2021-44228")] = { Reply(200, fixture("CVE-2021-44228.json")) }
        stub.routes[CveStubServer.OSV] = { Reply(200, fixture("osv-log4j-core.json")) }
        val service = service()
        service.mavenArtifacts("log4j-core")
        stub.routes[CveStubServer.MAVEN] = { Reply(429, "slow down") }
        assertThrows<CveLookupFailed> { service.mavenArtifacts("other") }

        assertEquals(5, service.mavenArtifacts("log4j-core").size)
        assertEquals(Severity.CRITICAL, service.record("CVE-2021-44228")!!.severity)
        assert(service.vulnerabilities(log4jCore, null).isNotEmpty())
        assertEquals(2, stub.mavenSearches().size)
    }
}
