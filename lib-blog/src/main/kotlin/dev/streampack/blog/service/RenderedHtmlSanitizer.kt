/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.service

import org.jsoup.Jsoup
import org.jsoup.nodes.Attribute
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.safety.Safelist
import org.springframework.stereotype.Component

/**
 * Sanitizes rendered HTML before it is stored or served.
 *
 * Markdown rendering escapes raw HTML in the source, but the renderer itself emits HTML from
 * markdown syntax, and link and image destinations are copied through verbatim. flexmark suppresses
 * `javascript:` destinations only by a case-sensitive match, so `JavaScript:` and `data:` URLs
 * reach stored `renderedHtml`, which the UI and SSR pages inject as raw HTML. This safelist keeps
 * the elements and attributes the flexmark extensions in [MarkdownRenderingService] produce and
 * drops everything else, including any URL whose scheme is not http, https, or mailto. Relative
 * URLs and fragment links are kept.
 *
 * Author-supplied raw HTML never reaches this class unescaped, so the SVG allowance below only
 * admits the icon symbol sheet the admonition extension generates.
 */
@Component
class RenderedHtmlSanitizer {

    private val safelist: Safelist =
        IconReferences(Safelist.relaxed())
            // Structural and inline elements emitted by flexmark extensions that relaxed() omits.
            .addTags("hr", "del", "s", "ins", "aside", "input", "section", "details", "summary")
            // Task list checkboxes; raw <input> from authors is already escaped upstream.
            .addAttributes("input", "type", "checked", "disabled", "readonly", "aria-label")
            // Footnotes, admonitions, task lists, and fenced code rely on class and id hooks.
            .addAttributes(":all", "class", "id")
            .addAttributes("a", "rel", "target")
            .addAttributes("th", "align")
            .addAttributes("td", "align")
            // Admonition icon sheet: <svg class="adm-hidden"><symbol id="adm-note"><path d=".."/>
            // and per-block <svg class="adm-icon"><use xlink:href="#adm-note"/>.
            .addTags("svg", "symbol", "path", "use")
            .addAttributes("svg", "xmlns", "viewBox", "viewbox", "width", "height", "aria-hidden")
            .addAttributes("symbol", "viewBox", "viewbox")
            .addAttributes("path", "d", "fill", "stroke", "stroke-width")
            // Checked by IconReferences: same-document #adm-* fragments only (#95).
            .addAttributes("use", "href", "xlink:href")
            .removeProtocols("a", "href", "ftp")
            .addProtocols("a", "href", "http", "https", "mailto", "#")
            .addProtocols("img", "src", "http", "https")
            .preserveRelativeLinks(true)

    private val outputSettings: Document.OutputSettings =
        Document.OutputSettings().prettyPrint(false)

    /** Returns [html] with disallowed elements, attributes, and URL schemes removed. */
    fun sanitize(html: String): String {
        if (html.isBlank()) return ""
        return Jsoup.clean(html, RELATIVE_BASE, safelist, outputSettings).trim()
    }

    /**
     * The safelist, admitting a `<use>` reference only when it names one of the admonition sprite's
     * symbols in the same document. jsoup's protocol check resolves the value against the base
     * first, so a `#` protocol never matched and every icon was emptied (#95); this checks the
     * value as written instead, and refuses anything else, other fragments and external sheets
     * included.
     */
    private class IconReferences(base: Safelist) : Safelist(base) {
        override fun isSafeAttribute(tagName: String, el: Element, attr: Attribute): Boolean {
            if (tagName == "use" && (attr.key == "href" || attr.key == "xlink:href")) {
                return ICON.matches(attr.value)
            }
            return super.isSafeAttribute(tagName, el, attr)
        }
    }

    companion object {
        private val ICON = Regex("""#adm-[a-z0-9-]+""")

        /**
         * jsoup resolves relative URLs against a base to test their scheme and drops them when no
         * base is given. With preserveRelativeLinks the original relative value is what gets kept,
         * so this base never appears in output.
         */
        private const val RELATIVE_BASE = "https://relative.invalid/"
    }
}
