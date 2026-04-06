/* Joseph B. Ottinger (C)2026 */
package dev.streampack.weather.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "streampack.weather")
data class WeatherProperties(
    val apiKey: String = "",
    val connectTimeoutSeconds: Int = 5,
    val readTimeoutSeconds: Int = 10,
    val geocodeBaseUrl: String = "https://nominatim.openstreetmap.org/search",
    val weatherBaseUrl: String = "https://api.openweathermap.org/data/2.5/weather",
)
