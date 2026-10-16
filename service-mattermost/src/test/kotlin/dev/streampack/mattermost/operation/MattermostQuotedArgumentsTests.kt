/* Joseph B. Ottinger (C)2026 */
package dev.streampack.mattermost.operation

import dev.streampack.core.model.OperationResult
import dev.streampack.core.model.Protocol
import dev.streampack.core.model.Provenance
import dev.streampack.core.model.Role
import dev.streampack.core.model.UserPrincipal
import dev.streampack.mattermost.service.MattermostService
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.Mockito.verify
import org.springframework.messaging.support.MessageBuilder

/** Channel names with spaces are given in quotes (#243); the connect token stays one word. */
class MattermostQuotedArgumentsTests {
    private val service = Mockito.mock(MattermostService::class.java)
    private val operation = MattermostAdminOperation(service)

    private fun run(text: String): OperationResult {
        val admin =
            UserPrincipal(
                id = UUID.randomUUID(),
                username = "admin",
                displayName = "Admin",
                role = Role.SUPER_ADMIN,
            )
        val message =
            MessageBuilder.withPayload(text)
                .setHeader(
                    Provenance.HEADER,
                    Provenance(
                        protocol = Protocol.CONSOLE,
                        serviceId = "",
                        replyTo = "local",
                        user = admin,
                    ),
                )
                .build()
        return operation.handle(text, message) as OperationResult
    }

    @Test
    fun `a quoted channel name reaches the service whole`() {
        Mockito.`when`(service.join("firenet", "Dev Ops")).thenReturn("Joined 'dev-ops'")
        Mockito.`when`(service.setAutojoin("firenet", "Dev Ops", true)).thenReturn("set")
        Mockito.`when`(service.listChannels("firenet", "dev ops")).thenReturn("list")
        Mockito.`when`(service.leave("firenet", "Joe's Place")).thenReturn("Left")

        assertInstanceOf(
            OperationResult.Success::class.java,
            run("""mattermost join firenet "Dev Ops""""),
        )
        run("mattermost autojoin firenet 'Dev Ops' true")
        run("""mattermost channels firenet "dev ops"""")
        run("""mattermost leave firenet "Joe's Place"""")

        verify(service).join("firenet", "Dev Ops")
        verify(service).setAutojoin("firenet", "Dev Ops", true)
        verify(service).listChannels("firenet", "dev ops")
        verify(service).leave("firenet", "Joe's Place")
    }

    @Test
    fun `unquoted names are unchanged`() {
        Mockito.`when`(service.join("firenet", "#town-square")).thenReturn("Joined")
        run("mattermost join firenet #town-square")
        verify(service).join("firenet", "#town-square")
    }

    @Test
    fun `an unclosed quote is an error, and nothing is changed`() {
        val result = run("""mattermost join firenet "Dev Ops""")
        assertInstanceOf(OperationResult.Error::class.java, result)
        assertEquals(
            """Unclosed " in: join firenet "Dev Ops. Close the quote; a name with an apostrophe goes in double quotes.""",
            (result as OperationResult.Error).message,
        )
        Mockito.verifyNoInteractions(service)
    }

    @Test
    fun `connect splits on whitespace alone, so the token is the word the log redacts`() {
        Mockito.`when`(service.connect("work", "https://mm.example.com", "to'ken"))
            .thenReturn("Connecting to 'work'...")
        run("mattermost connect work https://mm.example.com to'ken")
        verify(service).connect("work", "https://mm.example.com", "to'ken")
    }
}
