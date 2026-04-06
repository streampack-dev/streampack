/* Joseph B. Ottinger (C)2026 */
package dev.streampack.cal.calendar

import dev.streampack.cal.model.CalendarSystem
import java.time.LocalDate
import java.time.chrono.MinguoChronology
import java.time.format.DateTimeFormatter
import java.util.Locale
import org.springframework.stereotype.Component

/** Minguo (Republic of China) calendar using java.time.chrono.MinguoChronology. */
@Component
class MinguoCalendarSystem : CalendarSystem {
    override val name = "minguo"
    override val displayName = "Minguo"

    private val formatter = DateTimeFormatter.ofPattern("EEEE, MMMM d, y GGGG", Locale.ENGLISH)

    override fun formatDate(date: LocalDate): String {
        val minguoDate = MinguoChronology.INSTANCE.date(date)
        return minguoDate.format(formatter)
    }
}
