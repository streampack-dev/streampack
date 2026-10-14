/* Joseph B. Ottinger (C)2026 */
package dev.streampack.specs.service

import dev.streampack.specs.model.OsvEvent
import dev.streampack.specs.model.OsvRange
import dev.streampack.specs.model.OsvVuln
import dev.streampack.specs.model.VersionScheme
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class VersionsTests {

    private fun vuln(id: String, vararg ranges: List<Pair<String, String>>) =
        OsvVuln(
            id = id,
            aliases = emptyList(),
            published = null,
            severity = null,
            score = null,
            ranges =
                ranges.map { events ->
                    OsvRange("ECOSYSTEM", events.map { OsvEvent(it.first, it.second) })
                },
            versions = emptyList(),
        )

    @Test
    fun `Maven versions order as ComparableVersion does`() {
        val ordered =
            listOf(
                "2.0-alpha1",
                "2.0-beta9",
                "2.0-rc1",
                "2.0-SNAPSHOT",
                "2.0",
                "2.0.1",
                "2.3.1",
                "2.12.2",
                "2.15.0",
                "2.17.1",
                "10.0",
            )
        assertEquals(ordered, ordered.shuffled().sortedWith(Versions::compareMaven))
        assertEquals(0, Versions.compareMaven("1.0", "1.0.0.Final"))
        assertEquals(0, Versions.compareMaven("1.0.0", "1.0-GA"))
        assertTrue(Versions.compareMaven("1.0-sp1", "1.0") > 0)
    }

    @Test
    fun `semantic versions order by number, then pre-release`() {
        val ordered =
            listOf(
                "0.0.0-20180925071336-cf3bd585ca2a",
                "0.13.0",
                "1.0.0-alpha",
                "1.0.0-alpha.1",
                "1.0.0-beta.2",
                "1.0.0-beta.11",
                "1.0.0",
                "4.17.21",
                "v4.18.0",
            )
        assertEquals(ordered, ordered.shuffled().sortedWith(Versions::compareSemver))
    }

    @Test
    fun `an ecosystem whose rules aren't modeled compares only plain numbers`() {
        assertTrue(Versions.comparator(VersionScheme.NUMERIC, listOf("2.20.0", "2.31")) != null)
        assertNull(Versions.comparator(VersionScheme.NUMERIC, listOf("2.20.0", "2.0rc1")))
    }

    @Test
    fun `a version is affected between introduced and fixed, or up to last_affected`() {
        val order = Versions.comparator(VersionScheme.MAVEN, emptyList())!!
        val fixed = vuln("A", listOf("introduced" to "2.13.0", "fixed" to "2.15.0"))
        assertFalse(Versions.affects(fixed, "2.12.4", order))
        assertTrue(Versions.affects(fixed, "2.13.0", order))
        assertTrue(Versions.affects(fixed, "2.14.1", order))
        assertFalse(Versions.affects(fixed, "2.15.0", order))

        val last = vuln("B", listOf("introduced" to "0", "last_affected" to "1.2.17"))
        assertTrue(Versions.affects(last, "1.0", order))
        assertTrue(Versions.affects(last, "1.2.17", order))
        assertFalse(Versions.affects(last, "2.0", order))
    }

    @Test
    fun `the lowest version fixing all is above the one asked about and affected by none`() {
        // log4j-core's Log4Shell fixes, by branch.
        val vulns =
            listOf(
                vuln(
                    "CVE-2021-44228",
                    listOf("introduced" to "2.0-beta9", "fixed" to "2.3.1"),
                    listOf("introduced" to "2.4", "fixed" to "2.12.2"),
                    listOf("introduced" to "2.13.0", "fixed" to "2.15.0"),
                ),
                vuln(
                    "CVE-2021-45046",
                    listOf("introduced" to "2.0-beta9", "fixed" to "2.3.1"),
                    listOf("introduced" to "2.4", "fixed" to "2.12.2"),
                    listOf("introduced" to "2.13.0", "fixed" to "2.16.0"),
                ),
                vuln("CVE-2021-44832", listOf("introduced" to "2.13.0", "fixed" to "2.17.1")),
            )
        assertEquals(
            Versions.Fix.At("2.17.1"),
            Versions.lowestFixingAll(vulns, "2.14.1", VersionScheme.MAVEN),
        )
        assertEquals(
            Versions.Fix.At("2.3.1"),
            Versions.lowestFixingAll(vulns, null, VersionScheme.MAVEN),
        )
        assertEquals(
            Versions.Fix.None,
            Versions.lowestFixingAll(
                listOf(vuln("X", listOf("introduced" to "0", "last_affected" to "1.2.17"))),
                null,
                VersionScheme.MAVEN,
            ),
        )
        assertEquals(
            Versions.Fix.Unordered,
            Versions.lowestFixingAll(
                listOf(vuln("Y", listOf("introduced" to "0", "fixed" to "1.0.post1"))),
                null,
                VersionScheme.NUMERIC,
            ),
        )
    }

    @Test
    fun `CVSS 3 vectors give their base scores`() {
        assertEquals(10.0, Cvss.baseScore("CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:C/C:H/I:H/A:H"))
        assertEquals(9.0, Cvss.baseScore("CVSS:3.1/AV:N/AC:H/PR:N/UI:N/S:C/C:H/I:H/A:H"))
        assertEquals(6.6, Cvss.baseScore("CVSS:3.1/AV:N/AC:H/PR:H/UI:N/S:U/C:H/I:H/A:H"))
        assertEquals(5.3, Cvss.baseScore("CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:U/C:N/I:N/A:L"))
        assertEquals(0.0, Cvss.baseScore("CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:U/C:N/I:N/A:N"))
        assertNull(
            Cvss.baseScore("CVSS:4.0/AV:N/AC:L/AT:N/PR:N/UI:N/VC:N/VI:N/VA:N/SC:N/SI:L/SA:N"),
        )
        assertNull(Cvss.baseScore("AV:N/AC:L/Au:N/C:P/I:P/A:P"))
    }
}
