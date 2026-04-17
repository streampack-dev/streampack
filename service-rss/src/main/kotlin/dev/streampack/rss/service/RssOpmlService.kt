/* Joseph B. Ottinger (C)2026 */
package dev.streampack.rss.service

import com.rometools.opml.feed.opml.Opml
import com.rometools.opml.feed.opml.Outline
import dev.streampack.rss.model.AddFeedOutcome
import dev.streampack.rss.model.RssImportSummary
import dev.streampack.rss.repository.RssFeedRepository
import java.io.Reader
import java.io.StringReader
import java.net.URI
import javax.xml.parsers.DocumentBuilderFactory
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.w3c.dom.Element
import org.xml.sax.InputSource

/** Imports and exports registered RSS feeds as OPML or plain text URL lists. */
@Service
class RssOpmlService(
    private val feedRepository: RssFeedRepository,
    private val rssSubscriptionService: RssSubscriptionService,
) {
    private val logger = LoggerFactory.getLogger(RssOpmlService::class.java)

    /** Exports active feeds as OPML. */
    fun exportFeedsAsOpml(): String = tryExportWithRome() ?: exportFeedsAsOpmlFallback()

    /** Imports feed candidates from OPML or plain text. */
    fun importFeeds(input: String): RssImportSummary {
        if (input.isBlank()) return RssImportSummary(ignored = 1)
        val opmlUrls = parseOpmlUrls(input)
        return if (opmlUrls != null) {
            importCandidates(opmlUrls, ignored = 0)
        } else {
            val lines = input.lineSequence().map { it.trim() }.toList()
            val candidates = LinkedHashSet<String>()
            var ignored = 0
            for (line in lines) {
                if (line.isBlank()) continue
                if (URL_REGEX.matches(line)) {
                    candidates.add(line)
                } else {
                    ignored++
                }
            }
            importCandidates(candidates.toList(), ignored)
        }
    }

    private fun importCandidates(candidates: List<String>, ignored: Int): RssImportSummary {
        var added = 0
        var alreadyExisted = 0
        var discoveryFailed = 0

        for (candidate in LinkedHashSet(candidates)) {
            when (rssSubscriptionService.addFeed(candidate)) {
                is AddFeedOutcome.Added -> added++
                is AddFeedOutcome.AlreadyExists -> alreadyExisted++
                is AddFeedOutcome.DiscoveryFailed -> discoveryFailed++
            }
        }

        return RssImportSummary(
            added = added,
            alreadyExisted = alreadyExisted,
            discoveryFailed = discoveryFailed,
            ignored = ignored,
        )
    }

    private fun parseOpmlUrls(input: String): List<String>? {
        tryParseOpmlWithRome(input)?.let {
            return it
        }
        return try {
            val factory = DocumentBuilderFactory.newInstance()
            factory.isNamespaceAware = false
            val builder = factory.newDocumentBuilder()
            val document = builder.parse(InputSource(StringReader(input)))
            val root = document.documentElement ?: return null
            if (root.tagName.lowercase() != "opml") return null
            val outlines = document.getElementsByTagName("outline")
            buildList {
                for (index in 0 until outlines.length) {
                    val node = outlines.item(index)
                    if (node is Element) {
                        val xmlUrl = node.getAttribute("xmlUrl")?.trim().orEmpty()
                        if (xmlUrl.isNotBlank()) add(xmlUrl)
                    }
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun tryExportWithRome(): String? {
        return try {
            val opml = Opml()
            opml.feedType = "opml_2.0"
            opml.title = "Streampack RSS Feeds"
            val outlines =
                feedRepository
                    .findAllByActiveTrue()
                    .sortedBy { it.title.lowercase() }
                    .map { feed ->
                        val thing =
                            Outline(
                                feed.title,
                                URI.create(feed.feedUrl).toURL(),
                                feed.siteUrl
                                    ?.takeIf { it.isNotBlank() }
                                    ?.let { URI.create(it).toURL() },
                            )
                        thing.type = "rss"
                        thing.text = feed.title
                        thing
                    }

            opml.outlines = outlines
            val wireFeedOutput = com.rometools.rome.io.WireFeedOutput()
            wireFeedOutput.outputString(opml)
        } catch (e: Exception) {
            logger.warn("ROME OPML export unavailable, falling back to manual XML: {}", e.message)
            null
        }
    }

    private fun exportFeedsAsOpmlFallback(): String {
        val outlines =
            feedRepository
                .findAllByActiveTrue()
                .sortedBy { it.title.lowercase() }
                .joinToString("\n") { feed ->
                    buildString {
                        append("    <outline type=\"rss\" ")
                        append("text=\"${xml(feed.title)}\" ")
                        append("title=\"${xml(feed.title)}\" ")
                        append("xmlUrl=\"${xml(feed.feedUrl)}\"")
                        feed.siteUrl
                            ?.takeIf { it.isNotBlank() }
                            ?.let { append(" htmlUrl=\"${xml(it)}\"") }
                        append(" />")
                    }
                }

        return buildString {
            appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
            appendLine("""<opml version="2.0">""")
            appendLine("""  <head>""")
            appendLine("""    <title>Streampack RSS Feeds</title>""")
            appendLine("""  </head>""")
            appendLine("""  <body>""")
            if (outlines.isNotBlank()) appendLine(outlines)
            appendLine("""  </body>""")
            append("""</opml>""")
        }
    }

    private fun tryParseOpmlWithRome(input: String): List<String>? {
        return try {
            val wireFeedInput = com.rometools.rome.io.WireFeedInput()
            val feed = wireFeedInput.build(StringReader(input) as Reader)
            if (feed !is Opml) return null

            feed.outlines.orEmpty().mapNotNull { it.xmlUrl?.takeIf(String::isNotBlank) }
        } catch (e: Exception) {
            logger.debug("ROME OPML parse unavailable or failed: {}", e.message)
            null
        }
    }

    private fun xml(value: String): String =
        value
            .replace("&", "&amp;")
            .replace("\"", "&quot;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")

    private companion object {
        val URL_REGEX = Regex("""https?://\S+""")
    }
}
