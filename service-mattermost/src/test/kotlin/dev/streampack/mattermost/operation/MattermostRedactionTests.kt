/* Joseph B. Ottinger (C)2026 */
package dev.streampack.mattermost.operation

import dev.streampack.core.integration.IngressLoggingInterceptor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.Mockito

class MattermostRedactionTests {
    @Test
    fun `the connect token is redacted before the command is logged`() {
        val operation =
            MattermostAdminOperation(
                Mockito.mock(dev.streampack.mattermost.service.MattermostService::class.java)
            )
        val logged =
            IngressLoggingInterceptor.redact(
                "mattermost connect work https://mm.example.com mm-super-secret",
                operation.redactionRules,
            )
        assertEquals("mattermost connect work https://mm.example.com [REDACTED]", logged)
        assertEquals(
            "mattermost connect work",
            IngressLoggingInterceptor.redact("mattermost connect work", operation.redactionRules),
        )
    }
}
