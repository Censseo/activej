# ActiveJ JSON-RPC over framed TCP

JSON-RPC 2.0 over a raw persistent TCP connection for the ActiveJ platform — the **third transport**
for the `JsonRpcTransport` SPI, and the one with nothing underneath it. `JsonRpcTcpServer` accepts
connections and turns each into a `JsonRpcTcpSession` the server can enumerate, broadcast to and
initiate calls on; the client endpoint is `JsonRpcTcpTransport.connect(...)`, usable by a plain
`JsonRpcClient`. One wire dialect serves both directions, and it is thin enough to speak from a
shell: one LF-terminated JSON-RPC document in, one LF-terminated JSON-RPC document out.

Built on `activej-net` (`TcpSocket`, `AbstractReactiveServer`), `activej-csp`'s existing
LF-terminated framing decoder, and `activej-jsonrpc`'s envelope + service layer. This module adds
**no framing code, no streaming model and no connection model** — the framing is
`core-csp`'s `OfByteTerminated`, the accept loop and the drain are `AbstractReactiveServer`'s, the
correlation table and the close purge are `JsonRpcClient`'s. No `core-*` module is touched, and
`extra/cloud-jsonrpc` gained **zero** main-source changes.

This module is a part of the ActiveJ platform.

## Maturity

**Experimental — `extra/` modules are not production-ready.** This module lives under `extra/`, which
is profile-gated and whose per-module maturity is an explicitly open question for the platform. Treat
it as experimental: the API may change without a deprecation cycle, and the default `mvn verify` does
not build or test it at all.

## The framing rule — one document per line

> **One message on the wire is one complete JSON-RPC 2.0 document — a single request, notification,
> response, or a batch array — encoded in UTF-8 and terminated by exactly one LF byte (`0x0A`). Both
> directions use it. There is no preamble, no handshake, no negotiation: the first bytes on a
> connection are the first document.**

```text
client → server:  {"jsonrpc":"2.0","id":1,"method":"user.get","params":[42]}\n
server → client:  {"jsonrpc":"2.0","id":1,"result":{"name":"Bob","id":42}}\n
```

That is the whole wire contract. Consequences worth stating out loud:

- A conforming JSON text never contains a raw `0x0A` (RFC 8259 §7 escapes control characters inside
  strings) and `JsonRpcEncoder` never emits one — so **a raw LF on the wire is always a boundary**.
- **CRLF is accepted**: a line terminated `\r\n` decodes as if terminated `\n`, because the carriage
  return is insignificant trailing whitespace to the envelope decoder. Emitters on this stack always
  send a bare `\n`. A dedicated test pins this so a decoder regression fails loudly here rather than
  silently changing the wire contract.
- **Multi-line / pretty-printed JSON is not carriable.** A peer sending it finds its first fragment
  answered `-32700 Parse error` (with `id: null`) and the connection stays up — a JSON-level error,
  not a framing violation.
- A line that is **only** the terminator is a framing violation, not an empty document: the
  connection closes. A zero-length document is never legal in this stack (SPI obligation 3), which
  is also why `send(new byte[0])` fails with `IllegalArgumentException` rather than writing a bare
  LF.

The full contract is
[`specs/017-jsonrpc-tcp-transport/contracts/tcp-framing.md`](../../specs/017-jsonrpc-tcp-transport/contracts/tcp-framing.md).

## The two-tier size bound

Two size bounds are active on every inbound message; the **stricter one is effective**:

| Tier | Bound | Default | Fires | Behaviour |
|---|---|---|---|---|
| Transport | `JsonRpcTcpTransport`/`JsonRpcTcpServer` `withMaxMessageSize(MemSize)` | `JsonRpcLimits.MAX_BODY_SIZE` (`1mb`) | **during** accumulation — the framing decoder's own scan | connection closes with `MalformedDataException`; **no buffer of the attempted size is ever allocated** |
| Envelope | `JsonRpcLimits.MAX_BODY_SIZE` (`-DJsonRpcLimits.maxBodySize=…`) | `1mb` | after assembly, in the decoder | `-32001 Request too large` document, **connection survives** |

One boundary precision on the transport tier's number: the decoder's scan fails at
`index == maxSize − 1` when that byte is not the terminator, so the effective bound is
**`maxMessageSize − 1` content bytes** — a document whose content is exactly `maxMessageSize` bytes
is refused before its LF is ever examined. That is `OfByteTerminated`'s own semantics, identical in
both directions and observable only at the exact boundary; the envelope tier, checked after
assembly, applies to the content alone.

⚠ **With equal defaults the transport tier wins and `-32001` is unreachable.** A deployment that
wants the envelope answer sets the transport tier **strictly above** the envelope tier — e.g.
`withMaxMessageSize(MemSize.megabytes(2))` while `JsonRpcLimits.MAX_BODY_SIZE` stays `1mb`. The
conformance subjects do exactly that in both directions, which is how `envelope-too-large` replays
with an **empty** skip set.

**No `ApplicationSettings` key exists in this module** (mirroring both siblings): the two tiers above
are the only knobs, and both already exist on the components this module composes.

## Errors: what closes the connection, and what does not

Framing violations close the connection — there is no honest resynchronisation point once the
boundaries are lost. JSON-level errors are answerable, so they do not.

| Inbound bytes | Classification | Behaviour |
|---|---|---|
| a well-formed document + `\n` | — | the answer + `\n` (nothing at all for a notification) |
| garbage / non-JSON line | JSON level | `-32700 Parse error`, `id: null` — **connection stays up** |
| valid JSON that is not a JSON-RPC object | JSON level | `-32600 Invalid Request` — connection stays up |
| any other envelope-level failure | JSON level | `-32601`/`-32602`/`-32603`/`-32002`/`-32003`/`-32004` — connection stays up |
| `\n` alone (empty line) | framing violation | connection closes with an explicit fixed-string cause |
| more than the transport tier without a `\n` | framing violation | connection closes mid-accumulation with `MalformedDataException` |
| stream ends mid-message | truncation | connection closes with `TruncatedDataException`; partial bytes recycled; **no resynchronisation** |
| stream ends on a message boundary | clean close | `Listener.onClosed(null)` exactly once; in-flight calls fail with `AsyncCloseException` |

Nothing derived from peer content ever reaches a close cause, an exception message or any other
output: close causes are **fixed strings**, and this module emits **no log lines at all** (no
`org.slf4j` import exists in `src/main`, and the boundary test refuses one).

## `Content-Length` framing — a documented refusal

LSP-style `Content-Length:` header framing is **not implemented and will not be** in this module.
This is a decision, not an omission:

- **Two framings on one wire without negotiation is an interoperability bug source.** There is no
  preamble and no handshake here (see below), so a peer cannot discover which framing it is talking
  to; it would have to be configured, and a misconfiguration would look like a hang.
- **JSON Lines costs zero new framing code.** `core-csp` already ships the LF-terminated decoder
  with its bound applied *during* accumulation. A `Content-Length` parser would be a new framing
  state machine in an `extra/` module, which is exactly what the `extra/` guard rails forbid.
- **Readability on the wire is the point.** `printf … | nc` is a supported way to talk to this
  endpoint; a header-framed variant is not.
- The interop value of `Content-Length` framing belongs to a **`stdio` transport**, which is
  deliberately deferred. If that ships, it brings its own framing with it.

A **version or capability handshake** is refused for the same reason: versioning belongs to the
method namespace, not to a preamble nobody can extend compatibly. There are likewise no keep-alive
frames, pings or per-message timers — liveness is write failure, end-of-stream, and optionally
`SocketSettings`' implementation read/write timeouts (off by default).

## Reconnection — deliberately absent

Automatic reconnection with call replay is **not implemented** and will not be. The reason is not
effort but semantics: a JSON-RPC `id` correlation cannot survive a new connection without
application knowledge — an answer arriving after a reconnect belongs to a different incarnation of
the session, and replaying an in-flight call risks double-executing a side effect. Re-establishment
and re-issue are the **application's** job: when the connection dies, reconnect and re-issue whatever
your semantics require. This module guarantees only that every promise held across the drop
completes exceptionally with an explicit cause — in **both** directions — so nothing is stranded and
nothing is silently replayed.

## TLS — composed, not built in

Neither this module nor `extra/launchers/jsonrpc` contains a single TLS-specific line, and there is
no TLS option on any builder here. TLS is composed from what `core-net` already provides:

```java
JsonRpcTcpServer.builder(reactor, dispatcher)
	.withSslListenAddresses(sslContext, executor, new InetSocketAddress(5301))   // AbstractReactiveServer's
	.build();
```

On the client side, wrap the connected socket with `SslTcpSocket` and hand it to
`JsonRpcTcpTransport.of(reactor, socket)` — the transport takes any `ITcpSocket` and owns exactly
that socket. Terminating TLS in front of the process is equally valid. The plaintext endpoint is
plaintext **by design**, which is why the launcher's `jsonrpc.tcp.port` is disabled by default.

## Server — serve a dispatcher, enumerate sessions, push and call

```java
JsonRpcDispatcher dispatcher = JsonRpcDispatcher.builder(reactor)
	.withService(UserApi.class, new UserApiImpl())
	.build();                                       // the contract is validated here, or never

JsonRpcTcpServer server = JsonRpcTcpServer.builder(reactor, dispatcher)
	.withListenPort(5300)
	// .withMaxMessageSize(MemSize.megabytes(2))    // the transport tier; default = JsonRpcLimits.MAX_BODY_SIZE
	.build();
server.listen();
```

Each accepted connection becomes a `JsonRpcTcpSession` — a per-connection `JsonRpcClient` whose peer
handler is the server's dispatcher, which is the **whole** server→client direction:

```java
server.sessions();                                        // a reactor-confined snapshot, one entry per connection
server.broadcast(UserEvents.class, e -> e.changed(42));   // a push to every client; a per-session failure stays with that session

for (JsonRpcTcpSession session : server.sessions()) {
	session.proxy(UserEvents.class).decide("blue").whenResult(answer -> ...);
	session.inFlightCount();                              // calls the server initiated, awaiting answers
	session.closeEx(new AsyncCloseException());           // idempotent; purges every in-flight call, both ways
}
```

One failure is **not** contained per session: a `JsonRpcContractException` from
`session.proxy(clientInterface)`. A broken interface is the broadcaster's own programming error and
every session's proxy refuses it identically, so it propagates to the broadcast caller at the first
session — before any invocation ran — rather than producing one failure-handler report per session
that no operator can act on.

Session cardinality **is** open-connection cardinality: the registry adds no second bound, and the
connection tier's own limits (file descriptors, `ServerSocketSettings.backlog`) govern it. Admission
control is `AbstractReactiveServer`'s inherited `withAcceptFilter(...)`, which refuses a peer before
any session exists. Every method is reactor-confined — call `sessions()` / `broadcast` / `proxy` /
`closeEx` only on the server's reactor thread; a publisher on another thread hops explicitly
(`reactor.post(...)` / `Reactor.submit`).

`server.close()` drains: it stops accepting, fails every live session's in-flight calls, and
completes only once the registry is empty.

## Client — call the server, and answer the server's calls

```java
JsonRpcDispatcher clientDispatcher = JsonRpcDispatcher.builder(reactor)
	.withService(UserEvents.class, new UserEventsImpl())   // what the server may call
	.build();

JsonRpcTcpTransport.connect(reactor, new InetSocketAddress("localhost", 5300))
	.whenResult(transport -> {
		JsonRpcClient client = JsonRpcClient.builder(reactor, transport)
			.withPeerHandler(clientDispatcher)             // the whole server→client direction
			.build();
		UserApi api = client.proxy(UserApi.class);
		api.getUser(42).whenResult(user -> ...);
	});
```

`send`'s promise means **written**, never **answered**; answers are correlated by `id` alone, in any
order. `withPeerHandler(dispatcher)` is what makes the connection bidirectional: without it, a server
that initiates a call gets the honest `-32601 Method not found` from the default peer handler. The
two directions' `id` spaces are independent — both sides may emit `id: 1` concurrently.

A shell needs none of this:

```bash
$ printf '{"jsonrpc":"2.0","id":1,"method":"user.get","params":[42]}\n' | nc localhost 5300
{"jsonrpc":"2.0","id":1,"result":{"name":"Bob","id":42}}
```

That exchange is not a claim — the runnable, self-checking
[`JsonRpcTcpEndToEndExampleTest`](src/test/java/io/activej/jsonrpc/transport/tcp/JsonRpcTcpEndToEndExampleTest.java)
replays it byte for byte through a plain blocking `java.net.Socket` on every build.

## From the turnkey launcher

`extra/launchers/jsonrpc` mounts this endpoint beside the HTTP POST and WebSocket routes on one
launcher instance, behind the config key `jsonrpc.tcp.port` — **absent or empty means no socket is
opened**, and no `JsonRpcTcpServer` is even constructed. See
[`extra/launchers/jsonrpc/README.md`](../launchers/jsonrpc/README.md) for the key, its
disabled-by-default rationale and the multi-worker wiring.

## The two directions share one conformance suite

The 30 conformance vectors of the JSON-RPC envelope replay over a **real socket** in both
directions — client→server (`JsonRpcTcpConformanceTest`) and server→client
(`JsonRpcTcpBidirectionalConformanceTest`) — with an **empty skip set** in each, and the
correlation test running in both. The harnesses and the vectors are consumed unmodified from
`activej-jsonrpc`'s test-jar.

## ⚠ `AsyncTcpSocketNio.debugReadOffset` does not prove fragmentation robustness

The root POM's Surefire configuration sets `-DAsyncTcpSocketNio.debugReadOffset=1`, and it is
tempting to read that as "the whole suite is already run against fragmented reads". It is not:

- the property names **`AsyncTcpSocketNio`**, a class that no longer exists — `core-net`'s socket
  reads `TcpSocket.debugReadOffset`, so the Surefire property as spelled reaches nothing;
- and even when spelled correctly, the setting shifts the **offset** into the read buffer, not the
  **size** of the read. It exposes offset-arithmetic bugs. It does not chop a message into pieces.

So a transport's framing is **not** exercised by that property, and this module does not rely on it.
`JsonRpcTcpFragmentationTest` proves the property deterministically instead: one document written
**one byte at a time**, and one document **split at every internal byte boundary**, each through a
real socket, each answered correctly. A failure there is the framing bug, unambiguously — no timing,
no luck.

## Measured: framed TCP vs HTTP POST, same dispatcher

**Measured 2026-08-20** with
[`TransportOverheadHarness`](src/test/java/io/activej/jsonrpc/transport/tcp/baseline/TransportOverheadHarness.java)
— the same request document, the same `JsonRpcDispatcher` and the same service implementation
carried once over this transport and once over `JsonRpcServlet` + a real `HttpServer`/`HttpClient`,
both on loopback. The harness **reports and never asserts**: no wall-clock threshold is a build gate
(ADR-029).

| Figure | Value |
|---|---|
| Request shape | `{"jsonrpc":"2.0","id":1,"method":"test.add","params":{"a":2,"b":3}}` |
| Regime | 5 000 warm-up round trips per leg; 13 alternating segments × 5 000 timed round trips per leg |
| Framed TCP | median ≈ **18 313 ns/op** (per-segment spread [17 494, 23 840] ns/op) |
| HTTP POST | median ≈ **21 041 ns/op** (per-segment spread [19 723, 33 701] ns/op) |
| **Median of per-segment ratios (TCP / HTTP)** | **0.870 → HTTP costs ≈ +15 % per round trip** (ratio spread [0.707, 0.922]) |
| Directional consistency | TCP faster in **13/13** segments; five repeat runs gave ratio medians **0.868–0.884** and 13/13 every time |
| Verdict | the absolute ns/op bands **straddle** on this shared machine — the per-leg figures are *indistinguishable at this precision* (WI-17); the per-segment **ratio** is the stable figure |

Read it honestly: ~18–21 µs is loopback and selector cost on a shared 16-CPU sandbox, not a
deployment's latency — both legs pay it identically, which is why the ratio survives while the bands
straddle, and which also **dilutes** the protocol difference. The HTTP leg was measured in its most
favourable configuration (client keep-alive raised to 30 s, one pooled connection for the whole run);
with `HttpClient`'s shipped default of `0` every call would additionally pay a fresh TCP connect, so
≈15 % is a **lower bound**. Full environment, threats to validity and reproduction:
[`specs/017-jsonrpc-tcp-transport/research.md`](../../specs/017-jsonrpc-tcp-transport/research.md)
§ Results.

## Build, test, measure

The `extra` profile is **mandatory** — without it this module is not in the Maven reactor at all.

```bash
# once: publish the conformance test-jar for the new module
mvn -P extra -pl extra/cloud-jsonrpc -am install -DskipTests

# compile + test the module
mvn -P extra -pl extra/cloud-jsonrpc-tcp -am test

# the conformance suites (the flag is OBLIGATORY with -am under Surefire 3.5.0)
mvn -P extra -pl extra/cloud-jsonrpc-tcp -am test -Dtest=JsonRpcTcpConformanceTest -Dsurefire.failIfNoSpecifiedTests=false
mvn -P extra -pl extra/cloud-jsonrpc-tcp -am test -Dtest=JsonRpcTcpBidirectionalConformanceTest -Dsurefire.failIfNoSpecifiedTests=false

# the launcher co-mount
mvn -P extra -pl extra/launchers/jsonrpc -am test

# the TCP-vs-HTTP overhead comparison (reports, never asserts; not Surefire-collected)
mvn -P extra -pl extra/cloud-jsonrpc-tcp -am test-compile
mvn -P extra -pl extra/cloud-jsonrpc-tcp exec:java -Dexec.classpathScope=test \
    -Dexec.mainClass=io.activej.jsonrpc.transport.tcp.baseline.TransportOverheadHarness

# the gate that actually covers this module
mvn -P extra verify
```

`mvn -T1C verify` does **not** build this module — a green default build says nothing about it.

## What this module is not

| You want | Where it lives |
|---|---|
| An annotated Java interface, a dispatcher, a client proxy | `extra/cloud-jsonrpc` — the service layer |
| HTTP POST transport, a servlet and a client transport for it | `extra/cloud-jsonrpc-http` |
| WebSocket transport, duplex over one HTTP upgrade | `extra/cloud-jsonrpc-ws` |
| `Content-Length` (LSP-style) framing, or a `stdio` transport | not here — documented refusal above; `stdio` is deferred |
| A version or capability handshake | not here — documented refusal above |
| Automatic reconnection with call replay | not here — documented refusal above |
| Per-call timeouts, an in-flight bound | not yet — feature 09, unshipped; `inFlightCount()` is the observed value a later bound would check |
| TLS | composed from `core-net` — `withSslListenAddresses(...)` / `SslTcpSocket`; nothing TLS-specific is in this module |
| A DI module, JMX, a launcher | `extra/launchers/jsonrpc` — mounts this endpoint beside HTTP POST and WebSocket |
| ActiveJ's own binary RPC | `cloud-rpc` — a separate stack, by decision |
