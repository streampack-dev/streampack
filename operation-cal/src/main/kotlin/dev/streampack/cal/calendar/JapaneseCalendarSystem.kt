/* Joseph B. Ottinger (C)2026 */
package dev.streampack.cal.calendar

import dev.streampack.cal.model.CalendarSystem
import java.time.LocalDate
import java.time.chrono.JapaneseChronology
import java.time.format.DateTimeFormatter
import java.util.Locale
import org.springframework.stereotype.Component

/** Japanese imperial calendar using java.time.chrono.JapaneseChronology. */
@Component
class JapaneseCalendarSystem : CalendarSystem {
    override val name = "japanese"
    override val displayName = "Japanese"

    private val formatter = DateTimeFormatter.ofPattern("EEEE, MMMM d, y GGGG", Locale.ENGLISH)

    override fun formatDate(date: LocalDate): String {
        val japaneseDate = JapaneseChronology.INSTANCE.date(date)
        return japaneseDate.format(formatter)
    }
}
