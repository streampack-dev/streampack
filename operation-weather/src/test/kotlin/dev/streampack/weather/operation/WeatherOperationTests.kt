/* Joseph B. Ottinger (C)2026 */
package dev.streampack.weather.operation

import com.sun.net.httpserver.HttpServer
import dev.streampack.core.integration.EventGateway
import dev.streampack.core.model.OperationResult
import dev.streampack.core.model.Protocol
import dev.streampack.core.model.Provenance
import dev.streampack.weather.config.WeatherProperties
import dev.streampack.weather.service.WeatherService
import java.net.InetSocketAddress
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.messaging.support.MessageBuilder

@SpringBootTest
class WeatherOperationTests {

    @Autowired lateinit var eventGateway: EventGateway

    private lateinit var httpServer: HttpServer
    private var baseUrl: String = ""

    private fun provenance() =
        Provenance(protocol = Protocol.CONSOLE, serviceId = "", replyTo = "local")

    private fun message(text: String) =
        MessageBuilder.withPayload(text).setHeader(Provenance.HEADER, provenance()).build()

    @BeforeEach
    fun setUp() {
        httpServer = HttpServer.create(InetSocketAddress(0), 0)
        httpServer.start()
        baseUrl = "http://localhost:${httpServer.address.port}"
    }

    @AfterEach
    fun tearDown() {
        httpServer.stop(0)
    }

    @Test
    fun `weather lookup returns formatted response`() {
        httpServer.createContext("/search") { exchange ->
            val json = geocodeJson("Tallahassee, Leon County, Florida, US", 30.4381, -84.2808)
            exchange.sendResponseHeaders(200, json.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(json.toByteArray()) }
        }
        httpServer.createContext("/data/2.5/weather") { exchange ->
            val json = weatherJson(27.8, 29.0, 65, "Clear", "clear sky")
            exchange.sendResponseHeaders(200, json.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(json.toByteArray()) }
        }

        val props =
            WeatherProperties(
                apiKey = "test-key",
                geocodeBaseUrl = "$baseUrl/search",
                weatherBaseUrl = "$baseUrl/data/2.5/weather",
            )
        val service = WeatherService(props)
        val operation = WeatherOperation(service)

        val result = operation.execute(message("weather tallahassee, fl"))
        assertInstanceOf(OperationResult.Success::class.java, result)
        val payload = (result as OperationResult.Success).payload.toString()
        assertTrue(payload.contains("Tallahassee"))
        assertTrue(payload.contains("27.8C"))
        assertTrue(payload.contains("clear sky"))
    }

    @Test
    fun `weather response includes fahrenheit`() {
        httpServer.createContext("/search") { exchange ->
            val json = geocodeJson("London, England, United Kingdom", 51.5074, -0.1278)
            exchange.sendResponseHeaders(200, json.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(json.toByteArray()) }
        }
        httpServer.createContext("/data/2.5/weather") { exchange ->
            val json = weatherJson(10.0, 8.5, 80, "Clouds", "overcast clouds")
            exchange.sendResponseHeaders(200, json.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(json.toByteArray()) }
        }

        val props =
            WeatherProperties(
                apiKey = "test-key",
                geocodeBaseUrl = "$baseUrl/search",
                weatherBaseUrl = "$baseUrl/data/2.5/weather",
            )
        val service = WeatherService(props)
        val operation = WeatherOperation(service)

        val result = operation.execute(message("weather london"))
        assertInstanceOf(OperationResult.Success::class.java, result)
        val payload = (result as OperationResult.Success).payload.toString()
        assertTrue(payload.contains("50.0F"))
        assertTrue(payload.contains("overcast clouds"))
    }

    @Test
    fun `non-weather message is not handled`() {
        val result = eventGateway.process(message("hello world"))
        assertInstanceOf(OperationResult.NotHandled::class.java, result)
    }

    @Test
    fun `weather without location is not handled`() {
        val result = eventGateway.process(message("weather "))
        assertInstanceOf(OperationResult.NotHandled::class.java, result)
    }

    @Test
    fun `weather is case insensitive`() {
        httpServer.createContext("/search") { exchange ->
            val json = geocodeJson("Test, US", 0.0, 0.0)
            exchange.sendResponseHeaders(200, json.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(json.toByteArray()) }
        }
        httpServer.createContext("/data/2.5/weather") { exchange ->
            val json = weatherJson(20.0, 20.0, 50, "Clear", "clear sky")
            exchange.sendResponseHeaders(200, json.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(json.toByteArray()) }
        }

        val props =
            WeatherProperties(
                apiKey = "test-key",
                geocodeBaseUrl = "$baseUrl/search",
                weatherBaseUrl = "$baseUrl/data/2.5/weather",
            )
        val service = WeatherService(props)
        val operation = WeatherOperation(service)

        val result = operation.execute(message("Weather TEST"))
        assertInstanceOf(OperationResult.Success::class.java, result)
    }

    @Test
    fun `US zip code uses structured postal code query`() {
        httpServer.createContext("/search") { exchange ->
            val query = exchange.requestURI.query
            assertTrue(query.contains("postalcode=27596"), "Expected postalcode param, got: $query")
            assertTrue(query.contains("countrycodes=us"), "Expected countrycodes=us, got: $query")
            val json = geocodeJson("Wake Forest, NC 27596, US", 35.9799, -78.5097)
            exchange.sendResponseHeaders(200, json.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(json.toByteArray()) }
        }
        httpServer.createContext("/data/2.5/weather") { exchange ->
            val json = weatherJson(22.0, 21.0, 55, "Clear", "clear sky")
            exchange.sendResponseHeaders(200, json.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(json.toByteArray()) }
        }

        val props =
            WeatherProperties(
                apiKey = "test-key",
                geocodeBaseUrl = "$baseUrl/search",
                weatherBaseUrl = "$baseUrl/data/2.5/weather",
            )
        val service = WeatherService(props)
        val operation = WeatherOperation(service)

        val result = operation.execute(message("weather 27596"))
        assertInstanceOf(OperationResult.Success::class.java, result)
        val payload = (result as OperationResult.Success).payload.toString()
        assertTrue(payload.contains("Wake Forest"))
    }

    @Test
    fun `UK postcode uses structured postal code query`() {
        httpServer.createContext("/search") { exchange ->
            val query = exchange.requestURI.query
            assertTrue(query.contains("postalcode="), "Expected postalcode param, got: $query")
            assertTrue(query.contains("countrycodes=gb"), "Expected countrycodes=gb, got: $query")
            val json = geocodeJson("Westminster, London SW1A 1AA, UK", 51.5014, -0.1419)
            exchange.sendResponseHeaders(200, json.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(json.toByteArray()) }
        }
        httpServer.createContext("/data/2.5/weather") { exchange ->
            val json = weatherJson(12.0, 10.0, 70, "Clouds", "broken clouds")
            exchange.sendResponseHeaders(200, json.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(json.toByteArray()) }
        }

        val props =
            WeatherProperties(
                apiKey = "test-key",
                geocodeBaseUrl = "$baseUrl/search",
                weatherBaseUrl = "$baseUrl/data/2.5/weather",
            )
        val service = WeatherService(props)
        val operation = WeatherOperation(service)

        val result = operation.execute(message("weather SW1A 1AA"))
        assertInstanceOf(OperationResult.Success::class.java, result)
        val payload = (result as OperationResult.Success).payload.toString()
        assertTrue(payload.contains("Westminster"))
    }

    @Test
    fun `Canadian postal code uses structured postal code query`() {
        httpServer.createContext("/search") { exchange ->
            val query = exchange.requestURI.query
            assertTrue(query.contains("postalcode="), "Expected postalcode param, got: $query")
            assertTrue(query.contains("countrycodes=ca"), "Expected countrycodes=ca, got: $query")
            val json = geocodeJson("Ottawa, ON K1A 0B1, Canada", 45.4215, -75.6972)
            exchange.sendResponseHeaders(200, json.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(json.toByteArray()) }
        }
        httpServer.createContext("/data/2.5/weather") { exchange ->
            val json = weatherJson(-8.0, -12.0, 85, "Snow", "light snow")
            exchange.sendResponseHeaders(200, json.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(json.toByteArray()) }
        }

        val props =
            WeatherProperties(
                apiKey = "test-key",
                geocodeBaseUrl = "$baseUrl/search",
                weatherBaseUrl = "$baseUrl/data/2.5/weather",
            )
        val service = WeatherService(props)
        val operation = WeatherOperation(service)

        val result = operation.execute(message("weather K1A 0B1"))
        assertInstanceOf(OperationResult.Success::class.java, result)
        val payload = (result as OperationResult.Success).payload.toString()
        assertTrue(payload.contains("Ottawa"))
    }

    @Test
    fun `geocode failure returns null`() {
        httpServer.createContext("/search") { exchange ->
            val json = "[]"
            exchange.sendResponseHeaders(200, json.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(json.toByteArray()) }
        }

        val props =
            WeatherProperties(
                apiKey = "test-key",
                geocodeBaseUrl = "$baseUrl/search",
                weatherBaseUrl = "$baseUrl/data/2.5/weather",
            )
        val service = WeatherService(props)
        val operation = WeatherOperation(service)

        val result = operation.execute(message("weather xyznonexistent"))
        assertNull(result)
    }

    companion object {
        fun geocodeJson(displayName: String, lat: Double, lon: Double): String =
            """[{"lat":"$lat","lon":"$lon","display_name":"$displayName"}]"""

        fun weatherJson(
            temp: Double,
            feelsLike: Double,
            humidity: Int,
            main: String,
            description: String,
        ): String =
            """{"main":{"temp":$temp,"feels_like":$feelsLike,"humidity":$humidity},"weather":[{"main":"$main","description":"$description"}],"name":"Test"}"""
    }
}
