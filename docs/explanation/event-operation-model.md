# Event and Operation Model

The normal flow is:

```text
protocol adapter -> ingress channel -> OperationService -> egress channel -> protocol adapter
```

Protocol adapters receive input from IRC, Discord, Slack, HTTP, MCP, or the console. They create a Spring message with a payload and a `Provenance` header, then send it to `EventGateway`.

`OperationService` evaluates registered operations in priority order. The first operation to return a terminal `Success` or `Error` result stops the chain. A non-terminal result allows the chain to continue.

Egress subscribers receive the result with the original provenance. Each subscriber checks whether the result belongs to its protocol and sends the response through its own transport.

## Operation Types

Use `TypedOperation<T>` when an operation handles one typed payload. Use `TranslatingOperation<T>` when the operation accepts both interactive text and typed requests. Use raw `Operation` only when the operation needs unusual message/header handling.

Services should own domain behavior. Operations should translate between the message pipeline and services.
