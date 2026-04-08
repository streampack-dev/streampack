# OpenAPI

The generated OpenAPI document is stored at [openapi.json](../openapi.json).

Regenerate it with:

```bash
./mvnw -pl server-streampack -am -Dtest=OpenApiGeneratorTest -Dsurefire.failIfNoSpecifiedTests=false test
```

The generator starts `server-streampack` on a random port, fetches `/v3/api-docs`, normalizes the server URL to `http://localhost:8080`, and writes the result into `docs/openapi.json`.
