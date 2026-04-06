/* Joseph B. Ottinger (C)2026 */
package dev.streampack.weather.model

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

/** Response from OpenWeatherMap Current Weather API (2.5/weather) */
@JsonIgnoreProperties(ignoreUnknown = true)
data class WeatherApiResponse(
    val main: WeatherMain = WeatherMain(),
    val weather: List<WeatherCondition> = emptyList(),
    val name: String = "",
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class WeatherMain(
    val temp: Double = 0.0,
    @JsonProperty("feels_like") val feelsLike: Double = 0.0,
    val humidity: Int = 0,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class WeatherCondition(val main: String = "", val description: String = "")
