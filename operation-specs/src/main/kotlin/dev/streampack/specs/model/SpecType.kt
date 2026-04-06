/* Joseph B. Ottinger (C)2026 */
package dev.streampack.specs.model

/** Supported specification types with their source URL templates and title extraction hints */
enum class SpecType(val urlTemplate: String, val cssSelector: String) {
    RFC("https://www.rfc-editor.org/rfc/rfc%d.html", "title"),
    JEP("https://openjdk.org/jeps/%d", "title"),
    JSR("https://jcp.org/en/jsr/detail?id=%d", ".header1"),
    PEP("https://peps.python.org/pep-%04d/", ".page-title"),
}
