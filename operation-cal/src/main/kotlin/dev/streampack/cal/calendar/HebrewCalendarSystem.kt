/* Joseph B. Ottinger (C)2026 */
package dev.streampack.cal.calendar

import com.kosherjava.zmanim.hebrewcalendar.HebrewDateFormatter
import com.kosherjava.zmanim.hebrewcalendar.JewishCalendar
import dev.streampack.cal.model.CalendarSystem
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import org.springframework.stereotype.Component

/** Hebrew calendar using KosherJava's zmanim library for transliterated month names. */
@Component
class HebrewCalendarSystem : CalendarSystem {
    override val name = "hebrew"
    override val displayName = "Hebrew"

    private val dayFormatter = DateTimeFormatter.ofPattern("EEEE", Locale.ENGLISH)

    private val formatter = HebrewDateFormatter().apply { isHebrewFormat = false }

    override fun formatDate(date: LocalDate): String {
        val cal = JewishCalendar(date)
        return "${dayFormatter.format(date)}, ${formatter.format(cal)}"
    }
}
