/* Joseph B. Ottinger (C)2026 */
package dev.streampack.rss.service

import com.rometools.rome.feed.synd.SyndFeed
import com.rometools.rome.io.SyndFeedInput
import java.io.StringReader

/** Feeds whose entries carry their own tags (#139), parsed in memory: no server, no timing. */
object FeedTagSamples {
    /** An RSS 2.0 feed at [site]: each item `guid` with its `<category>` elements. */
    fun rss(site: String, items: Map<String, List<String>>): String =
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <rss version="2.0">
            <channel>
                <title>Feed at $site</title>
                <link>$site</link>
                <description>A test feed</description>
                ${items.entries.joinToString("\n") { (guid, cats) ->
                    """
                    <item>
                        <title>Entry $guid</title>
                        <link>$site/$guid</link>
                        <guid>$site/$guid</guid>
                        <pubDate>Mon, 01 Jan 2026 00:00:00 GMT</pubDate>
                        ${cats.joinToString("") { "<category>$it</category>" }}
                    </item>
                    """
                }}
            </channel>
        </rss>
        """
            .trimIndent()

    /** An Atom feed at [site]: each entry `id` with its `<category term>` elements. */
    fun atom(site: String, entries: Map<String, List<String>>): String =
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <feed xmlns="http://www.w3.org/2005/Atom">
            <title>Atom at $site</title>
            <link href="$site"/>
            <id>$site</id>
            <updated>2026-01-01T00:00:00Z</updated>
            ${entries.entries.joinToString("\n") { (id, cats) ->
                """
                <entry>
                    <title>Entry $id</title>
                    <link href="$site/$id"/>
                    <id>$site/$id</id>
                    <updated>2026-01-01T00:00:00Z</updated>
                    ${cats.joinToString("") { "<category term=\"$it\" label=\"Label $it\"/>" }}
                </entry>
                """
            }}
        </feed>
        """
            .trimIndent()

    fun parse(xml: String): SyndFeed = SyndFeedInput().build(StringReader(xml))
}
