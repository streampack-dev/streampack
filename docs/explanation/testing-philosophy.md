# Testing Philosophy

Bug fixes should start with a targeted failing test. The failing execution is part of the bug report: it proves the test reproduces the observed behavior before the fix changes anything.

For HTTP tests, avoid test-managed transactions. A MockMvc request runs in the same test thread, so class-level `@Transactional` can keep Hibernate sessions open in tests that are closed in production HTTP requests. That masks lazy-proxy failures.

Use `@ResetDatabaseBeforeEach` from `lib-testsupport` for HTTP/controller tests that need database isolation. Keep `@Transactional` for repository tests or operation tests where the transaction boundary is part of what is being tested.

The policy test in `lib-testsupport` rejects MockMvc tests that reintroduce propagating `@Transactional`.
