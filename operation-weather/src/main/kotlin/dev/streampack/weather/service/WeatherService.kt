/* Joseph B. Ottinger (C)2026 */
package dev.streampack.weather.service

import dev.streampack.core.json.JacksonMappers
import dev.streampack.weather.config.WeatherProperties
import dev.streampack.weather.model.GeocodingResult
import dev.streampack.weather.model.WeatherApiResponse
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import tools.jackson.module.kotlin.readValue

/** Geocodes locations via Nominatim and fetches current weather from OpenWeatherMap */
@Service
class WeatherService(private val config: WeatherProperties) {

    private val logger = LoggerFactory.getLogger(WeatherService::class.java)
    private val mapper = JacksonMappers.standard()

    /** Enforces Nominatim TOS: max 1 geocoding request per second */
    @Volatile private var lastGeocodingRequestMs: Long = 0

    /** Get formatted weather for a location string, or null if lookup fails */
    fun getWeather(location: String): WeatherResult? {
        if (config.apiKey.isBlank()) {
            logger.warn("OpenWeatherMap API key not configured (streampack.weather.api-key)")
            return null
        }

        val geocode = rateLimitedGeocode(location) ?: return null
        val weather = fetchWeather(geocode.latitude, geocode.longitude) ?: return null

        return WeatherResult(
            location = geocode.displayName,
            tempCelsius = weather.main.temp,
            tempFahrenheit = celsiusToFahrenheit(weather.main.temp),
            feelsLikeCelsius = weather.main.feelsLike,
            feelsLikeFahrenheit = celsiusToFahrenheit(weather.main.feelsLike),
            humidity = weather.main.humidity,
            description = weather.weather.firstOrNull()?.description ?: "unknown",
        )
    }

    /** Enforce 1 request/second rate limit for Nominatim TOS compliance */
    @Synchronized
    private fun rateLimitedGeocode(location: String): GeocodingResult? {
        val now = System.currentTimeMillis()
        val elapsed = now - lastGeocodingRequestMs
        if (elapsed < NOMINATIM_MIN_INTERVAL_MS) {
            Thread.sleep(NOMINATIM_MIN_INTERVAL_MS - elapsed)
        }
        lastGeocodingRequestMs = System.currentTimeMillis()
        return geocode(location)
    }

    private fun geocode(location: String): GeocodingResult? {
        val url = buildGeocodeUrl(location)
        val body = fetchBody(url) ?: return null
        return try {
            val results: List<GeocodingResult> = mapper.readValue(body)
            results.firstOrNull()
        } catch (e: Exception) {
            logger.debug("Failed to parse geocoding response: {}", e.message)
            null
        }
    }

    /** Detects postal codes and builds a structured query; falls back to freeform search */
    private fun buildGeocodeUrl(location: String): String {
        val trimmed = location.trim()
        val postalMatch = detectPostalCode(trimmed)
        if (postalMatch != null) {
            val encoded = URLEncoder.encode(postalMatch.first, Charsets.UTF_8)
            return "${config.geocodeBaseUrl}?postalcode=$encoded" +
                "&countrycodes=${postalMatch.second}&format=json&limit=1"
        }
        val encoded = URLEncoder.encode(trimmed, Charsets.UTF_8)
        return "${config.geocodeBaseUrl}?q=$encoded&format=json&limit=1"
    }

    /** Returns (postalCode, countryCode) if the input matches a known postal code pattern */
    private fun detectPostalCode(input: String): Pair<String, String>? =
        when {
            US_ZIP.matches(input) -> input to "us"
            UK_POSTCODE.matches(input) -> input to "gb"
            CA_POSTCODE.matches(input) -> input to "ca"
            else -> null
        }

    private fun fetchWeather(lat: Double, lon: Double): WeatherApiResponse? {
        val url = "${config.weatherBaseUrl}?lat=$lat&lon=$lon&units=metric&appid=${config.apiKey}"
        val body = fetchBody(url) ?: return null
        return try {
            mapper.readValue<WeatherApiResponse>(body)
        } catch (e: Exception) {
            logger.debug("Failed to parse weather response: {}", e.message)
            null
        }
    }

    private fun fetchBody(url: String): String? {
        return try {
            val client =
                HttpClient.newBuilder()
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .connectTimeout(Duration.ofSeconds(config.connectTimeoutSeconds.toLong()))
                    .build()
            val request =
                HttpRequest.newBuilder()
                    .uri(URI(url))
                    .timeout(Duration.ofSeconds(config.readTimeoutSeconds.toLong()))
                    .header(
                        "User-Agent",
                        "Mozilla/5.0 (compatible; Nevet/1.0; +https://bytecode.news)",
                    )
                    .GET()
                    .build()
            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() in 200..299) {
                response.body()
            } else {
                logger.debug("HTTP {} fetching {}", response.statusCode(), url)
                null
            }
        } catch (e: Exception) {
            logger.debug("Failed to fetch {}: {}", url, e.message)
            null
        }
    }

    private fun celsiusToFahrenheit(celsius: Double): Double =
        Math.round((celsius * 9.0 / 5.0 + 32.0) * 10.0) / 10.0

    companion object {
        private const val NOMINATIM_MIN_INTERVAL_MS = 1000L
        private val US_ZIP = Regex("""^\d{5}(-\d{4})?$""")
        private val UK_POSTCODE =
            Regex("""^[A-Z]{1,2}\d[A-Z\d]?\s*\d[A-Z]{2}$""", RegexOption.IGNORE_CASE)
        private val CA_POSTCODE = Regex("""^[A-Z]\d[A-Z]\s*\d[A-Z]\d$""", RegexOption.IGNORE_CASE)
    }
}

/** Formatted weather result ready for display */
data class WeatherResult(
    val location: String,
    val tempCelsius: Double,
    val tempFahrenheit: Double,
    val feelsLikeCelsius: Double,
    val feelsLikeFahrenheit: Double,
    val humidity: Int,
    val description: String,
)
