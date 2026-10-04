/* Joseph B. Ottinger (C)2026 */
package dev.streampack.specs.model

/**
 * Supported specification types: the address a reader is given ([urlTemplate]), the page the title
 * is read from ([lookupTemplate], the same unless the reader's page has no usable title), and where
 * on that page the title is.
 */
enum class SpecType(
    val urlTemplate: String,
    val cssSelector: String,
    val lookupTemplate: String = urlTemplate,
) {
    // Older RFCs' own pages are preformatted text without a <title>; the RFC Editor's info page
    // has one for every RFC, and is a 404 for one that doesn't exist.
    RFC(
        "https://www.rfc-editor.org/rfc/rfc%d.html",
        "title",
        "https://www.rfc-editor.org/info/rfc%d",
    ),
    JEP("https://openjdk.org/jeps/%d", "title"),
    // jcp.org has the title only in the page's <h1> (its <title> is set by script).
    JSR("https://jcp.org/en/jsr/detail?id=%d", "h1"),
    PEP("https://peps.python.org/pep-%04d/", ".page-title"),
}
