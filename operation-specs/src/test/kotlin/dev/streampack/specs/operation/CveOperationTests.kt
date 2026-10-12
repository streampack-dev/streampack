/* Joseph B. Ottinger (C)2026 */
package dev.streampack.specs.operation

import dev.streampack.core.integration.EventGateway
import dev.streampack.core.model.OperationResult
import dev.streampack.core.model.Protocol
import dev.streampack.core.model.Provenance
import dev.streampack.core.model.ThrottlePolicy
import dev.streampack.core.service.OperationService
import dev.streampack.core.service.ThrottleService
import dev.streampack.specs.config.CveProperties
import dev.streampack.specs.model.CveRequest
import dev.streampack.specs.service.CveLookupService
import dev.streampack.specs.service.CveReporter
import dev.streampack.specs.service.CveStubServer
import dev.streampack.specs.service.CveStubServer.Companion.MAVEN
import dev.streampack.specs.service.CveStubServer.Companion.NO_VULNS
import dev.streampack.specs.service.CveStubServer.Companion.OSV
import dev.streampack.specs.service.CveStubServer.Companion.cvePath
import dev.streampack.specs.service.CveStubServer.Companion.fixture
import dev.streampack.specs.service.CveStubServer.Companion.mavenSearch
import dev.streampack.specs.service.CveStubServer.Reply
import java.time.Duration
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.messaging.Message
import org.springframework.messaging.support.MessageBuilder
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import tools.jackson.databind.json.JsonMapper

/** `cve`, end to end through the event gateway, against canned CVE.org, OSV and Maven Central. */
@SpringBootTest
class CveOperationTests {

    @Autowired lateinit var eventGateway: EventGateway
    @Autowired lateinit var lookupService: CveLookupService
    @Autowired lateinit var reporter: CveReporter
    @Autowired lateinit var properties: CveProperties
    @Autowired lateinit var throttleService: ThrottleService
    @Autowired lateinit var operation: CveOperation

    private val mapper = JsonMapper.builder().build()

    private fun message(text: String) =
        MessageBuilder.withPayload(text)
            .setHeader(
                Provenance.HEADER,
                Provenance(protocol = Protocol.CONSOLE, serviceId = "", replyTo = "local"),
            )
            .build()

    private fun reply(text: String): String {
        val result = eventGateway.process(message(text))
        assertInstanceOf(OperationResult.Success::class.java, result, text)
        return (result as OperationResult.Success).payload.toString()
    }

    /** The package and ecosystem each OSV query asked about. */
    private fun osvPackages(): List<Pair<String, String>> =
        stub.osvQueries().map {
            val pkg = mapper.readTree(it).path("package")
            pkg.path("ecosystem").asString() to pkg.path("name").asString()
        }

    @BeforeEach
    fun setUp() {
        stub.reset()
        lookupService.clearCache()
        throttleService.clear()
    }

    @Test
    fun `a CVE id resolves in both forms`() {
        stub.routes[cvePath("CVE-2021-44228")] = { Reply(200, fixture("CVE-2021-44228.json")) }

        for (asked in listOf("cve CVE-2021-44228", "cve 2021-44228", "cve cve-2021-44228")) {
            val payload = reply(asked)
            assertTrue(payload.startsWith("CVE-2021-44228 (critical 10.0): Apache Log4j2"), payload)
            assertTrue(
                payload.endsWith(
                    " Published 2021-12-10. https://www.cve.org/CVERecord?id=CVE-2021-44228"
                ),
                payload,
            )
            assertTrue(payload.length <= 400, payload)
        }
    }

    @Test
    fun `an unknown id says so`() {
        stub.routes[cvePath("CVE-2021-99999999")] = {
            Reply(404, """{"error":"CVE_RECORD_DNE"}""")
        }

        assertEquals("CVE.org has no record of CVE-2021-99999999.", reply("cve 2021-99999999"))
    }

    @Test
    fun `a rejected id says why`() {
        stub.routes[cvePath("CVE-2023-0001")] = {
            Reply(
                200,
                """{"cveMetadata":{"cveId":"CVE-2023-0001","state":"REJECTED"},""" +
                    """"containers":{"cna":{"rejectedReasons":[{"lang":"en",""" +
                    """"value":"This candidate was withdrawn by its CNA."}]}}}""",
            )
        }

        assertEquals(
            "CVE-2023-0001 was rejected: This candidate was withdrawn by its CNA. " +
                "https://www.cve.org/CVERecord?id=CVE-2023-0001",
            reply("cve CVE-2023-0001"),
        )
    }

    @Test
    fun `Maven coordinates with a version list the CVEs worst first, and the lowest fixing all`() {
        stub.routes[OSV] = { Reply(200, fixture("osv-log4j-core-2.14.1.json")) }

        val expected =
            "org.apache.logging.log4j:log4j-core 2.14.1: 7 known to OSV, worst first: " +
                "CVE-2021-44228 (critical), CVE-2021-45046 (critical), CVE-2021-45105 (high), " +
                "CVE-2021-44832 (medium), CVE-2025-68161 (medium), …and 2 more; OSV lists " +
                "2.25.4 as fixing all. https://osv.dev/list?ecosystem=Maven&q=org.apache.logging.log4j%3Alog4j-core"
        assertEquals(expected, reply("cve org.apache.logging.log4j:log4j-core 2.14.1"))
        // The version can also be the coordinates' third part.
        assertEquals(expected, reply("cve org.apache.logging.log4j:log4j-core:2.14.1"))

        val query = mapper.readTree(stub.osvQueries().first())
        assertEquals("Maven", query.path("package").path("ecosystem").asString())
        assertEquals(
            "org.apache.logging.log4j:log4j-core",
            query.path("package").path("name").asString(),
        )
        assertEquals("2.14.1", query.path("version").asString())
    }

    @Test
    fun `without a version a package gets a summary`() {
        stub.routes[OSV] = { Reply(200, fixture("osv-log4j-core.json")) }

        assertEquals(
            "org.apache.logging.log4j:log4j-core: 11 known to OSV, worst CVE-2021-44228 " +
                "(critical), newest CVE-2026-34480 (medium); OSV lists 2.25.4 as fixing all. " +
                "https://osv.dev/list?ecosystem=Maven&q=org.apache.logging.log4j%3Alog4j-core",
            reply("cve org.apache.logging.log4j:log4j-core"),
        )
    }

    @Test
    fun `aliases of one vulnerability count once, under its CVE`() {
        stub.routes[OSV] = { Reply(200, fixture("osv-lodash-4.17.15.json")) }

        assertEquals(
            "npm:lodash 4.17.15: 4 known to OSV, worst first: CVE-2021-23337 (high), " +
                "CVE-2020-8203 (high), CVE-2025-13465 (medium), CVE-2020-28500 (medium); OSV " +
                "lists 4.18.0 as fixing all. https://osv.dev/list?ecosystem=npm&q=lodash",
            reply("cve npm:lodash 4.17.15"),
        )
    }

    @Test
    fun `each ecosystem prefix reaches OSV with its ecosystem`() {
        stub.routes[OSV] = { Reply(200, NO_VULNS) }
        val asked =
            mapOf(
                "npm:lodash" to ("npm" to "lodash"),
                "npm:@angular/core" to ("npm" to "@angular/core"),
                "go:golang.org/x/net" to ("Go" to "golang.org/x/net"),
                "crates:openssl" to ("crates.io" to "openssl"),
                "cargo:serde" to ("crates.io" to "serde"),
                "pypi:requests" to ("PyPI" to "requests"),
                "gem:rails" to ("RubyGems" to "rails"),
                "nuget:Newtonsoft.Json" to ("NuGet" to "Newtonsoft.Json"),
                "composer:laravel/framework" to ("Packagist" to "laravel/framework"),
                "hex:plug" to ("Hex" to "plug"),
                "pub:http" to ("Pub" to "http"),
                "io.netty:netty-codec-http" to ("Maven" to "io.netty:netty-codec-http"),
            )
        for (term in asked.keys) reply("cve $term")
        assertEquals(asked.values.toList(), osvPackages())
    }

    @Test
    fun `nothing in OSV is none known, never safe`() {
        stub.routes[OSV] = { Reply(200, NO_VULNS) }

        assertEquals(
            "pypi:requests 2.32.4: none known to OSV. https://osv.dev/list?ecosystem=PyPI&q=requests",
            reply("cve pypi:requests 2.32.4"),
        )
    }

    @Test
    fun `an alias naming several packages gets a line for each`() {
        stub.routes[OSV] = { request ->
            val name = mapper.readTree(request.body).path("package").path("name").asString()
            Reply(
                200,
                if (name == "log4j:log4j") fixture("osv-log4j.json")
                else fixture("osv-log4j-core.json"),
            )
        }

        val lines = reply("cve log4j").lines()
        assertEquals(2, lines.size, lines.toString())
        assertTrue(
            lines[0].startsWith("log4j:log4j: 6 known to OSV, worst CVE-2019-17571 (critical)"),
            lines[0],
        )
        assertTrue(
            lines[1].startsWith("org.apache.logging.log4j:log4j-core: 11 known to OSV"),
            lines[1],
        )
        assertEquals(
            listOf("Maven" to "log4j:log4j", "Maven" to "org.apache.logging.log4j:log4j-core"),
            osvPackages(),
        )
        assertTrue(stub.requests.none { it.path == MAVEN })
    }

    @Test
    fun `an alias naming several packages, with a version, asks which package`() {
        val payload = reply("cve log4j 1.2.17")
        assertEquals(
            "log4j covers 2 packages: log4j:log4j, org.apache.logging.log4j:log4j-core; ask " +
                "again with one of them and the version, as in `cve log4j:log4j 1.2.17`.",
            payload,
        )
        assertTrue(stub.requests.isEmpty())
    }

    @Test
    fun `an alias naming one package takes a version`() {
        stub.routes[OSV] = { Reply(200, fixture("osv-log4j-core-2.14.1.json")) }

        val payload = reply("cve log4j2 2.14.1")
        assertTrue(
            payload.startsWith("org.apache.logging.log4j:log4j-core 2.14.1: 7 known to OSV"),
            payload,
        )
        assertEquals(listOf("Maven" to "org.apache.logging.log4j:log4j-core"), osvPackages())
        assertEquals(
            "2.14.1",
            mapper.readTree(stub.osvQueries().single()).path("version").asString(),
        )
    }

    @Test
    fun `versions that can't be ordered get each CVE's fixes, as OSV's`() {
        stub.routes[OSV] = {
            Reply(
                200,
                """{"vulns":[{"id":"PYSEC-1","aliases":["CVE-2020-1111"],""" +
                    """"database_specific":{"severity":"HIGH"},"affected":[{"package":""" +
                    """{"name":"example","ecosystem":"PyPI"},"ranges":[{"type":"ECOSYSTEM",""" +
                    """"events":[{"introduced":"0"},{"fixed":"1.0.post1"}]}]}]},""" +
                    """{"id":"PYSEC-2","aliases":["CVE-2021-2222"],"affected":[{"package":""" +
                    """{"name":"example","ecosystem":"PyPI"},"ranges":[{"type":"ECOSYSTEM",""" +
                    """"events":[{"introduced":"0"}]}]}]}]}""",
            )
        }

        assertEquals(
            "pypi:example 0.9: 2 known to OSV, worst first: CVE-2020-1111 (high, OSV says fixed " +
                "in 1.0.post1), CVE-2021-2222 (OSV lists no fix). " +
                "https://osv.dev/list?ecosystem=PyPI&q=example",
            reply("cve pypi:example 0.9"),
        )
    }

    @Test
    fun `a bare name with one Maven Central match uses it`() {
        stub.routes[MAVEN] = { Reply(200, mavenSearch("org.yaml:snakeyaml-engine")) }
        stub.routes[OSV] = { Reply(200, NO_VULNS) }

        assertEquals(
            "org.yaml:snakeyaml-engine 2.9: none known to OSV. " +
                "https://osv.dev/list?ecosystem=Maven&q=org.yaml%3Asnakeyaml-engine",
            reply("cve snakeyaml-engine 2.9"),
        )
        assertEquals(listOf("Maven" to "org.yaml:snakeyaml-engine"), osvPackages())
    }

    @Test
    fun `a bare name with several Maven Central matches lists them and asks`() {
        stub.routes[MAVEN] = { Reply(200, fixture("maven-log4j-core.json")) }

        val payload = reply("cve log4j-core")
        assertTrue(
            payload.startsWith("log4j-core is the artifactId of 5 Maven artifacts: "),
            payload,
        )
        assertTrue(payload.contains("org.apache.logging.log4j:log4j-core"), payload)
        assertTrue(payload.endsWith("ask again with group:artifact."), payload)
        assertTrue(stub.osvQueries().isEmpty())
    }

    @Test
    fun `a bare name nothing on Maven Central has says how to ask`() {
        stub.routes[MAVEN] = { Reply(200, mavenSearch()) }

        assertEquals(
            "No Maven artifact is named leftpad; ask by group:artifact, or with an ecosystem " +
                "prefix such as npm:leftpad.",
            reply("cve leftpad"),
        )
    }

    @Test
    fun `an upstream error gets one line`() {
        stub.routes[OSV] = { Reply(500, "oops") }
        stub.routes[cvePath("CVE-2021-44228")] = { Reply(429, "slow down") }

        assertEquals(
            "Couldn't look that up right now: OSV didn't answer.",
            reply("cve npm:lodash 4.17.15"),
        )
        assertEquals(
            "Couldn't look that up right now: CVE.org didn't answer.",
            reply("cve 2021-44228"),
        )
        // Maven Central down.
        stub.routes[MAVEN] = { Reply(503, "") }
        assertEquals(
            "Couldn't look that up right now: Maven Central didn't answer.",
            reply("cve netty"),
        )
    }

    @Test
    fun `text that isn't a cve question isn't handled`() {
        for (text in listOf("cve", "cve a b c", "cve log4j;rm", "hello world")) {
            assertInstanceOf(
                OperationResult.NotHandled::class.java,
                eventGateway.process(message(text)),
                text,
            )
        }
    }

    @Test
    fun `an id lookup seeds a light factoid, and a package lookup none`() {
        stub.routes[cvePath("CVE-2021-44228")] = { Reply(200, fixture("CVE-2021-44228.json")) }
        stub.routes[OSV] = { Reply(200, fixture("osv-log4j-core.json")) }
        val seeded = mutableListOf<String>()
        val recorder =
            object : EventGateway {
                override fun process(message: Message<*>): OperationResult {
                    seeded += message.payload.toString()
                    return OperationResult.Success("ok")
                }

                override fun send(message: Message<*>) {
                    seeded += message.payload.toString()
                }
            }
        val operation = CveOperation(reporter, recorder, properties)

        operation.handle(CveRequest("CVE-2021-44228"), message("cve CVE-2021-44228"))
        assertEquals(
            listOf("cve-2021-44228.url=https://www.cve.org/CVERecord?id=CVE-2021-44228"),
            seeded,
        )

        seeded.clear()
        operation.handle(CveRequest("org.apache.logging.log4j:log4j-core"), message("cve x"))
        operation.handle(CveRequest("log4j"), message("cve log4j"))
        assertEquals(emptyList<String>(), seeded)
    }

    @Test
    fun `the command is throttled per channel, as configured`() {
        assertEquals(ThrottlePolicy(THROTTLE, Duration.ofHours(1)), operation.throttlePolicy)
        assertEquals(
            ThrottlePolicy(10, Duration.ofHours(1)),
            CveOperation(reporter, eventGateway, CveProperties()).throttlePolicy,
        )

        // An alias asked with a version needs no upstream, so the throttle is all that's tested.
        repeat(THROTTLE) { reply("cve log4j 1.2.17") }
        val throttled = eventGateway.process(message("cve log4j 1.2.17"))
        assertEquals(OperationResult.Error(OperationService.THROTTLED), throttled)
    }

    @Test
    fun `during a Maven Central back-off searches get the plain failure line, and the rest answer`() {
        stub.routes[MAVEN] = { Reply(200, mavenSearch("org.yaml:snakeyaml-engine")) }
        stub.routes[OSV] = { Reply(200, NO_VULNS) }
        reply("cve snakeyaml-engine")
        stub.routes[MAVEN] = { Reply(429, "slow down") }

        val unavailable = "Couldn't look that up right now: Maven Central didn't answer."
        assertEquals(unavailable, reply("cve netty"))
        // Refused without asking, and not kept.
        assertEquals(unavailable, reply("cve netty"))
        assertEquals(2, stub.mavenSearches().size)

        // A kept search, an alias and coordinates still answer.
        assertTrue(
            reply("cve snakeyaml-engine").startsWith("org.yaml:snakeyaml-engine: none known")
        )
        assertTrue(reply("cve h2").startsWith("com.h2database:h2: none known"))
        assertTrue(reply("cve io.netty:netty-codec-http").startsWith("io.netty:netty-codec-http:"))
        assertEquals(2, stub.mavenSearches().size)
    }

    companion object {
        /** Above the default, so tests that ask many times aren't throttled. */
        private const val THROTTLE = 40

        private val stub = CveStubServer()

        @JvmStatic
        @DynamicPropertySource
        fun upstreams(registry: DynamicPropertyRegistry) {
            val properties = stub.properties()
            registry.add("streampack.specs.cve.cve-url") { properties.cveUrl }
            registry.add("streampack.specs.cve.osv-url") { properties.osvUrl }
            registry.add("streampack.specs.cve.maven-search-url") { properties.mavenSearchUrl }
            registry.add("streampack.specs.cve.throttle-requests") { THROTTLE }
            registry.add("streampack.specs.cve.maven-search-gap") { "0s" }
        }

        @JvmStatic @AfterAll fun stopStub() = stub.stop()
    }
}
