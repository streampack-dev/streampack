/* Joseph B. Ottinger (C)2026 */
package dev.streampack.cal.model

import java.time.LocalDate

/** A named calendar system that can format any date as an ASCII string. */
interface CalendarSystem {
    /** Command identifier used in "today <name>" lookups, e.g. "gregorian", "hebrew" */
    val name: String

    /** Human-readable label for listing, e.g. "Gregorian", "Hebrew" */
    val displayName: String

    /** Formats an arbitrary date as 7-bit ASCII text */
    fun formatDate(date: LocalDate): String

    /** Returns today's date formatted as 7-bit ASCII text */
    fun today(): String = formatDate(LocalDate.now())
}
