# ActiveJ JSON-RPC over WebSocket

JSON-RPC 2.0 over RFC 6455 WebSocket for the ActiveJ platform — the **second transport** for the
`JsonRpcTransport` SPI, and the first whose two directions are genuinely symmetric. A
`JsonRpcWsServlet` mounts a dispatcher behind a WebSocket route; each accepted upgrade becomes a
`JsonRpcWsSession` the server can enumerate, broadcast to and initiate calls on; the client endpoint
is `JsonRpcWsTransport.connect(...)`, usable by a plain `JsonRpcClient`. One wire dialect serves
both: a browser's `WebSocket` needs no ActiveJ code at all, because every document on the wire is
plain JSON-RPC 2.0 in a TEXT message.

Built on `activej-http`'s WebSocket implementation (reassembly, UTF-8 validation, size cap, close
handshake and ping/pong are all core-http's) and `activej-jsonrpc`'s envelope + service layer. This
module adds **no framing, no streaming model and no connection model** — it adapts core-http's
existing RFC 6455 implementation to the existing transport SPI, exactly the relationship the HTTP
transport has to `AsyncServlet`. No `core-*` module is touched.

This module is a part of the ActiveJ platform.

## Maturity

**Experimental — `extra/` modules are not production-ready.** This module lives under `extra/`, which
is profile-gated and whose per-module maturity is an explicitly open question for the platform. Treat
it as experimental: the API may change without a deprecation cycle, and the default `mvn verify` does
not build or test it at all.

## The framing rule — one document per TEXT message

> **One WebSocket TEXT message carries exactly one complete JSON-RPC 2.0 document — a single
> request, notification, response, or a batch array — and one document never spans messages.**

That is the whole wire contract. Outbound documents are emitted as **one unfragmented TEXT frame**;
inbound fragmented messages are reassembled by core-http before the transport sees a byte, so a
document fragmented across several frames arrives as one contiguous array. A BINARY message is
refused with close code `1003` (this transport speaks TEXT only), an empty TEXT message with `1002`.
No `Sec-WebSocket-Protocol` subprotocol is defined or required.

## The two-tier size bound

Two size bounds are active on every message; the **stricter one is effective**:

| Tier | Bound | Default | Fires | Behaviour |
|---|---|---|---|---|
| Transport | `HttpServer.maxWebSocketMessageSize` (server) / `HttpClient.maxWebSocketMessageSize` (client) | `1mb` each | **during** accumulation, fragment by fragment | close `1009` (message too big); no allocation-first |
| Envelope | `JsonRpcLimits.MAX_BODY_SIZE` | `1mb` | after assembly, in the decoder | `-32001 Request too large` error document |

With equal defaults the **transport tier wins**: a document over the transport cap dies `1009`
before the decoder ever sees it, and `-32001` is unreachable. A deployment that wants the envelope
answer sets the transport tier **strictly above** the envelope tier — e.g. a server's
`withMaxWebSocketMessageSize` to `2mb` while `JsonRpcLimits.MAX_BODY_SIZE` stays `1mb` — so the
oversized document reaches the decoder and answers `-32001`. The conformance suite does exactly this
in both directions, which is how `envelope-too-large` replays with an empty skip set.

**No `ApplicationSettings` key exists in this module** (Decision 12, mirroring the HTTP transport):
the two tiers above are the only knobs, and both already exist on the components this module
composes. Raising one without the other is the entire two-tier story.

## Ping/pong and liveness — stated plainly

Ping and pong are **core-http's business**, unchanged. Pongs are answered **automatically**, core-http
exposes **no public ping API**, and this module adds **no keep-alive scheduler**. Liveness therefore
rests on exactly this set, and nothing more:

- the **peer's pings**, answered automatically by core-http;
- **write failures**, detected when a `send` cannot complete;
- the **close handshake**, RFC 6455's own;
- the **host server's connection sweep** — see the operational rule below.

A deployment that wants its own keep-alive must either ping from the peer (any RFC 6455 client can
send a ping frame) or use one of the three liveness signals above. There is no ActiveJ-side ping
method to call, by design.

## ⚠ The `readWriteTimeout` operational rule

`HttpServer.readWriteTimeout` defaults to **60 seconds** and sweeps upgraded connections: the
sweep's timestamp is refreshed only at connection-pool switches, **not by frame traffic**, so an
idle-but-open WebSocket connection is closed ~60 s after upgrade no matter how much data it carries.
core-http documents the identical behaviour for SSE.

A deployment serving long-lived sessions **must set the host server's read/write timeout to `0`**:

```java
HttpServer.builder(reactor, routingServlet)
	.withReadWriteTimeout(Duration.ZERO)   // FR-096: the 60 s default sweeps upgraded connections
	.build();
```

The transport cannot and does not override the host server — this is a property of the `HttpServer`
the route is mounted on, not of this module.

## Reconnection — deliberately absent

Automatic reconnection with call replay is **not implemented** and will not be. The reason is not
effort but semantics: a JSON-RPC `id` correlation cannot survive a new connection without application
knowledge — an answer that arrives after a reconnect belongs to a different incarnation of the
session, and replaying an in-flight call risks double-executing a side effect. Re-establishment and
re-issue are the **application's** job: wire the transport, and when the connection dies, reconnect
and re-issue whatever your application's semantics require. This module only guarantees that every
promise held across the drop completes exceptionally with an explicit cause, so nothing is stranded
and nothing is silently replayed.

## Server — serve a dispatcher, enumerate sessions, push and call

The server side is a `JsonRpcWsServlet` mounted in a `RoutingServlet`, backed by one
`JsonRpcDispatcher` — the **single** service table every session dispatches inbound calls to.

```java
JsonRpcDispatcher dispatcher = JsonRpcDispatcher.builder(reactor)
	.withService(UserApi.class, new UserApiImpl())
	.build();

JsonRpcWsServlet wsServlet = JsonRpcWsServlet.builder(reactor, dispatcher).build();

HttpServer server = HttpServer.builder(reactor, RoutingServlet.builder(reactor)
		.withWebSocket("/ws", wsServlet)
		.build())
	.withReadWriteTimeout(Duration.ZERO)   // long-lived sessions: the 60 s default sweeps upgrades
	.build();
```

Each accepted upgrade becomes a `JsonRpcWsSession` — a per-connection `JsonRpcClient` whose peer
handler is the servlet's dispatcher, which is the **whole** server→client direction. The servlet
exposes the live sessions as a reactor-confined snapshot and a broadcast convenience:

```java
wsServlet.sessions();                                   // a snapshot, one entry per open connection
wsServlet.broadcast(UserEvents.class, e -> e.changed(new User(42)));   // a push to every client

// or address one session:  the server calls the client and awaits the answer
for (JsonRpcWsSession session : wsServlet.sessions()) {
	session.proxy(UserEvents.class).decide("blue").whenResult(answer -> ...);
	session.inFlightCount();                            // calls the server initiated, awaiting answers
	session.closeEx(new AsyncCloseException());         // idempotent; purges every in-flight call
}
```

A broadcast's per-session failures are **contained**: one dead connection's send failure is routed
to that session's failure handling and never aborts the iteration. The one failure not contained is
a `JsonRpcContractException` from `session.proxy(clientInterface)` — a broken interface is the
broadcaster's own programming error and every session's proxy refuses it identically, so it
propagates to the broadcast caller at the first session rather than producing one failure-handler
report per session that no operator can act on.

Session cardinality **is** open-connection cardinality: the registry adds no second bound, and the
connection tier's own limits (file descriptors, the host server's sweeps) govern it. Admission
control is core-http's `onRequest` seam — override it (or decorate with an auth servlet) to answer a
non-`101` before any session exists.

Every method is reactor-confined: call `sessions()`/`broadcast`/`proxy`/`closeEx` only on the
connection's reactor thread; a publisher on another thread hops explicitly
(`reactor.post(...)` / `Reactor.submit`).

## Client — call the server, and answer the server's calls

The client endpoint is `JsonRpcWsTransport.connect(...)` plus the exact wiring feature 012
documented, with the WS transport substituted:

```java
JsonRpcDispatcher clientDispatcher = JsonRpcDispatcher.builder(reactor)
	.withService(UserEvents.class, new UserEventsImpl())   // what the server may call
	.build();

JsonRpcWsTransport.connect(reactor, httpClient, HttpRequest.get("ws://host/ws").build())
	.whenResult(transport -> {
		JsonRpcClient client = JsonRpcClient.builder(reactor, transport)
			.withPeerHandler(clientDispatcher)             // the whole server→client direction
			.build();
		UserApi api = client.proxy(UserApi.class);
		api.getUser(42).whenResult(user -> ...);
	});
```

`withPeerHandler(dispatcher)` is what makes the connection bidirectional: without it, a server that
initiates a call gets the honest `-32601 Method not found` from the default peer handler. The
independent `id` spaces per direction are a free consequence of the two sides being two
`JsonRpcClient`s — same-numbered calls in opposite directions never collide.

A **browser** needs none of this: connect a `WebSocket` to the same URL and speak the same documents.
The runnable, self-checking program in
[`JsonRpcWsEndToEndExampleTest`](src/test/java/io/activej/jsonrpc/transport/ws/JsonRpcWsEndToEndExampleTest.java)
shows the Java side and carries the raw-JavaScript equivalent in its Javadoc.

## The two directions share one conformance suite

The 30 conformance vectors of feature 010 replay over a real WebSocket connection in **both**
directions — client→server (`JsonRpcWsConformanceTest`) and server→client
(`JsonRpcWsBidirectionalConformanceTest`) — with an empty skip set. A transport that could not carry
the suite both ways would not be the bidirectional transport this module claims to be.

## Build and test

The `extra` profile is **mandatory** — without it this module is not in the Maven reactor at all.

```bash
# once: publish the conformance test-jar the module consumes at test scope
mvn -P extra -pl extra/cloud-jsonrpc -am install -DskipTests

# the module plus its upstream modules
mvn -P extra -pl extra/cloud-jsonrpc-ws -am test

# a single test class. -Dsurefire.failIfNoSpecifiedTests=false is REQUIRED whenever -Dtest= is
# combined with -am: Surefire matches the pattern against every upstream module first and fails
# before reaching this one
mvn -P extra -pl extra/cloud-jsonrpc-ws -am test \
    -Dtest=JsonRpcWsConformanceTest -Dsurefire.failIfNoSpecifiedTests=false

# the whole extra tree
mvn -P extra verify
```

## What this module is not

| You want | Where it lives |
|---|---|
| An annotated Java interface, a dispatcher, a client proxy | `extra/cloud-jsonrpc` — the service layer |
| HTTP POST transport, a servlet and client transport for it | `extra/cloud-jsonrpc-http` |
| Per-call timeouts, an in-flight bound | not yet — feature 09, unshipped; `inFlightCount()` is the observed value a later bound would check |
| Automatic reconnection with call replay | not here — documented refusal above |
| A DI module, JMX, a launcher | `extra/launchers/jsonrpc` — mounts the WebSocket endpoint beside HTTP POST on one `HttpServer` |
| Frame-level access to the socket | encapsulated — this module speaks the message-level `IWebSocket` API only (FR-011) |
