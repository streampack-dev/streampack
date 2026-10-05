# Web console implementation brief — issue #115

Mirrors the consolidated [GitHub issue](https://github.com/streampack-dev/streampack/issues/115). Built as described, with the differences below; the shipped contract is in [Blog HTTP API: Admin Web Console](../reference/blog-http-api.md#admin-web-console). This replaces the earlier local request/reply-only design review.

## As built

- **Live authority is opt-in per message.** The shared chain refreshes a principal only for
  messages carrying `Provenance.LIVE_AUTHORITY` (the web console sets it), through
  `LiveAuthorityService`. Refreshing every principal would turn principals built without a stored
  user (bridges, tests, synthetic system messages) anonymous; other credential-based transports opt
  in the same way. An account no longer active is refused before any operation runs.
- **Sanitized failures are opt-in too.** `Provenance.REPORT_FAILURES` (set by the console) turns an
  unexpected exception into a correlated `error` result; other messages fail as before.
- **No in-flight command cap.** Admission is the per-admin command rate only.
- **The timeout fall-through (item 4 below) is fixed**: an operation that runs past its timeout
  ends the chain with an error (`OperationService.TIMED_OUT`), however the interrupt surfaces, as
  a throttled one does (#116). Interrupting still doesn't roll back what it had already done, and
  the error says it may have partly run.
- The child-event header fix (item 3) landed with #99 (`MessageHeaders.forFollowUp`).
- Code: `service-blog/.../webconsole/` (controller, stream registry, delivery, egress subscriber,
  adapter, access, properties); `lib-core` (`Protocol.WEBCONSOLE`, `LiveAuthorityService`, the two
  headers, `LoggingEgressSubscriber`); `service-bridge` and `operation-tell` for the guards.

## Objective and current scope

Give signed-in administrators a web console for the existing text-command system, initially for factoid work alongside article editing. Preserve the architecture's ability to support other web applications later, including Startrader and other tick-driven output. Do not implement a Startrader UI in this issue.

**This body consolidates the current decisions and supersedes the earlier request/reply-only examples in this issue and its comments. Streaming is included in the first release.**

- `POST /admin/console` submits one addressed line through `EventGateway.send` and returns `202` with a server-generated correlation ID.
- `GET /admin/console/stream` uses server-sent events (`SseEmitter`) to deliver output to all of that administrator's connected windows.
- Use a stable per-user provenance, not a per-request, per-token, or per-window destination.
- Identity comes from the authenticated web request; **authority is queried live on entry to the command chain for every command**.
- The initial admin console is for ADMIN and SUPER_ADMIN. This is the access policy of these endpoints, not a blanket requirement that all future web applications or operations require admin.
- The frontend is ui-pudl only: https://github.com/streampack-dev/ui-pudl/issues/169. This issue owns the backend contract and documentation.

This replaces #100. Related: #114 (factoid editing while writing) and #116 (throttling must terminate a claimed command).

## Implementation shape

Add the HTTP controller, DTOs, console adapter, stream registry, and egress subscriber alongside the existing administration API in `service-blog`. Add `Protocol.WEBCONSOLE` in `lib-core`, with TEXT_BASED and CONVERSATIONAL traits like CONSOLE. No new Maven service is needed.

Use `WEBCONSOLE`, not `HTTP_CONSOLE`: protocol names become URI schemes and underscores are invalid there. Keeping a distinct protocol also prevents `ConsoleEgressSubscriber` from printing browser output to server stdout.

`WebConsoleEgressSubscriber` should extend the existing `EgressSubscriber`, match WEBCONSOLE and service `web`, and hand rendered results to the stream registry. `WebConsoleAdapter` exposes the capability through the existing `ProtocolAdapter`/`/features` pattern and routes direct `sendReply` calls through the same delivery component. Have one delivery path per emission; do not publish a second copy of an operation result.

Reuse the existing operation chain, parsers, group configuration, transformers, and operation permissions. A text console exposes text-capable operations; it does not magically expose every typed-request operation. ADDRESSABLE is separate from the addressed-message header; current operations do not read the protocol traits, so adding that trait is not required.

## Authentication, live authority, and attribution

The intended model is: “The web request is authenticated as dreamreal; when this command enters the chain, what permissions does that account currently have?”

1. Validate the web credential using the existing authentication mechanisms. Use the authenticated immutable user ID to identify the account; do not accept a username, role, or principal from the JSON body.
2. Check current active/admin access at the HTTP boundary for an appropriate 401/403 response and before admission/logging work.
3. At shared command-chain entry, resolve the current active user and replace the incoming principal with its current authority before evaluating or executing operations. Do not make a JWT role claim, cached connection principal, or the earlier controller check the authoritative command permission check. A role/status change while a command is queued must be respected.
4. Keep the resolver reusable for other authenticated command transports/applications. Preserve existing anonymous/system-message semantics; do not introduce an unconditional admin requirement in the shared chain.
5. The operations receive that refreshed principal. Do not promote ADMIN to SUPER_ADMIN: existing command-specific requirements continue to apply. Preserve the authenticated user's attribution for factoid updates and self-karma checks.

Implementation can use `UserRepository.findActiveById` and the existing principal conversion. There is no need to introduce a new roles/ACL framework. Propagate the enriched message consistently through processing and egress rather than refreshing a local variable while leaving stale provenance downstream.

Admin roles are granted rarely, but live authority is an architectural requirement, not merely a precaution for frequent demotions. Test suspension/deletion/demotion with an already-issued credential, including a change between admission and execution.

## Stable provenance and routing

Use a canonical server-derived destination:

```kotlin
Provenance(
    protocol = Protocol.WEBCONSOLE,
    serviceId = "web",
    replyTo = "users/${authenticatedUserId}",
    user = authenticatedPrincipal, // refreshed at chain entry
    correlationId = commandId,
)
```

The UUID-based address refines the earlier `webconsole://web/<username>` suggestion so renaming a user cannot break persistent subscriptions or reassign a former username's destination. Use the current username/display name for presentation. Set `Provenance.ADDRESSED = true`.

The same admin's windows share context, operation settings, and throttles; different admins have different contexts. This is an application destination, not an HTTP session. The request body cannot override protocol, service, user, destination, or internal message headers.

Route outbound notifications by canonical destination, not by requiring `provenance.user` to be populated: `EgressNotifier` decodes stored URIs and produces a provenance without a principal. Resolve and authorize the destination's owner before browser delivery. A client can subscribe only to their own destination; there is no arbitrary user/channel selector on the stream URL.

Preserve correlation for output causally associated with a command where provenance is copied/rebuilt. Unsolicited poller/timer output legitimately has no command correlation ID. A correlation ID is not an authorization token or a stream-routing key.

## HTTP and SSE contract

Submit bare command syntax, as the stdin console does after marking input addressed:

```http
POST /admin/console
Content-Type: application/json

{"line":"aho-corasick"}
```

```http
202 Accepted
Cache-Control: no-store

{"correlationId":"<server-generated UUID>"}
```

Other examples: `aho-corasick is ...`, `calc 2+2`, `foo++`. Do not add a second parser or blindly strip chat prefixes. The original `~factoid aho-corasick` example was not the raw console command syntax.

202 means ingress accepted the message, not that the command succeeded or a remote destination received its output. Generate the ID before dispatch. Results may reach SSE before the POST response; the client must tolerate this ordering.

Stream response headers:

```http
Content-Type: text/event-stream
Cache-Control: no-store
X-Accel-Buffering: no
```

Send a `ready` event after registration so the frontend can connect before enabling submission. Send output as named `result` events, using the SSE JSON serializer rather than interpolating raw text into the SSE framing:

```text
event: result
data: {"correlationId":"<command UUID or null>","status":"success","text":"..."}
```

Statuses are `success`, `error`, and `unhandled`; omit text for an unhandled result. A command can produce zero, one, or several result events. Do not treat the first event as a universal completion marker. An explicit NotHandled result reaching egress is forwarded; internal `Consumed` outcomes remain silent. Do not turn every background event into a fabricated user-command failure. The UI should describe unhandled output as “No matching response,” not “No side effects occurred.”

Heartbeats are SSE comments or a separately documented heartbeat event, not command results. If event IDs are provided, distinguish them from command correlation IDs: multiple result events can share one command ID.

Return 401/403 for authentication/access failures, 400 for invalid input, 413 for excessive body size, 415 for non-JSON submission, and 429 for admission throttling before dispatch. Once a request has been accepted, operation errors travel through egress/SSE. Ensure an unexpected failure processing a submitted web command has a sanitized, correlated error path instead of silently disappearing into an asynchronous error channel. Preserve ordinary Consumed/no-output behavior.

No automatic POST retries, exactly-once claim, or cancellation claim. A lost response/disconnection does not prove a mutation failed. Do not invent delivery confirmation for external transports whose egress failures are currently caught and logged.

## Stream lifecycle and resource bounds

Use an in-memory registry keyed by immutable user ID, with a bounded collection of emitters per user. This version assumes one backend instance; document that requirement. Multi-instance fan-out/shared admission state is separate work.

- Fan out each emission once to each live stream owned by that admin. Preserve enqueue order within a connection; do not promise completion order across independently executing commands.
- Use bounded per-connection queues and bounded writer resources. Egress dispatch is synchronous today; a slow browser must not block operation processing, pollers, or unrelated chat delivery. Enqueue promptly and perform socket writes outside the shared egress caller.
- On queue overflow or persistent write failure, disconnect that slow stream and clean it up. Do not accumulate unbounded output or silently discard arbitrary frames while pretending the stream is healthy.
- Remove registrations on completion, timeout, disconnect/error, and application shutdown. Heartbeats detect dead connections. Bound output frame size as well as queue item count; never silently truncate a command or claim a truncated result is complete.
- Authenticate every stream opening/reconnection. Record credential expiry, check expiry/current active admin authority before delivering data, and recheck on heartbeat so idle revoked streams close as well. A long-lived emitter must not preserve authority forever. No sensitive data is sent after a failed check.
- For v1, disconnected output is not durably stored for replay. Reconnection starts a fresh live stream; do not promise Last-Event-ID recovery. ui-pudl must indicate possible gaps and must not resubmit commands to fill them.
- The frontend establishes a ready stream before submitting. The backend may accept a valid POST even with no open stream; output emitted without listeners is dropped under the documented no-replay policy. A stream can also disappear during execution; that does not cancel the command. The UI must show connection state and disable submission until ready.

Suggested configurable starting values: a 15-second heartbeat, at most 4 streams per admin, 100 queued events per connection with a total byte budget, and 30 submitted commands/minute per admin. These are implementation defaults to validate, not existing system behavior. Reuse `ThrottleService` for admission where suitable, keyed by user ID rather than session/token/correlation ID. Stream connection attempts also need bounds.

If adding an in-flight command cap, release permits on actual root-handler completion/exception or failure to enqueue, not when the POST returns 202. Descendant events must not inherit/release the root's admission bookkeeping. No distributed queue or new broker is needed for this version.

Spring documents blocking writes and heartbeat-based disconnect detection in its [MVC asynchronous request reference](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-ann-async.html).

## Browser security, logging, and channel behavior

`WebSecurityConfiguration` currently permits all requests and disables CSRF; an `/admin` path and OpenAPI security annotation are not access enforcement. Protect both endpoints explicitly.

Support the existing HttpOnly cookie authentication for native browser EventSource. Cross-origin use needs exact trusted origins and credentialed CORS; native EventSource does not provide arbitrary Authorization/custom-header options. Do not put access tokens in query strings. A fetch-based SSE client is an alternative if bearer headers are required. See [EventSource's constructor contract](https://developer.mozilla.org/en-US/docs/Web/API/EventSource/EventSource).

For the state-changing POST, require JSON and an explicit cookie-CSRF defense. A scoped custom `X-Web-Console: 1` header plus exact trusted Origin/Referer validation is a suitable implementation; add only the needed header to CORS. Reject untrusted/null origins and cookie-authenticated POSTs without a verifiable source. Do not require that custom header on native EventSource's GET. Preserve/document cookie-versus-bearer precedence and test conflicting credentials. SameSite cookies help but should not be the sole check. [OWASP guidance](https://cheatsheetseries.owasp.org/cheatsheets/Cross-Site_Request_Forgery_Prevention_Cheat_Sheet.html).

Validate one nonblank command line, reject CR/LF/NUL, and apply both string and HTTP-body limits before expensive work. A starting point is 4,096 characters and a bounded JSON body large enough for escaping; preserve internal spacing and do not truncate. Render command/output history as plain escaped text. Factoids can contain content authored outside the admin UI. No HTML or terminal escape execution; avoid durable raw command history containing credentials.

Keep existing redacted ingress logging as an operational record. Do not log raw request bodies again in the controller or error handler. Generic outbound logging has no equivalent secret redaction today: for WEBCONSOLE, record outcome metadata and skip generic response-body persistence until an explicit safe output policy exists. Document the resulting effect on log-derived Ask context. Existing logging is best-effort, not an immutable audit ledger.

Keep WEBCONSOLE out of the public log browser, including direct lookup routes. Prevent bridge copying of console ingress and reject bridge pairs involving web-console destinations: bridge copying runs before command handlers and forwards raw input, bypassing log redaction. Keep explicit admin-directed messaging/configuration of actual chat channels available.

No arbitrary source-channel override is needed. Existing configuration commands already accept target provenance arguments. `tell` currently sends immediately and can use a full destination URI; preserve destination override semantics and do not duplicate the remote message into a fabricated local delivery acknowledgment. For WEBCONSOLE input, reject relative `tell` targets with a clear instruction to use a canonical full URI; username-to-destination convenience resolution is not required for v1. Reject malformed/unsupported destinations clearly. Intentionally addressed output to a web-console owner may arrive without that owner being the initiating command user.

Do not automatically add WEBCONSOLE to every hard-coded protocol allowlist. For example, incidental URL-title fetching currently has its own allowlist. Document supported behavior rather than assuming protocol traits enable every operation.

## Factoid correctness and related fixes

1. **Terminal throttling: #116.** A recognized but throttled operation must return a terminal error; it must not fall through to factoid assignment. Example: a throttled `ask why is foo` must never define the factoid `ask why`. Coordinate/land #116 rather than duplicating an incompatible fix here. This is a release prerequisite.
2. **Reference rendering.** Factoid see-also output can contain `{{ref:example}}`. The existing `EgressSubscriber.handleMessage` renders these before `deliver`. With the streaming architecture, subclassing it and retaining the bare-command signal character already provides the intended `example` rendering. Test the actual SSE output. This supersedes the earlier comment's recommendation to add rendering to a synchronous HTTP response; do not duplicate the renderer unnecessarily.
3. **Independent child-event headers.** Both factoid setters copy all parent headers into `FactoidUpdatedEvent`. A database-free probe using the existing gateway and Spring Integration 7.0.4 reproduced a child stealing its parent's request/reply response; removing `replyChannel`/`errorChannel` restored the correct parent response. This is a verified messaging-mechanism bug, not yet a complete factoid integration reproduction. The async `send` console has no waiting HTTP reply to steal, so do not misrepresent this as an SSE prerequisite. If touched here, add a separate regression and strip transport bookkeeping while retaining intentional provenance/correlation; otherwise record a follow-up for existing request/reply callers.
4. **Timeout distinction.** The current InterruptedException path can also fall through; #116 covers throttling specifically. Keep this visible as a related dispatcher defect and explicitly handle or track it. An operation interrupt does not guarantee cancellation/rollback, and streaming does not repair parser fallthrough.

## Acceptance criteria

- [ ] POST and stream are denied to unauthenticated/non-admin users; invalid requests never execute operations. ADMIN remains unable to execute SUPER_ADMIN-only commands.
- [ ] Live chain-entry authority is verified with an old credential and a role/status change after HTTP admission. No stale principal survives into operation checks. Authenticated web identity is never replaced with a super-admin.
- [ ] Real factoid set/get works through POST + SSE, records the correct author, preserves both setter syntaxes, handles locked factoids, and renders existing see-also references without `{{ref:...}}` tokens.
- [ ] Admission throttling is per immutable user across tabs/tokens. #116 prevents throttled Ask input from creating a factoid. Nonmatches still allow normal parser traversal.
- [ ] Two admins cannot subscribe to/read each other's streams by guessing IDs or modifying parameters. Each admin's multiple windows receive the intended fan-out, with stable provenance across reconnects.
- [ ] `EgressNotifier.send` to a canonical web-console URI reaches the owner's streams even with no principal/correlation on the notification. This proves the path can accept later poller/game output without implementing Startrader here.
- [ ] Immediate, delayed, multiple, unhandled, and unsolicited results are covered. Correlation survives relevant descendants; unsolicited output uses null correlation. Consumed events remain silent. Results arriving before the 202 response are handled by the documented client contract.
- [ ] Reference rendering and text escaping are tested on the actual SSE wire format, including multiline output. No result is accidentally sent to stdin-console stdout.
- [ ] Disconnect, expiry, revocation, emitter failure, overflow, connection limits, and shutdown clean up resources. A slow stream cannot stall other streams, egress, or command execution. A reconnect receives a fresh stream without a replay guarantee.
- [ ] Submitted-command exceptions have a sanitized asynchronous error path; no stack trace, token, principal, or arbitrary serialized object leaks to the client. Redirected messages are delivered only to their intended destination, without false delivery confirmation.
- [ ] Cookie/bearer precedence, POST CSRF checks, SSE credentialed CORS, malformed/oversized input, log redaction/public exclusion, and bridge-copy isolation are covered.
- [ ] Tests include the production ExecutorChannel behavior. `TestChannelConfiguration` replaces ingress with DirectChannel and cannot alone establish async correctness. HTTP/database tests follow `@ResetDatabaseBeforeEach`, not test-managed transactions.
- [ ] Both endpoints and event schemas are documented in OpenAPI/API references; `/features` advertises the capability consistently. Include nginx buffering/timeout guidance and the single-instance/no-replay assumptions for ui-pudl integration.

## Suggested work order and source map

1. Coordinate #116; characterize existing async outcomes and preserve shared dispatcher behavior with regression tests.
2. Implement protocol, canonical addressing, live authority resolution, controller input/admission, and DTOs.
3. Implement bounded stream registry, lifecycle/security checks, adapter, and egress subscriber using existing rendering.
4. Add the focused integration/security/lifecycle tests, then update OpenAPI and deployment/client documentation.

Starting points:

- `lib-core/.../integration/{EventGateway,EgressSubscriber,EventChannelConfiguration}.kt`
- `lib-core/.../service/OperationService.kt`, `ThrottleService.kt`, `UserResolutionService.kt`
- `lib-core/.../model/{Protocol,Provenance,UserPrincipal,OperationResult}.kt`
- `lib-core/.../repository/UserRepository.kt`
- `lib-web/.../controller/UserAwareController.kt`
- `service-blog/.../controller/{WebSecurityConfiguration,BlogProtocolAdapter,LogController}.kt`
- `service-console/.../{ConsoleAdapter,ConsoleEgressSubscriber}.kt`
- `operation-factoid/.../operation/{SetFactoidOperation,SetFactoidVerbOperation,GetFactoidOperation}.kt`
- `lib-polling/.../service/EgressNotifier.kt`
- `service-features/.../controller/FeaturesController.kt`

Out of scope: Startrader UI/domain integration, non-admin application access policies, generative token-by-token output, WebSockets, durable replay/idempotency, distributed stream registries, a new authorization framework, and universal cancellation. An OperationResult is a finished operation output today; partial generative output is a separate protocol design. Keep this feature extensible without implementing those projects inside #115.
