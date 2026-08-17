# ActiveJ : Launchers : JSON-RPC

Turnkey launchers for JSON-RPC 2.0 services over HTTP POST, built on `activej-jsonrpc-http`,
`activej-http` and the ActiveJ boot stack.

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
| `jsonrpc.path` | `String` | `/` | where the servlet is mounted in the root `RoutingServlet` |
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

Example rejection message:

```
Configuration key 'jsonrpc.maxBatchSize' is not supported: the batch bound is process-wide and is
read directly by JsonRpcDecoder. Set -DJsonRpcLimits.maxBatchSize=<n> instead.
(A per-instance override is owned by feature 09.)
```

The effective process-wide values of both bounds are published read-only on the dispatcher MBean
(`maxBatchSize`, `maxJsonDepth`), so an operator can always see what is in force.

The full key surface is defined normatively in the feature contract `contracts/config-keys.md`.

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
