/* Joseph B. Ottinger (C)2026 */
package dev.streampack.example

import dev.streampack.core.model.Role
import dev.streampack.core.service.RequestCaller
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * The example extension's own HTTP endpoints (#302), under `/ext/greet`. [RequestCaller] says who
 * is signed in, as it does for the server's controllers.
 */
@RestController
@RequestMapping("/ext/greet")
class GreetController(private val caller: RequestCaller) {

    /** Greets the signed-in caller by name; 401 when nobody is signed in. */
    @GetMapping
    fun greet(request: HttpServletRequest): ResponseEntity<Map<String, String>> {
        val user =
            caller.resolve(request)?.principal
                ?: return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(mapOf("error" to "Sign in first."))
        return ResponseEntity.ok(
            mapOf("greeting" to "Hello, ${user.displayName}, from an extension."),
        )
    }

    /** Only for administrators: 401 when nobody is signed in, 403 for anyone else. */
    @GetMapping("/admin")
    fun admin(request: HttpServletRequest): ResponseEntity<Map<String, String>> {
        val user =
            caller.resolve(request)?.principal
                ?: return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(mapOf("error" to "Sign in first."))
        if (user.role < Role.ADMIN) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(mapOf("error" to "This is for administrators."))
        }
        return ResponseEntity.ok(mapOf("greeting" to "Hello, administrator ${user.displayName}."))
    }
}
