/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.operation

import dev.streampack.core.integration.EventGateway
import dev.streampack.core.model.OperationResult
import dev.streampack.core.model.Protocol
import dev.streampack.core.model.Provenance
import java.util.Properties
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.info.BuildProperties
import org.springframework.boot.info.GitProperties
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.messaging.support.MessageBuilder

@SpringBootTest
class VersionOperationTests {

    @TestConfiguration
    class VersionTestConfig {
        @Bean
        fun gitProperties(): GitProperties {
            val props = Properties()
            props["branch"] = "main"
            props["commit.id"] = "abc1234567890"
            props["commit.id.abbrev"] = "abc1234"
            return GitProperties(props)
        }

        @Bean
        fun buildProperties(): BuildProperties {
            val props = Properties()
            props["name"] = "Nevet"
            props["version"] = "3.0"
            return BuildProperties(props)
        }
    }

    @Autowired lateinit var eventGateway: EventGateway

    @Autowired lateinit var versionOperation: VersionOperation

    private fun buildMessage(payload: String) =
        MessageBuilder.withPayload(payload)
            .setHeader(
                Provenance.HEADER,
                Provenance(protocol = Protocol.HTTP, serviceId = "test-service", replyTo = "test"),
            )
            .build()

    @Test
    fun `version command returns Success`() {
        val result = eventGateway.process(buildMessage("version"))
        assertInstanceOf(OperationResult.Success::class.java, result)
    }

    @Test
    fun `version response includes application name and commit`() {
        val result = eventGateway.process(buildMessage("version"))
        val payload = (result as OperationResult.Success).payload as String
        assertTrue(payload.contains("Nevet"))
        assertTrue(payload.contains("abc1234"))
        assertTrue(payload.contains("main"))
    }

    @Test
    fun `version response includes build version`() {
        val result = eventGateway.process(buildMessage("version"))
        val payload = (result as OperationResult.Success).payload as String
        assertTrue(payload.contains("3.0"))
    }

    @Test
    fun `non-version messages are not handled`() {
        val message = buildMessage("hello world")
        assertFalse(versionOperation.canHandle(message))
    }

    @Test
    fun `version command is case insensitive`() {
        val result = eventGateway.process(buildMessage("VERSION"))
        assertInstanceOf(OperationResult.Success::class.java, result)
    }

    @Test
    fun `graceful degradation when no build metadata is available`() {
        val operation = VersionOperation(null, null, "")
        val versionString = operation.buildVersionString()
        assertEquals("streampack | development build", versionString)
    }
}
