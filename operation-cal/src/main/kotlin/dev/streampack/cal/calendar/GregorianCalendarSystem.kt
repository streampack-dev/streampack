/* Joseph B. Ottinger (C)2026 */
package dev.streampack.cal.calendar

import dev.streampack.cal.model.CalendarSystem
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import org.springframework.stereotype.Component

/** Gregorian calendar using java.time.LocalDate. */
@Component
class GregorianCalendarSystem : CalendarSystem {
    override val name = "gregorian"
    override val displayName = "Gregorian"

    private val formatter = DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy", Locale.ENGLISH)

    override fun formatDate(date: LocalDate): String = date.format(formatter)
}
