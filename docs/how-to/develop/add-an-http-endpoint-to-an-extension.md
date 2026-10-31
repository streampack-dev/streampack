# Add an HTTP Endpoint to an Extension

An extension can serve its own HTTP endpoints, beside the server's API. It checks who is signed in the same way the server's own controllers do. The repository's `example-extension` has a complete example in `GreetController`.

You need an extension that builds against `lib-extension-api`. To make one, follow [Write and Load an Extension](../../tutorials/write-and-load-an-extension.md).

## 1. Add Spring MVC to the Build

Add `spring-webmvc` with the `provided` scope. The server has it already, so it stays out of your jar. The version comes from the server's BOM:

```xml
<dependency>
    <groupId>org.springframework</groupId>
    <artifactId>spring-webmvc</artifactId>
    <scope>provided</scope>
</dependency>
```

## 2. Write the Controller

Put the controller in a package under `dev.streampack`, such as `dev.streampack.greet`. The server scans that package, so it finds the controller like one of its own. Inject `RequestCaller` to find out who is calling:

```kotlin
@RestController
@RequestMapping("/ext/greet")
class GreetController(private val caller: RequestCaller) {

    @GetMapping
    fun greet(request: HttpServletRequest): ResponseEntity<Map<String, String>> {
        val user =
            caller.resolve(request)?.principal
                ?: return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(mapOf("error" to "Sign in first."))
        return ResponseEntity.ok(mapOf("greeting" to "Hello, ${user.displayName}."))
    }
}
```

`caller.resolve(request)` returns a `Credential`, or null when nobody valid is signed in. A `Credential` has:

- `principal`: the user's `id`, `username`, `displayName` and `role`.
- `expiresAt`: when the sign-in ends.
- `fromCookie`: true when the browser's sign-in cookie carried it, false for an `Authorization: Bearer` token.

The server lets every request reach a controller. The controller decides who may use it, so an endpoint without a check is public.

## 3. Check the Role

Roles are ordered: `GUEST`, `USER`, `ADMIN`, `SUPER_ADMIN`. To let in administrators and above, compare:

```kotlin
if (user.role < Role.ADMIN) {
    return ResponseEntity.status(HttpStatus.FORBIDDEN)
        .body(mapOf("error" to "This is for administrators."))
}
```

## 4. Choose the Paths

Put your endpoints under `/ext/<your extension's name>/`, such as `/ext/greet/`. The server does not enforce this, but it keeps your paths apart from the server's `/api/` and from other extensions.

If two controllers map the same path and method, the server does not start, and the log names both.

## 5. Protect Requests That Change Something

The sign-in cookie is `SameSite=Strict`, so a browser does not send it with a request that another site starts. That covers most cross-site request forgery.

For a request that does something dangerous, do what the server's web console does. When `fromCookie` is true, require a header of your own, such as `X-Greet: 1`, and refuse a request whose `Origin` header names a site you don't trust. A browser can't add a custom header to a cross-site request without CORS allowing it first.

## 6. Test It

`lib-extension-test` has what a controller test needs: Spring's `MockMvc`, and `ExtensionHarness.caller`, a `RequestCaller` that you control. Use `signIn` and `signOut` to say who is calling:

```kotlin
class GreetControllerTests {
    private val harness = ExtensionHarness()
    private val mvc = MockMvcBuilders.standaloneSetup(GreetController(harness.caller)).build()

    @Test
    fun `a signed-in user is greeted by name`() {
        harness.signIn(ExtensionHarness.user("gholem"))

        mvc.get("/ext/greet").andExpect {
            status { isOk() }
            jsonPath("$.greeting") { value("Hello, gholem.") }
        }
    }
}
```

`signIn(user, fromCookie = true)` acts as a browser's cookie.

## 7. Call It

Build the jar, put it in `ext/` and recreate the server, as in [Extend the Server with Jars](../deploy/extend-the-server-with-jars.md). Then call the endpoint with a token:

```bash
curl -H "Authorization: Bearer $TOKEN" https://api.example.com/ext/greet
```

The endpoint also appears in the server's live OpenAPI document, at `/v3/api-docs`, with what your controller's annotations say about it.

## Related

- [The `ext/` Directory](../../reference/ext-directory.md): what the server gives an extension, and how to test one.
- [Extensions](../../explanation/extensions.md): how extensions load, and what they are trusted with.
