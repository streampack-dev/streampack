/* Joseph B. Ottinger (C)2026 */
package dev.streampack.specs.service

import dev.streampack.specs.model.Ecosystem
import dev.streampack.specs.model.PackageRef
import dev.streampack.specs.model.Severity
import dev.streampack.specs.service.CveStubServer.Companion.cvePath
import dev.streampack.specs.service.CveStubServer.Companion.fixture
import dev.streampack.specs.service.CveStubServer.Reply
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** The upstream calls, against canned responses: parsing, caching and failure. */
class CveLookupServiceTests {

    private val stub = CveStubServer()

    /** A clock the test moves forward. */
    private class ManualClock(var now: Instant = Instant.parse("2026-10-11T12:00:00Z")) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId?): Clock = this

        override fun instant(): Instant = now
    }

    private val clock = ManualClock()

    private fun service(timeout: Duration = Duration.ofSeconds(5)) =
        CveLookupService(stub.properties(timeout), "test", clock)

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
}
