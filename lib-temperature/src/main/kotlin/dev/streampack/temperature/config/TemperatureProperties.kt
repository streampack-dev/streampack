/* Joseph B. Ottinger (C)2026 */
package dev.streampack.temperature.config

import org.springframework.boot.context.properties.ConfigurationProperties

/** Configuration for generic time-decayed temperature scoring. */
@ConfigurationProperties(prefix = "streampack.temperature")
data class TemperatureProperties(val halfLifeDays: Double = 14.0, val retentionDays: Long = 365)
