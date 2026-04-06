/* Joseph B. Ottinger (C)2026 */
package dev.streampack.cal.calendar

import dev.streampack.cal.model.CalendarSystem
import java.time.LocalDate
import java.time.chrono.HijrahChronology
import java.time.format.DateTimeFormatter
import java.util.Locale
import org.springframework.stereotype.Component

/** Islamic (Hijri) calendar using java.time.chrono.HijrahChronology. */
@Component
class HijriCalendarSystem : CalendarSystem {
    override val name = "hijri"
    override val displayName = "Hijri"

    private val formatter = DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy GGGG", Locale.ENGLISH)

    override fun formatDate(date: LocalDate): String {
        val hijriDate = HijrahChronology.INSTANCE.date(date)
        return hijriDate.format(formatter)
    }
}
