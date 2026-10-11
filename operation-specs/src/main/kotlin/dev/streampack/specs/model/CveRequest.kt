/* Joseph B. Ottinger (C)2026 */
package dev.streampack.specs.model

/**
 * A `cve` question: [term] is a CVE id (`CVE-2021-44228` or `2021-44228`), package coordinates
 * (`group:artifact`, or an ecosystem prefix and a name, `npm:lodash`) or a bare name (`log4j`);
 * [version] narrows a package to the CVEs that affect that version. An id takes no version.
 */
data class CveRequest(val term: String, val version: String? = null)
