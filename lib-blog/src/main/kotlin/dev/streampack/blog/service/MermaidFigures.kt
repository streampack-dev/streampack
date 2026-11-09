/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.service

import org.jsoup.Jsoup
import org.jsoup.nodes.Attribute
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.safety.Safelist

/**
 * The HTML a Mermaid block becomes (#252), built in one place for the renderer and the sanitizer:
 *
 * - Drawn: `<figure class="mermaid">`, the SVG, then the source in a closed `<details>`.
 * - Refused: `<figure class="mermaid mermaid-refused">`, the source as a code block, and a
 *   `<figcaption>` saying why it wasn't drawn.
 *
 * The sanitizer rebuilds every figure from its parts (the SVG through [cleanSvg], the source and
 * the caption as text), so stored HTML can't carry anything else inside one, and running it again
 * changes nothing.
 */
object MermaidFigures {
    const val FIGURE = "figure.mermaid"
    const val SOURCE_SUMMARY = "Diagram source"

    /** A drawn diagram: [svg] already through [cleanSvg] */
    fun drawn(svg: Element, source: String): Element =
        Element("figure").addClass("mermaid").appendChild(svg).appendChild(sourceDetails(source))

    /** A diagram Pele refused, with [reason] for the author and reader */
    fun refused(source: String, reason: String): Element =
        Element("figure")
            .addClass("mermaid")
            .addClass("mermaid-refused")
            .appendChild(codeBlock(source))
            .appendChild(Element("figcaption").text("This diagram wasn't drawn: $reason"))

    /** The fenced block as flexmark writes it, for a diagram that wasn't attempted */
    fun codeBlock(source: String): Element =
        Element("pre").appendChild(Element("code").addClass("language-mermaid").text(source))

    private fun sourceDetails(source: String): Element =
        Element("details")
            .appendChild(Element("summary").text(SOURCE_SUMMARY))
            .appendChild(codeBlock(source))

    /**
     * [figure] rebuilt from its parts, or null when it has no source to rebuild from. The SVG is
     * cleaned; a figure whose SVG cleans to nothing keeps its source as a code block.
     */
    fun rebuild(figure: Element): Element? {
        val code = figure.selectFirst("pre > code") ?: return null
        val source = code.wholeText()
        val svg = figure.selectFirst("svg")?.let { cleanSvg(it.outerHtml()) }
        if (svg != null) return drawn(svg, source)
        val caption = figure.selectFirst("figcaption")?.text()
        val reason = caption?.removePrefix("This diagram wasn't drawn: ")
        return if (reason.isNullOrBlank()) codeBlock(source) else refused(source, reason)
    }

    /**
     * [svg] with only what Pele draws: shapes, text and their presentation, no links, images,
     * scripts, event handlers or references outside the diagram. Returns null when nothing is left.
     * The diagram has no fixed label unless its author gave one (`accTitle`), so one names its
     * kind, as Pele's `aria-roledescription` gives it.
     */
    fun cleanSvg(svg: String): Element? {
        val cleaned = Jsoup.clean(svg, BASE, SvgSafelist, output)
        val doc = Jsoup.parseBodyFragment(cleaned)
        doc.outputSettings(output)
        val root = doc.body().children().singleOrNull()?.takeIf { it.normalName() == "svg" }
        root ?: return null
        if (!root.hasAttr("aria-labelledby") && !root.hasAttr("aria-label")) {
            val kind = root.attr("aria-roledescription").ifBlank { "diagram" }
            root.attr("aria-label", "Diagram ($kind)")
        }
        return root
    }

    private val output = Document.OutputSettings().prettyPrint(false)

    /** Never appears in output: jsoup needs a base to check relative values against */
    private const val BASE = "https://relative.invalid/"

    /** Values no SVG attribute here may hold: outside references, script, escapes */
    private val UNSAFE_VALUE =
        Regex(
            """url\s*\(|javascript:|vbscript:|data:|expression\s*\(|@import|\\|<""",
            RegexOption.IGNORE_CASE,
        )

    /**
     * A paint that starts with a reference to a gradient (Pele's Sankey links, since 1.9:
     * `url(#d1-sankey-…) var(--pele-series-1,…)`, the variable its fallback)
     */
    private val GRADIENT_PAINT = Regex("""^url\(#([A-Za-z0-9_-]+)\)""")

    /**
     * [value] without its leading gradient reference, when [attribute] is a paint and the reference
     * names a gradient inside the same drawing as [el]; otherwise [value] as it is. What's left
     * still has to pass [UNSAFE_VALUE], so a paint can point at nothing outside its own diagram.
     */
    private fun withoutOwnGradient(el: Element, attribute: String, value: String): String {
        if (attribute != "fill" && attribute != "stroke") return value
        val reference = GRADIENT_PAINT.find(value) ?: return value
        val drawing = el.parents().lastOrNull { it.normalName() == "svg" } ?: return value
        val target = drawing.getElementById(reference.groupValues[1]) ?: return value
        if (target.normalName() != "lineargradient") return value
        return value.substring(reference.range.last + 1)
    }

    /** The CSS properties Pele writes inline; custom properties (`--…`) are its theme hooks */
    private val STYLE_PROPERTIES =
        setOf(
            "color",
            "direction",
            "fill",
            "fill-opacity",
            "font",
            "font-family",
            "font-size",
            "font-style",
            "font-weight",
            "height",
            "letter-spacing",
            "max-width",
            "opacity",
            "rx",
            "ry",
            "stroke",
            "stroke-dasharray",
            "stroke-dashoffset",
            "stroke-opacity",
            "stroke-width",
            "text-decoration",
            "text-transform",
            "width",
            "word-spacing",
        )

    private val CUSTOM_PROPERTY = Regex("""--[A-Za-z0-9_-]+""")

    private fun safeStyle(value: String): Boolean =
        value.split(';').map(String::trim).filter(String::isNotEmpty).all { declaration ->
            val property = declaration.substringBefore(':', "").trim().lowercase()
            property in STYLE_PROPERTIES || CUSTOM_PROPERTY.matches(property)
        }

    private val PRESENTATION =
        arrayOf(
            "baseline-shift",
            "class",
            "data-id",
            "data-icon",
            "fill",
            "fill-opacity",
            "fill-rule",
            "font-family",
            "font-size",
            "font-style",
            "font-weight",
            "opacity",
            "stroke",
            "stroke-dasharray",
            "stroke-linecap",
            "stroke-linejoin",
            "stroke-opacity",
            "stroke-width",
            "style",
            "text-anchor",
            "text-decoration",
            "dominant-baseline",
            "transform",
        )

    /**
     * What Pele 1.13 writes across every diagram type, and nothing that links or loads. A paint may
     * point only at a gradient in its own drawing ([withoutOwnGradient]).
     */
    private object SvgSafelist :
        Safelist(
            Safelist.none()
                .addTags(
                    "svg",
                    "g",
                    "title",
                    "desc",
                    "rect",
                    "circle",
                    "ellipse",
                    "line",
                    "polyline",
                    "polygon",
                    "path",
                    "text",
                    "tspan",
                    "defs",
                    "linearGradient",
                    "stop",
                )
                .addAttributes(":all", *PRESENTATION)
                .addAttributes(
                    "svg",
                    "xmlns",
                    "viewBox",
                    "width",
                    "height",
                    "x",
                    "y",
                    "role",
                    "aria-label",
                    "aria-labelledby",
                    "aria-describedby",
                    "aria-roledescription",
                )
                .addAttributes("title", "id")
                .addAttributes("desc", "id")
                .addAttributes("rect", "x", "y", "width", "height", "rx", "ry")
                .addAttributes("circle", "cx", "cy", "r")
                .addAttributes("ellipse", "cx", "cy", "rx", "ry")
                .addAttributes("line", "x1", "y1", "x2", "y2")
                .addAttributes("polyline", "points")
                .addAttributes("polygon", "points")
                .addAttributes("path", "d")
                .addAttributes("text", "x", "y", "dx", "dy", "xml:space")
                .addAttributes("tspan", "x", "y", "dx", "dy")
                .addAttributes("linearGradient", "id", "gradientUnits", "x1", "y1", "x2", "y2")
                .addAttributes("stop", "offset", "stop-color", "stop-opacity"),
        ) {
        override fun isSafeAttribute(tagName: String, el: Element, attr: Attribute): Boolean {
            if (!super.isSafeAttribute(tagName, el, attr)) return false
            if (UNSAFE_VALUE.containsMatchIn(withoutOwnGradient(el, attr.key, attr.value))) {
                return false
            }
            return attr.key != "style" || safeStyle(attr.value)
        }
    }
}
