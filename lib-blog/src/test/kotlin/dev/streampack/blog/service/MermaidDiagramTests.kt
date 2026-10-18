/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.service

import java.time.Duration
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Mermaid blocks drawn as SVG with Pele, and kept safe by the sanitizer (#252) */
class MermaidDiagramTests {
    private val sanitizer = RenderedHtmlSanitizer()
    private val markdown =
        MarkdownRenderingService(
            ExcerptSummarizerService(),
            renderedHtmlSanitizer = sanitizer,
            mermaidRenderer = renderer,
        )

    private fun mermaid(source: String) =
        "Before.\n\n```mermaid\n${source.trimIndent()}\n```\n\nAfter.\n"

    private fun figure(html: String): Element {
        val figure = Jsoup.parseBodyFragment(html).selectFirst("figure.mermaid")
        assertNotNull(figure, html)
        return figure!!
    }

    private fun assertDrawn(source: String, kind: String): Element {
        val html = markdown.render(mermaid(source))
        val figure = figure(html)
        val svg = figure.child(0)
        assertEquals("svg", svg.normalName(), html)
        assertEquals(kind, svg.attr("aria-roledescription"), html)
        assertTrue(svg.hasAttr("aria-label") || svg.hasAttr("aria-labelledby"), html)
        val details = figure.child(1)
        assertEquals("details", details.normalName(), html)
        assertEquals(MermaidFigures.SOURCE_SUMMARY, details.selectFirst("summary")!!.text())
        val code = details.selectFirst("pre > code.language-mermaid")!!
        assertEquals(source.trimIndent() + "\n", code.wholeText())
        assertTrue(html.startsWith("<p>Before.</p>") && html.endsWith("<p>After.</p>"), html)
        return svg
    }

    @Test
    fun `a flowchart, a sequence diagram and a chart are drawn, with their source kept`() {
        val flow =
            assertDrawn("flowchart LR\n  A[Start] --> B{OK?}\n  B -->|yes| C[Ship]", "flowchart")
        assertEquals("Diagram (flowchart)", flow.attr("aria-label"))
        assertTrue(flow.text().contains("Ship"), flow.outerHtml())
        assertDrawn("sequenceDiagram\n  Alice->>Bob: Hello\n  Bob-->>Alice: Hi", "sequence")
        assertDrawn("pie title Pets\n  \"Dogs\" : 386\n  \"Cats\" : 85", "pie")
    }

    @Test
    fun `an author's accTitle labels the diagram instead`() {
        val svg =
            assertDrawn(
                "flowchart TD\n  accTitle: Release flow\n  accDescr: How a change ships\n  A --> B",
                "flowchart",
            )
        assertFalse(svg.hasAttr("aria-label"), svg.outerHtml())
        val title = svg.getElementById(svg.attr("aria-labelledby"))
        assertEquals("Release flow", title?.text(), svg.outerHtml())
    }

    @Test
    fun `colours come from theme variables, not fixed values`() {
        val svg = assertDrawn("flowchart LR\n  A --> B", "flowchart")
        val fills = svg.select("[fill]").map { it.attr("fill") }.filter { it != "none" }
        assertTrue(fills.isNotEmpty())
        assertTrue(fills.all { it.startsWith("var(--") }, fills.toString())
    }

    @Test
    fun `a diagram that won't parse keeps its source and says why`() {
        val html = markdown.render(mermaid("flowchart LR\n  A -->"))
        val figure = figure(html)
        assertTrue(figure.hasClass("mermaid-refused"), html)
        assertNull(figure.selectFirst("svg"), html)
        assertEquals("flowchart LR\n  A -->\n", figure.selectFirst("pre > code")!!.wholeText())
        val caption = figure.selectFirst("figcaption")!!.text()
        assertTrue(
            Regex("""This diagram wasn't drawn: Parse error on line \d+: Expecting .+""")
                .matches(caption),
            caption,
        )
    }

    @Test
    fun `diagram source can't put script, handlers, links or outside references in the page`() {
        val html =
            markdown.render(
                mermaid(
                    """
                    flowchart LR
                      A["<script>alert(1)</script><img src=x onerror=alert(1)>"] --> B
                      B --> C["<a href='https://evil.example'>x</a>"]
                      click A "javascript:alert(1)"
                      click B "https://evil.example/track"
                    """,
                ),
            )
        val svg = figure(html).selectFirst("svg")
        assertNotNull(svg, html)
        val drawn = svg!!.outerHtml()
        for (unsafe in listOf("<script", "onerror", "javascript:", "href", "<a ", "<img", "url(")) {
            assertFalse(drawn.contains(unsafe, ignoreCase = true), "$unsafe in $drawn")
        }
    }

    @Test
    fun `other code blocks are left alone`() {
        val html = markdown.render("```kotlin\nval x = 1\n```\n")
        assertEquals("<pre><code class=\"language-kotlin\">val x = 1\n</code></pre>", html)
    }

    @Test
    fun `two diagrams on a page don't share element ids`() {
        val diagram = "flowchart LR\n  accTitle: Same\n  A --> B"
        val html = markdown.render(mermaid(diagram) + "\n" + mermaid(diagram))
        val ids = Jsoup.parseBodyFragment(html).select("figure.mermaid [id]").map { it.id() }
        assertEquals(2, ids.size, html)
        assertEquals(ids.toSet().size, ids.size, ids.toString())
    }

    @Test
    fun `sanitizing a rendered diagram again changes nothing`() {
        val html =
            markdown.render(
                mermaid("flowchart LR\n  A --> B") + mermaid("flowchart LR\n  A -->") + "# End\n",
            )
        assertEquals(html, sanitizer.sanitize(html))
    }

    @Test
    fun `a stored figure is rebuilt, and only the drawing and its source survive`() {
        val stored =
            """<figure class="mermaid"><svg xmlns="http://www.w3.org/2000/svg" onload="steal()" viewBox="0 0 10 10">""" +
                """<a href="https://evil.example"><text x="1" y="1">t</text></a>""" +
                """<image href="https://evil.example/pixel.png"/><script>steal()</script>""" +
                """<use href="https://evil.example/sprite.svg#x"/>""" +
                """<rect width="5" height="5" style="fill:url(https://evil.example/x)"/>""" +
                """<rect width="4" height="4" style="position:fixed"/>""" +
                """<rect width="3" height="3" fill="var(--pele-accent)"/></svg>""" +
                """<iframe src="https://evil.example"></iframe><p onclick="x()">extra</p>""" +
                """<details><summary>Diagram source</summary><pre><code class="language-mermaid">A --&gt; B</code></pre></details></figure>"""
        val html = sanitizer.sanitize(stored)
        for (unsafe in
            listOf(
                "onload",
                "evil",
                "<script",
                "<image",
                "<use",
                "url(",
                "position",
                "iframe",
                "onclick",
                "extra",
            )) {
            assertFalse(html.contains(unsafe, ignoreCase = true), "$unsafe in $html")
        }
        val figure = figure(html)
        assertEquals(3, figure.select("svg rect").size, html)
        assertEquals("t", figure.selectFirst("svg text")!!.text(), html)
        assertEquals("A --> B", figure.selectFirst("details pre > code")!!.wholeText())
        assertEquals(html, sanitizer.sanitize(html))
    }

    @Test
    fun `a stored figure with no source is dropped, and one with no drawing stays code`() {
        assertEquals(
            "<p>a</p><p>b</p>",
            sanitizer.sanitize(
                """<p>a</p><figure class="mermaid"><svg><text>x</text></svg></figure><p>b</p>""",
            ),
        )
        assertEquals(
            """<pre><code class="language-mermaid">A --&gt; B</code></pre>""",
            sanitizer.sanitize(
                """<figure class="mermaid"><pre><code class="language-mermaid">A --&gt; B</code></pre></figure>""",
            ),
        )
    }

    @Test
    fun `source over Pele's limit is refused, not drawn`() {
        val big = "flowchart LR\n" + (1..2_000).joinToString("\n") { "  n$it --> n${it + 1}" }
        assertTrue(big.length > MermaidRenderer.SOURCE_LIMIT)
        val figure = figure(markdown.render(mermaid(big)))
        assertTrue(figure.hasClass("mermaid-refused"), figure.outerHtml().take(300))
    }

    @Test
    fun `only the first fifty diagrams in a post are drawn`() {
        val post =
            (1..MarkdownRenderingService.MAX_DIAGRAMS + 1).joinToString("\n") {
                "```mermaid\nflowchart LR\n  A$it --> B$it\n```\n"
            }
        val doc = Jsoup.parseBodyFragment(markdown.render(post))
        assertEquals(MarkdownRenderingService.MAX_DIAGRAMS, doc.select("figure.mermaid svg").size)
        assertEquals(1, doc.select("body > pre > code.language-mermaid").size)
    }

    @Test
    fun `a render that runs too long is cancelled and refused`() {
        val slow = MermaidRenderer(enabled = true, timeout = Duration.ofMillis(1))
        try {
            val outcome = slow.render("flowchart LR\n  A --> B", "slow-")
            assertInstanceOf(MermaidRenderer.Outcome.Refused::class.java, outcome)
            assertTrue(
                (outcome as MermaidRenderer.Outcome.Refused).message.startsWith("It took longer"),
            )
        } finally {
            slow.close()
        }
    }

    @Test
    fun `turned off, Mermaid blocks stay code`() {
        val off = MermaidRenderer(enabled = false)
        val html =
            MarkdownRenderingService(ExcerptSummarizerService(), mermaidRenderer = off)
                .render("```mermaid\nflowchart LR\n  A --> B\n```\n")
        assertEquals(
            MermaidRenderer.Outcome.Unavailable,
            off.render("flowchart LR\n A --> B", "x-"),
        )
        assertEquals(
            "<pre><code class=\"language-mermaid\">flowchart LR\n  A --&gt; B\n</code></pre>",
            html,
        )
    }

    @Test
    fun `labels are measured in the diagram font, which the SVG names`() {
        val measurer = MermaidTextMeasurer.load()
        assertNotNull(measurer, "the bundled IBM Plex faces should load")
        val m = measurer!!
        assertTrue(m.width("Release", 16.0, 1) > m.width("Release", 16.0, 0), "bold is wider")
        assertTrue(m.width("AV", 16.0, 0) < m.width("A", 16.0, 0) + m.width("V", 16.0, 0), "kerned")
        assertEquals(m.width("iiii", 16.0, 4), m.width("WWWW", 16.0, 4), 0.001, "monospace")
        assertTrue(m.width("漢字", 16.0, 0) > 16.0, "a glyph Plex lacks falls back, not zero")
        assertEquals(0.0, m.width("", 16.0, 0))
        // A private-use character no font has: Pele's own estimate, not a missing glyph's box
        assertEquals(16.0 * 0.58, m.width("\uE000", 16.0, 0), 0.001)
        assertEquals(m.width("ab", 16.0, 0) + 16.0 * 0.58, m.width("a\uE000b", 16.0, 0), 0.5)

        val svg = assertDrawn("flowchart LR\n  A[WAVE AVAILABLE] --> B[iiii]", "flowchart")
        assertTrue(svg.attr("style").contains("IBM Plex Sans"), svg.attr("style"))
        assertTrue(svg.attr("style").contains("IBM Plex Mono"), svg.attr("style"))
    }

    @Test
    fun `without the measurer, Pele's table sizes the boxes and the SVG names no font`() {
        val table = MermaidRenderer(measure = false)
        try {
            val diagram = "flowchart LR\n  A[WAVE AVAILABLE]"
            val estimated = table.render(diagram, "t-") as MermaidRenderer.Outcome.Drawn
            val measured = renderer.render(diagram, "m-") as MermaidRenderer.Outcome.Drawn
            assertFalse(estimated.svg.contains("IBM Plex"), estimated.svg.take(400))
            fun width(svg: String) = Regex("""viewBox="0 0 ([\d.]+) """).find(svg)!!.groupValues[1]
            assertTrue(width(estimated.svg) != width(measured.svg), "the measurer changes the box")
        } finally {
            table.close()
        }
    }

    @Test
    fun `a diagram's source stays out of the excerpt`() {
        val excerpt = markdown.excerpt(mermaid("flowchart LR\n  accTitle: Ship it\n  A --> B"))
        assertEquals("Before. After.", excerpt)
    }

    companion object {
        /** One engine for the class: it takes about a second to start */
        private val renderer = MermaidRenderer()

        @JvmStatic @AfterAll fun closeRenderer() = renderer.close()
    }
}
