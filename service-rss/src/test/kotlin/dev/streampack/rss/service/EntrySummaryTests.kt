/* Joseph B. Ottinger (C)2026 */
package dev.streampack.rss.service

import com.rometools.rome.feed.synd.SyndEntry
import com.rometools.rome.io.SyndFeedInput
import java.io.StringReader
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** An entry's summary as plain text, from what its feed says about it (#98). */
class EntrySummaryTests {
    private fun rssEntry(item: String): SyndEntry =
        SyndFeedInput()
            .build(
                StringReader(
                    """<?xml version="1.0"?><rss version="2.0"><channel><title>F</title>
                    <link>http://example.com</link><description>d</description>$item</channel></rss>"""
                )
            )
            .entries
            .single()

    private fun atomEntry(entry: String): SyndEntry =
        SyndFeedInput()
            .build(
                StringReader(
                    """<?xml version="1.0"?><feed xmlns="http://www.w3.org/2005/Atom"><title>F</title>
                    <id>urn:f</id><updated>2026-01-01T00:00:00Z</updated>$entry</feed>"""
                )
            )
            .entries
            .single()

    @Test
    fun `an RSS description's HTML becomes plain text`() {
        val entry =
            rssEntry(
                """<item><title>T</title><link>http://e/1</link><description><![CDATA[
                <p>Hello <b>world</b> &amp; more.</p>
                <p>Second   paragraph.</p>]]></description></item>"""
            )

        assertEquals("Hello world & more. Second paragraph.", EntrySummary.of(entry))
    }

    @Test
    fun `an Atom summary is used, else its content`() {
        val summarised =
            atomEntry(
                """<entry><title>T</title><id>urn:1</id><updated>2026-01-01T00:00:00Z</updated>
                <summary>The short version.</summary><content type="html">&lt;p&gt;The long version.&lt;/p&gt;</content></entry>"""
            )
        assertEquals("The short version.", EntrySummary.of(summarised))

        val contentOnly =
            atomEntry(
                """<entry><title>T</title><id>urn:2</id><updated>2026-01-01T00:00:00Z</updated>
                <content type="html">&lt;p&gt;Only the &lt;em&gt;content&lt;/em&gt;.&lt;/p&gt;</content></entry>"""
            )
        assertEquals("Only the content.", EntrySummary.of(contentOnly))
    }

    @Test
    fun `nothing to say is no summary`() {
        assertNull(
            EntrySummary.of(rssEntry("<item><title>T</title><link>http://e/1</link></item>"))
        )
        assertNull(
            EntrySummary.of(
                rssEntry(
                    "<item><title>T</title><link>http://e/1</link><description>  <p> </p> </description></item>"
                )
            )
        )
        // A description that only repeats the title says nothing more.
        assertNull(
            EntrySummary.of(
                rssEntry(
                    "<item><title>Same words</title><link>http://e/1</link><description>Same words</description></item>"
                )
            )
        )
    }

    @Test
    fun `a long summary is cut at a word, within the column`() {
        val words = "word ".repeat(300).trim()
        val summary =
            EntrySummary.of(
                rssEntry(
                    "<item><title>T</title><link>http://e/1</link><description>$words</description></item>"
                )
            )!!

        assertTrue(summary.length <= EntrySummary.MAX_LENGTH, "${summary.length}")
        assertTrue(summary.endsWith("..."), summary)
        assertTrue(summary.removeSuffix("...").split(" ").all { it == "word" }, summary)
    }
}
