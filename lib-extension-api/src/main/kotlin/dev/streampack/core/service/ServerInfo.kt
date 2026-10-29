/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.service

/** What the running server says about itself to extensions (#294). */
interface ServerInfo {
    /**
     * The server's version, such as `1.16.11`, or null for a build without one, such as a test or a
     * run from the IDE.
     */
    val version: String?

    /**
     * Whether the server is at least [required]. A server with no version counts as new enough,
     * since it is a development build.
     */
    fun isAtLeast(required: String): Boolean = version?.let { compare(it, required) >= 0 } ?: true

    companion object {
        /**
         * Compares two dotted versions by their numbers: `1.16.10` is after `1.16.9`, and a missing
         * part counts as 0. Anything after a `-`, such as `-SNAPSHOT`, is ignored.
         */
        fun compare(a: String, b: String): Int {
            val x = numbers(a)
            val y = numbers(b)
            for (i in 0 until maxOf(x.size, y.size)) {
                val c = x.getOrElse(i) { 0 }.compareTo(y.getOrElse(i) { 0 })
                if (c != 0) return c
            }
            return 0
        }

        private fun numbers(v: String): List<Int> =
            v.substringBefore('-').split('.').map {
                requireNotNull(it.trim().toIntOrNull()) { "Not a version: $v" }
            }
    }
}
