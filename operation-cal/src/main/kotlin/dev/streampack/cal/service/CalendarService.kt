/* Joseph B. Ottinger (C)2026 */
package dev.streampack.cal.service

import dev.streampack.cal.model.CalendarSystem
import java.time.LocalDate
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/** Collects all registered CalendarSystem beans and provides lookup by name. */
@Component
class CalendarService(private val calendars: List<CalendarSystem>) {
    private val logger = LoggerFactory.getLogger(CalendarService::class.java)
    private val calendarsByName: Map<String, CalendarSystem> =
        calendars.associateBy { it.name.lowercase() }

    init {
        logger.info(
            "Registered {} calendar systems: {}",
            calendars.size,
            calendars.map { it.name }.sorted().joinToString(", "),
        )
    }

    /** Looks up a calendar by command name, case-insensitive. */
    fun getCalendar(name: String): CalendarSystem? = calendarsByName[name.lowercase()]

    /** Returns all registered calendars sorted by name. */
    fun listCalendars(): List<CalendarSystem> = calendars.sortedBy { it.name }

    /** Returns the Gregorian calendar as the default. */
    fun defaultCalendar(): CalendarSystem =
        calendarsByName["gregorian"]
            ?: throw IllegalStateException("Gregorian calendar system not registered")

    /**
     * Formats a date using the named calendar, or the default (Gregorian) if name is null. Returns
     * null if the calendar name is not found.
     */
    fun formatDate(date: LocalDate, calendarName: String? = null): String? {
        val calendar =
            if (calendarName == null) defaultCalendar()
            else getCalendar(calendarName) ?: return null
        return calendar.formatDate(date)
    }
}
