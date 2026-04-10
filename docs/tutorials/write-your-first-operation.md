# Write Your First Operation

This tutorial sketches the shape of a new operation module.

## 1. Choose the Operation Type

Use `TypedOperation<T>` if the operation receives a typed request. Use `TranslatingOperation<T>` if it must parse interactive text as well as accept typed requests.

For a simple typed operation:

```kotlin
data class EchoRequest(val text: String)

@Component
class EchoOperation : TypedOperation<EchoRequest>(EchoRequest::class) {
    override fun handle(payload: EchoRequest, message: Message<*>): OperationOutcome {
        return OperationResult.Success(payload.text)
    }
}
```

## 2. Choose the Right Outcome

- Return `OperationResult.Success(...)` when the caller should see a normal response.
- Return `OperationResult.Error(...)` when the caller should see a definitive failure.
- Return `Declined(...)` when your operation recognized the message but wants the chain to
  continue.
- Return `Consumed(...)` when your operation handled the message internally and should stop the
  chain without sending anything to egress.
- Return `null` when the operation was not relevant after deeper inspection.

`Consumed` is the right shape for internal work such as queueing deferred rerenders, updating
buffers, or recording state for later timers and listeners.

## 3. Keep Domain Logic in a Service

Operations should be routing and translation glue. If the behavior has state or domain rules, put it in a service and inject that service into the operation.

## 4. Add Tests

Test the operation with the shared channel and test-support configuration where possible. If the operation is exposed via HTTP, add a controller-level test without test-managed transactions so the test matches production session boundaries.

## 5. Add It to a Server

Add the operation module as a dependency of `server-streampack` or your own server distribution. `OperationService` discovers operation beans through Spring scanning.
