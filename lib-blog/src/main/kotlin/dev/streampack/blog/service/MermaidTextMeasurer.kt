/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.service

import java.awt.Font
import java.awt.font.FontRenderContext
import java.awt.font.TextAttribute
import java.awt.font.TextLayout
import org.graalvm.polyglot.HostAccess
import org.slf4j.LoggerFactory

/**
 * Label widths from the diagram font's own metrics (#252), as Pele's `TextMeasurer`.
 *
 * Without one, Pele estimates widths from a table of Arial's ASCII advances, with no kerning and a
 * flat width for everything else, so labels in another font can touch or overrun their boxes.
 * Diagrams are drawn in IBM Plex Sans and IBM Plex Mono, bundled here and named in the SVG, and
 * measured with Java 2D's [TextLayout] in those fonts. Front ends show diagrams in the same fonts.
 *
 * Three things Java 2D does differently from a browser, each handled:
 * - A font loaded from a file has one face; asking it for bold synthesizes bold and its metrics. So
 *   each style is its own file: regular, bold, italic and bold italic.
 * - A loaded font doesn't fall back for glyphs it lacks, as a CSS font stack does. A label it can't
 *   display is measured character by character, as a browser's font stack does: what Plex has in
 *   Plex, the rest in the logical `SansSerif` or `Monospaced` font, which falls back. Characters no
 *   installed font has (CJK, in the server's image, which has only DejaVu) get Pele's own estimate:
 *   an em for CJK, 0.58 em for anything else. A missing glyph's box would be too narrow.
 * - Kerning and ligatures are off by default; browsers have them on, and measure in fractions of a
 *   pixel. Both are turned on here.
 */
class MermaidTextMeasurer
private constructor(
    private val sans: List<Font>,
    private val mono: List<Font>,
) {
    private val context = FontRenderContext(null, true, true)
    private val logicalSans = Font(Font.SANS_SERIF, Font.PLAIN, 1).deriveFont(TEXT)
    private val logicalMono = Font(Font.MONOSPACED, Font.PLAIN, 1).deriveFont(TEXT)

    /** [text]'s advance at [size] pixels. [style] is Pele's: bit 1 bold, 2 italic, 4 monospace. */
    @HostAccess.Export
    fun width(text: String, size: Double, style: Int): Double {
        if (text.isEmpty()) return 0.0
        val monospace = style and MONOSPACE != 0
        val face = style and (BOLD or ITALIC)
        val font = (if (monospace) mono else sans)[face].deriveFont(size.toFloat())
        if (font.canDisplayUpTo(text) < 0) return TextLayout(text, font, context).advance.toDouble()
        val awtStyle =
            (if (face and BOLD != 0) Font.BOLD else 0) or
                (if (face and ITALIC != 0) Font.ITALIC else 0)
        val fallback =
            (if (monospace) logicalMono else logicalSans).deriveFont(awtStyle, size.toFloat())
        return advance(text, listOf(font, fallback), size)
    }

    /**
     * [text] as a browser's font stack would draw it: each character in the first of [fonts] that
     * has it, each run measured in its font, and a character none has estimated.
     */
    private fun advance(text: String, fonts: List<Font>, size: Double): Double {
        var total = 0.0
        val run = StringBuilder()
        var runFont: Font? = null
        fun flush() {
            if (run.isNotEmpty()) total += TextLayout(run.toString(), runFont, context).advance
            run.setLength(0)
        }
        text.codePoints().forEach { point ->
            val font = fonts.firstOrNull { it.canDisplay(point) }
            if (font != runFont) flush()
            runFont = font
            if (font == null) total += size * if (isWide(point)) 1.0 else 0.58
            else run.appendCodePoint(point)
        }
        flush()
        return total
    }

    /** CJK and other full-width characters, an em wide, as Pele's table has them */
    private fun isWide(point: Int): Boolean =
        when (Character.UnicodeScript.of(point)) {
            Character.UnicodeScript.HAN,
            Character.UnicodeScript.HIRAGANA,
            Character.UnicodeScript.KATAKANA,
            Character.UnicodeScript.HANGUL,
            Character.UnicodeScript.BOPOMOFO -> true
            else -> point in 0xFF00..0xFFEF || point in 0x3000..0x303F
        }

    companion object {
        private val logger = LoggerFactory.getLogger(MermaidTextMeasurer::class.java)

        const val SANS_FAMILY = "IBM Plex Sans"
        const val MONO_FAMILY = "IBM Plex Mono"

        /** Pele's style bits */
        private const val BOLD = 1
        private const val ITALIC = 2
        private const val MONOSPACE = 4

        private const val FONTS = "/fonts/ibm-plex/"

        /** Each family's faces, in the order Pele's bold and italic bits index them */
        private val FACES = listOf("Regular", "Bold", "Italic", "BoldItalic")

        private val TEXT: Map<TextAttribute, Any> =
            mapOf(
                TextAttribute.KERNING to TextAttribute.KERNING_ON,
                TextAttribute.LIGATURES to TextAttribute.LIGATURES_ON,
            )

        /** The measurer, or null when the fonts can't be loaded (logged): Pele's table then */
        fun load(): MermaidTextMeasurer? =
            try {
                MermaidTextMeasurer(faces("IBMPlexSans"), faces("IBMPlexMono"))
            } catch (e: Throwable) {
                logger.error(
                    "Mermaid diagrams: the diagram fonts didn't load; label widths are estimated",
                    e,
                )
                null
            }

        private fun faces(file: String): List<Font> = FACES.map { face ->
            val path = "$FONTS$file-$face.ttf"
            val stream =
                MermaidTextMeasurer::class.java.getResourceAsStream(path)
                    ?: error("$path is missing")
            stream.use { Font.createFont(Font.TRUETYPE_FONT, it).deriveFont(TEXT) }
        }
    }
}
