# Create a Service Module

A service module owns domain behavior or protocol integration. It should not be a catch-all application module.

Typical service module responsibilities:

- expose HTTP controllers for one domain
- implement a protocol adapter
- persist domain entities and repositories
- provide domain services used by operations

Typical module shape:

```text
service-example/
  pom.xml
  src/main/kotlin/dev/streampack/example/
    controller/
    service/
    repository/
    entity/
```

Add dependencies on `lib-core` and any domain libraries the service needs. Keep reusable platform contracts in libraries, not in `server-streampack`.

If a service participates in the operation pipeline, publish or consume messages through the shared gateway/channel contracts instead of binding directly to a specific protocol.
