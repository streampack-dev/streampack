# Testing Philosophy

Bug fixes should start with a targeted failing test. The failing execution is part of the bug report: it proves the test reproduces the observed behavior before the fix changes anything.

For HTTP tests, avoid test-managed transactions. A MockMvc request runs in the same test thread, so class-level `@Transactional` can keep Hibernate sessions open in tests that are closed in production HTTP requests. That masks lazy-proxy failures.

Use `@ResetDatabaseBeforeEach` from `lib-testsupport` for HTTP/controller tests that need database isolation. Keep `@Transactional` for repository tests or operation tests where the transaction boundary is part of what is being tested.

The policy test in `lib-testsupport` rejects MockMvc tests that reintroduce propagating `@Transactional`.

## Shared test settings

`lib-testsupport` holds the settings every module's tests need in `streampack-test-defaults.yml`. Examples are the test database, the test secrets, and `streampack.tick.scheduler.enabled: false`. The file is not named `application.yml`, because a module's own `src/test/resources/application.yml` replaces every other `application.yml` on the classpath.

`TestDefaultsEnvironmentPostProcessor` adds the file as the source with the lowest precedence. Every other source wins over it: a module's own `application.yml`, `@TestPropertySource`, and `@SpringBootTest(properties = …)`. To change one shared setting, set only that key in the module's file. The module keeps the other shared settings.

## Stub HTTP servers

Create a test's stub server with `loopbackHttpServer()` from `lib-testsupport`. It binds the loopback address on a free port. Do not bind with `InetSocketAddress(port)`, which binds every address. `just test` runs modules in parallel, and on macOS a second server can then take `127.0.0.1` on the same port and receive the first test's requests.

`lib-core` cannot use either, because `lib-testsupport` depends on it. Its own test `application.yml` turns the scheduler off, and its tests bind `InetAddress.getLoopbackAddress()` directly.
