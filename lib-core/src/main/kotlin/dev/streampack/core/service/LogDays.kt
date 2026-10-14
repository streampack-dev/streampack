/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.service

import dev.streampack.core.config.StreampackProperties
import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component

/**
 * How channel logs are split into days (#212). Every reader sees the same day, so the zone is the
 * instance's, set by `streampack.logs.zone` (`STREAMPACK_LOG_ZONE`) and UTC by default. The JVM's
 * own zone is not used: in a container it is almost always UTC whatever the host says. Stored
 * timestamps stay UTC instants; only their grouping into days follows the zone.
 */
@Component
class LogDays(val zone: ZoneId) {

    @Autowired constructor(properties: StreampackProperties) : this(parse(properties.logs.zone))

    /** The log day it is now */
    fun today(): LocalDate = LocalDate.now(zone)

    /** The day an instant's line belongs to */
    fun dayOf(instant: Instant): LocalDate = instant.atZone(zone).toLocalDate()

    /** The first instant of [day]; with [end], a day's half-open window, 23 to 25 hours long */
    fun start(day: LocalDate): Instant = day.atStartOfDay(zone).toInstant()

    /** The first instant after [day] */
    fun end(day: LocalDate): Instant = day.plusDays(1).atStartOfDay(zone).toInstant()

    companion object {
        /** Reads the configured zone, refusing to start on one that isn't a timezone */
        fun parse(value: String): ZoneId =
            try {
                ZoneId.of(value.trim())
            } catch (e: DateTimeException) {
                throw SilentStartupException(
                    "STREAMPACK_LOG_ZONE (streampack.logs.zone) is '$value', which is not a " +
                        "timezone. Use an IANA zone such as America/New_York, or UTC."
                )
            }
    }
}
