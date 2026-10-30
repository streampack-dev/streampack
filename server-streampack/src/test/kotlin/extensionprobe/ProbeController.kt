/* Joseph B. Ottinger (C)2026 */
package extensionprobe

import dev.streampack.core.service.RequestCaller
import jakarta.servlet.http.HttpServletRequest
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

/** A controller as an extension writes one (#302), for ExtensionEndpointTests only. */
@RestController
class ProbeController(private val caller: RequestCaller) {
    /** Who the server says is calling: name, role and where the credential came from. */
    @GetMapping("/ext/probe", produces = ["text/plain"])
    fun probe(request: HttpServletRequest): String =
        caller.resolve(request)?.let {
            "${it.principal.username} ${it.principal.role} ${if (it.fromCookie) "cookie" else "bearer"}"
        } ?: "nobody"
}
