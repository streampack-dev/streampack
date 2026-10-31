/* Joseph B. Ottinger (C)2026 */
package dev.streampack.example

import dev.streampack.core.model.Role
import dev.streampack.core.testing.ExtensionHarness
import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.setup.MockMvcBuilders

/** The extension's endpoints, with the harness saying who is signed in (#302). */
class GreetControllerTests {
    private val harness = ExtensionHarness()
    private val mvc = MockMvcBuilders.standaloneSetup(GreetController(harness.caller)).build()

    @Test
    fun `nobody signed in is refused`() {
        mvc.get("/ext/greet").andExpect { status { isUnauthorized() } }
        mvc.get("/ext/greet/admin").andExpect { status { isUnauthorized() } }
    }

    @Test
    fun `a signed-in user is greeted by name`() {
        harness.signIn(ExtensionHarness.user("gholem"))

        mvc.get("/ext/greet").andExpect {
            status { isOk() }
            jsonPath("$.greeting") { value("Hello, gholem, from an extension.") }
        }
    }

    @Test
    fun `the admin endpoint is for administrators`() {
        harness.signIn(ExtensionHarness.user("gholem"))
        mvc.get("/ext/greet/admin").andExpect { status { isForbidden() } }

        harness.signIn(ExtensionHarness.user("ada", Role.ADMIN), fromCookie = true)
        mvc.get("/ext/greet/admin").andExpect {
            status { isOk() }
            jsonPath("$.greeting") { value("Hello, administrator ada.") }
        }
    }
}
