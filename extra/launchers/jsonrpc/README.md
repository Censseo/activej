# ActiveJ : Launchers : JSON-RPC

Turnkey launchers for JSON-RPC 2.0 services over HTTP POST, WebSocket and framed TCP, built on
`activej-jsonrpc-http`, `activej-jsonrpc-ws`, `activej-jsonrpc-tcp`, `activej-http` and the ActiveJ
boot stack.

## Maturity

**Experimental — `extra/` modules are not production-ready.** This module lives under `extra/`, which
is profile-gated and whose per-module maturity is an explicitly open question for the platform. Treat
it as experimental: the API may change without a deprecation cycle, and the default `mvn verify` does
not build or test it at all. "Turnkey" describes ergonomics — how little code a developer writes to
stand a service up — never a support level.

## Using the launcher

Extend `JsonRpcServerLauncher` and contribute one `JsonRpcServiceBinding` per service interface in
`getBusinessLogicModule()`:

```java
public final class MyApp extends JsonRpcServerLauncher {
	@Override
	protected Module getBusinessLogicModule() {
		return new AbstractModule() {
			@ProvidesIntoSet
			JsonRpcServiceBinding userApi() {
				return new JsonRpcServiceBinding(UserApi.class, new UserApiImpl());
			}
		};
	}

	public static void main(String[] args) throws Exception {
		new MyApp().launch(args);
	}
}
```

The launcher wires the eventloop, the `JsonRpcDispatcher`, the servlet, the `HttpServer`, JMX and the
service graph; a service interface is the whole protocol (`@JsonRpcService` + `@JsonRpcMethod`,
codecs derived for any `record`). JMX is on by default: the dispatcher exposes per-method request
counts, error breakdowns and latencies under `io.activej.jsonrpc.service:type=JsonRpcDispatcher`.

## Configuration

Precedence: built-in defaults ← `jsonrpc-server.properties` (classpath, optional) ←
`-Dconfig.<key>=<value>`.

### Keys this launcher introduces

| Key | Type | Default | Effect |
|---|---|---|---|
| `jsonrpc.path` | `String` | `/` | where the POST servlet is mounted in the root `RoutingServlet` |
| `jsonrpc.ws.path` | `String` | `/ws` | where the WebSocket endpoint is mounted in the **same** root `RoutingServlet`, beside the POST endpoint. An **empty value disables** it: no `JsonRpcWsServlet` is constructed, the path 404s, and the POST route is untouched |
| `jsonrpc.tcp.port` | `int` | **empty — disabled** | the framed-TCP listen port. Absent or empty: **no `JsonRpcTcpServer` is constructed and no socket is opened**. Set: the endpoint accepts LF-terminated JSON-RPC documents on that port, with the same dispatcher as the HTTP and WebSocket endpoints. `0` binds an ephemeral port — ask the server where it landed rather than reading the key back |
| `jsonrpc.maxBodySize` | `MemSize` | `1mb` (`JsonRpcLimits.MAX_BODY_SIZE`) | the servlet-tier body bound; a larger declared `Content-Length` is `413`. A configured value **overrides** the process-wide default |
| `jsonrpc.emptyResponseCode` | `int` | `204` | status for an empty dispatcher result (a lone notification). Only `200` or `204`; anything else is refused at build |
| `jsonrpc.client.url` | `String` | `http://localhost:8080/` | the target endpoint of `JsonRpcClientModule`'s client (client-side wiring only, unused by the server launchers) |

### Keys inherited unchanged

| Key tree | Via |
|---|---|
| `http.*` (`listenAddresses`, `keepAliveTimeout`, `readWriteTimeout`, …) | `Initializers.ofHttpServer` |
| `eventloop.*` | `Initializers.ofEventloop` |
| `workers` | multi-worker launcher only, default `4` |

### Keys that deliberately do NOT exist

Setting one of these **fails startup** — it is never silently ignored. `ConfigModule` only marks
unconsumed keys `##` in the effective-config dump and does not fail, so the launcher's own check in
`onStart()` rejects them loudly:

| Non-key | Controlled instead by |
|---|---|
| `jsonrpc.maxBatchSize` | `-DJsonRpcLimits.maxBatchSize=<n>` (process-wide, default `100`) |
| `jsonrpc.maxJsonDepth` | `-DJsonRpcLimits.maxJsonDepth=<n>` (process-wide, default `64`) |
| `jsonrpc.callTimeout` | not yet available — feature 09 owns it; `http.readWriteTimeout` bounds a stalled request meanwhile |
| `jsonrpc.maxInFlight` | not yet available — feature 09 owns it |

Every other key under `jsonrpc.ws.*` — anything but `jsonrpc.ws.path` — is **also** rejected at
startup, naming the key: the WebSocket surface admits exactly that one key
(`contracts/config-keys.md` of feature 06). A **scalar** `jsonrpc.ws` value (for example
`jsonrpc.ws=/ws`, a plausible typo for the real key) is rejected the same way — it carries no child
keys, so silently applying the default mount would otherwise hide the typo.

The `jsonrpc.tcp.*` subtree is checked identically and admits exactly `jsonrpc.tcp.port`; a scalar
`jsonrpc.tcp` value is rejected too. There, silence would be worse than a wrong mount: a mistyped key
leaves the endpoint **off**, so a deployment that believed it had opened a TCP listener would find
out from a connection refusal in production rather than from startup.

Example rejection message:

```
Configuration key 'jsonrpc.maxBatchSize' is not supported: the batch bound is process-wide and is
read directly by JsonRpcDecoder. Set -DJsonRpcLimits.maxBatchSize=<n> instead.
(A per-instance override is owned by feature 09.)
```

The effective process-wide values of both bounds are published read-only on the dispatcher MBean
(`maxBatchSize`, `maxJsonDepth`), so an operator can always see what is in force.

The full key surface is defined normatively in the feature contract `contracts/config-keys.md`.

## The WebSocket endpoint

Beside the POST endpoint, the launcher mounts the **same dispatcher's services** over WebSocket on
`jsonrpc.ws.path` (default `/ws`) — one `HttpServer`, two transports, one service table. A WebSocket
client speaks plain JSON-RPC 2.0, one document per TEXT message: a browser's `WebSocket` needs no
ActiveJ code, and a Java client wires `JsonRpcWsTransport.connect(...)` + `JsonRpcClient` (see the
`activej-jsonrpc-ws` module).

- **Disable the endpoint** with the empty form `jsonrpc.ws.path=`: no `JsonRpcWsServlet` is
  constructed, the path 404s, and the POST route is untouched.
- **Long-lived sessions and `http.readWriteTimeout` (FR-096/FR-106).** The host server's 60 s
  `readWriteTimeout` default sweeps upgraded connections — the timestamp is refreshed only at
  connection-pool switches, not by frame traffic (core-http documents the identical behaviour for
  SSE). A deployment serving long-lived WebSocket sessions **must set `http.readWriteTimeout=0 seconds`**
  (`StringFormatUtils.DURATION_PATTERN` requires whitespace between the number and the unit — `0s`
  fails to parse and startup rejects the key); the transport cannot and does not override the host
  server.
- **Per-worker sessions in the multithreaded launcher (FR-103).** Each worker reactor mounts its own
  route, dispatcher and session registry; a connection's sessions live only on the worker that
  accepted it, and **cross-worker broadcast is out of scope** — broadcast within one worker's
  registry is a `JsonRpcWsServlet` concern (its `broadcast(...)`).
- **Reaching the sessions: the mounted servlet is a DI binding.** `JsonRpcWsServlet` is an ordinary
  binding of the launcher modules — inject it to call `sessions()`, `broadcast(...)` or a session's
  server-initiated `JsonRpcClient`. Under the multithreaded launcher the binding is `@Worker`-scoped,
  so retrieve each worker's servlet with `WorkerPool.getInstances(JsonRpcWsServlet.class)`. The
  binding is resolved lazily and only when `jsonrpc.ws.path` is non-empty, so a disabled endpoint
  constructs nothing at startup (a lookup while disabled yields an unmounted servlet with an
  always-empty registry — the provider cannot re-check `jsonrpc.ws.path` then, because `Config` is
  readable during startup only).
- **WebSockets disabled JVM-wide (`-DIWebSocket.enabled=false`).** `WebSocketServlet`'s constructor
  refuses construction when core-http's `IWebSocket.ENABLED` is off, and the mount is **on by
  default** — such a deployment must set `jsonrpc.ws.path=` (empty) or startup fails at wiring.

## The framed-TCP endpoint

Set `jsonrpc.tcp.port` and the launcher opens a **third** endpoint over the same dispatcher and the
same service table: one LF-terminated JSON-RPC document per message, both directions, on one
persistent connection (see the `activej-jsonrpc-tcp` module). A Java client is
`JsonRpcTcpTransport.connect(reactor, address)` + `JsonRpcClient`; anything that can write a line to
a socket is a client:

```bash
printf '{"jsonrpc":"2.0","id":1,"method":"user.get","params":[42]}\n' | nc 127.0.0.1 9000
```

- **Disabled by default — and the asymmetry with `jsonrpc.ws.path` is deliberate.** The WebSocket
  route **rides the HTTP listener that already exists**, so enabling it opens no new socket, which is
  why it defaults to `/ws`. The TCP endpoint **opens a new listening socket**, and that socket is
  **plaintext and unauthenticated by design** — there is no preamble, no handshake and no admission
  step on this wire. Opening it is therefore an explicit deployment decision, not a default: with the
  key absent, no `JsonRpcTcpServer` is constructed at all.
- **TLS is composed, not built in.** Neither this module nor `activej-jsonrpc-tcp` carries a single
  TLS-specific line, and `jsonrpc.tcp.*` has no TLS key. A deployment that wants TLS builds the
  server itself with core-net's existing mechanism — `AbstractReactiveServer.Builder`'s
  `withSslListenAddresses(sslContext, sslExecutor, …)`, which wraps every accepted socket in
  `SslTcpSocket` — or terminates TLS in front of the process. Should launcher keys for it ever be
  wanted, they follow the `http.ssl.*` initializer precedent and are a separate change.
- **Deployment posture.** Until TLS or an admission gate is composed in front of it, treat the port
  as an internal-network endpoint. `JsonRpcTcpServer.Builder`'s inherited `withAcceptFilter(...)` is
  the platform's admission seam if an embedded host wants one. That seam is also the answer to a
  pipelining peer that never reads: socket writes coalesce in `TcpSocket`'s write buffer and the
  read loop does not wait for them to drain, so a client that keeps sending requests without reading
  the responses grows that buffer **without bound** — today it is the peer's intent, not the
  transport, that bounds a session's memory. The per-connection in-flight bound is feature 09's
  slot; until it ships, who may connect is the bound.
- **Per-worker sessions in the multithreaded launcher.** The primary reactor accepts and hands each
  connection to a per-worker `JsonRpcTcpServer`, exactly as `PrimaryServer` already does for the HTTP
  workers. Each worker's `sessions()` registry sees only the connections that worker accepted, and
  **cross-worker broadcast is out of scope** — `broadcast(...)` reaches one worker's registry, and
  fanning out is the application's loop over `WorkerPool.getInstances(JsonRpcTcpServer.class)`.
- **Reaching the sessions: the mounted server is a DI binding.** `JsonRpcTcpServer` is an ordinary
  binding of the launcher modules (`@Worker`-scoped in the multithreaded one) — inject it, or use
  `WorkerPool.getInstances(...)`, to call `sessions()`, `broadcast(Class, Consumer)` or a session's
  server-initiated `proxy(...)`. It is resolved lazily and only when `jsonrpc.tcp.port` carries a
  value, so a disabled endpoint constructs nothing at startup; a lookup while disabled yields a
  server that nothing ever starts, because the provider cannot re-check the key at lookup time
  (`Config` is readable during startup only). `JsonRpcTcpMount` is the wiring-time decision itself —
  inject it to ask `isEnabled()` or `listener().getBoundAddresses()`.

## Several paths — out of scope, and the workaround

Publishing one application's services at **several distinct HTTP paths** is out of scope for this
launcher: `JsonRpcServerLauncher` mounts one `JsonRpcServlet` at the configured `jsonrpc.path`. The
servlet is path-agnostic — it reads no path segment — so several paths is a small `RoutingServlet`
composition with one servlet instance per path:

```java
// hand composition — several paths, one server (FR-013 workaround)
AsyncServlet rootServlet = RoutingServlet.builder(reactor)
	.with(POST, "/api/a", JsonRpcServlet.create(reactor, dispatcherA))
	.with(POST, "/api/b", JsonRpcServlet.create(reactor, dispatcherB))
	.build();
HttpServer server = HttpServer.builder(reactor, rootServlet).build();
```

## Building

```bash
mvn -P extra -pl extra/launchers/jsonrpc -am test
```
