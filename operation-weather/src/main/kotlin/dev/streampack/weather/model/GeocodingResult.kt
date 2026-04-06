/* Joseph B. Ottinger (C)2026 */
package dev.streampack.weather.model

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

/** A single result from the Nominatim geocoding API */
@JsonIgnoreProperties(ignoreUnknown = true)
data class GeocodingResult(
    val lat: String = "0",
    val lon: String = "0",
    @JsonProperty("display_name") val displayName: String = "",
) {
    val latitude: Double
        get() = lat.toDoubleOrNull() ?: 0.0

    val longitude: Double
        get() = lon.toDoubleOrNull() ?: 0.0
}
