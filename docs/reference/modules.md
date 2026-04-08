# Module Reference

| Module | Role |
|--------|------|
| `lib-core` | Platform core: operations, event gateway, provenance, protocols, users, service bindings |
| `lib-testsupport` | Shared test support, channel overrides, HTTP database reset utilities |
| `lib-blog` | Blog/site domain model, repositories, and operations |
| `lib-factoid` | Factoid domain library |
| `lib-irc` | IRC domain and integration support |
| `lib-slack` | Slack integration support |
| `lib-polling` | Shared polling infrastructure |
| `lib-taxonomy` | Taxonomy/category support |
| `lib-web` | Shared web support |
| `operation-*` | Operation modules that add behavior to the operation pipeline |
| `service-*` | Service modules for HTTP, protocols, and integrations |
| `server-streampack` | Bundled runnable server distribution |
| `coverage-report` | Aggregate coverage report module |

The parent POM manages versions. Child modules should not pin versions for internal `dev.streampack` dependencies.
