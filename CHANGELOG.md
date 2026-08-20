# Changelog

## Unreleased

### Notable additions

- **A bidirectional JSON-RPC 2.0 transport over WebSocket.** New profile-gated
  module `extra/cloud-jsonrpc-ws` (`activej-jsonrpc-ws`): `JsonRpcWsTransport`
  binds the JSON-RPC transport SPI to core-http's message-level WebSocket API (one
  JSON-RPC document per TEXT message, in both directions), and
  `JsonRpcWsServlet`/`JsonRpcWsSession` give the server an enumerable registry of
  live connections — each carrying a full `JsonRpcClient` for server-initiated
  calls, plus `broadcast(...)` for pushing a notification to every client. The 30
  JSON-RPC 2.0 conformance vectors are replayed in **both** directions over real
  sockets with an empty skip set (the WS transport tier raised above the envelope
  tier makes `envelope-too-large` reach the decoder and answer `-32001`).

  **Breaking for launcher users:** the JSON-RPC launchers now mount a WebSocket
  endpoint at `jsonrpc.ws.path` (default `/ws`) beside the existing HTTP POST
  route on the same server. Set `jsonrpc.ws.path=` to an **empty string** to
  disable the mount entirely (the servlet is then not constructed) — this is
  also required when WebSockets are disabled JVM-wide via
  `-DIWebSocket.enabled=false`, since the default mount would otherwise fail
  startup at wiring. No other `jsonrpc.ws.*` key exists, and a scalar
  `jsonrpc.ws` value is not one either; setting either fails startup loudly
  naming the key. A deployment serving long-lived sessions should set
  `http.readWriteTimeout=0 seconds` — the 60 s default sweep closes upgraded
  connections regardless of frame traffic (the duration format requires
  whitespace before the unit; `0s` does not parse). The mounted
  `JsonRpcWsServlet` is an ordinary DI binding — inject it, or under the
  worker-pool launcher retrieve each worker's via
  `WorkerPool.getInstances(JsonRpcWsServlet.class)`, to reach `sessions()`,
  `broadcast(...)` and the per-session server-initiated clients.

- **A turnkey JSON-RPC 2.0 launcher family, and per-method JMX metrics on the
  dispatcher.** New profile-gated module `extra/launchers/jsonrpc`
  (`activej-launchers-jsonrpc`): `JsonRpcServerLauncher` (one eventloop),
  `MultithreadedJsonRpcServerLauncher` (one dispatcher and servlet per worker, a
  `PrimaryServer` accepting on every core) and `JsonRpcClientModule` (a DI-provided
  client whose in-flight calls are failed deterministically at graph stop). A
  developer writes one `@JsonRpcService` interface, an implementation and a
  `JsonRpcServiceBinding` contribution; the servlet, dispatcher, server, config and
  JMX come from the launcher.

  In `extra/cloud-jsonrpc` (`activej-jsonrpc`), `JsonRpcDispatcher` gains a purely
  additive `Inspector` seam with a `JmxInspector` implementation: request counts,
  error breakdowns by JSON-RPC code, latencies and a `methodNotFound` aggregate —
  **one row per registered wire name, the row set closed at `build()`** and never
  growable by anything a caller sends (no `computeIfAbsent` anywhere; the one
  callback that sees wire text, `onMethodNotFound(String)`, is documented as
  aggregate-only). The JMX attribute names are a stable surface from this entry
  onward. With no inspector installed every dispatch outcome is byte-identical to
  before; a throwing inspector can never break a dispatch. Under a worker pool the
  per-worker tables aggregate in the JMX layer so the aggregated attribute equals
  the sum over workers.

Fixes a latent platform defect found while verifying this surface against a real
  `MBeanServer`: registering a stats-bearing bean through a bare `DynamicMBeanFactory` (no
  `customType` adapters) threw "Setters are allowed only on attributes of simple, custom or
  Enum types" on `EventStats.setSmoothingWindow(Duration)`. `boot-jmx`'s
  `DynamicMBeanFactory` now skips non-simple setters and renders an un-walkable optional
  attribute as an inert hidden node; `RealStatsRegistrationTest` pins it. Launcher-path
  registrations (`HttpServer` included) were already fine at HEAD — `JmxModule` registers a
  `Duration` custom type by default — so the blast radius is the bare factory, which no
  launcher path exercised. The one corner this changes from loud to silent: a bean whose
  *only* member for an attribute is a non-simple setter now registers without that attribute
  instead of failing the whole bean — nothing could register that bean before.

  Not Breaking changes: no signature moves in any shipped module (the dispatcher's
  seam is additive; the `boot-jmx` fix only removes a startup crash). The new
  launcher's config surface is `jsonrpc.path`, `jsonrpc.maxBodySize`,
  `jsonrpc.emptyResponseCode` plus the inherited `http.*` / `eventloop.*` / `workers`
  keys; setting `jsonrpc.maxBatchSize`, `jsonrpc.maxJsonDepth`,
  `jsonrpc.callTimeout` or `jsonrpc.maxInFlight` fails startup loudly rather than
  being silently ignored.

- **`JsonCodecFactory` derives a `JsonCodec` for any `record`, and for eleven more
  built-in types.** In `extra/util-json` (`activej-json`, profile-gated behind
  `-P extra`), `JsonCodecFactory.defaultInstance().resolve(MyRecord.class)` now
  returns a working codec with no registration at all, recursing through nested,
  generic, self-referencing and mutually recursive records. Previously every one of
  these reached the `Object.class` fallback and threw
  `UnsupportedOperationException`.

  A record's **component names are the JSON keys, verbatim**, and its **canonical
  constructor order is the emitted member order**. There is no naming strategy and
  no way to configure either — so for a record whose JSON is persisted or published,
  renaming or reordering a component is a wire-format change, not a Java refactor.
  Decoding accepts members in any order; an unknown key is still
  `JsonValidationException("Key not found: …")` and a missing member still fails, so
  the reader's contract is unchanged.

  Eleven types the factory could not previously resolve now resolve, inside a
  derived record or standalone: `Set`, `Optional`, `UUID`, `BigDecimal`,
  `BigInteger`, reference arrays (`T[]`, recursively), `LocalTime`, `LocalDateTime`,
  `Instant`, `Duration`, and `Map` keyed by an `Enum` or a `UUID` on top of the
  existing `String` and `Number` keys. `Optional` is the one with an encode-side
  rule: `Optional.empty()` is **omitted**, and both an absent member and an explicit
  `null` decode back to it. Deliberately still unresolved, each for a stated reason:
  **primitive arrays** (`byte[]` would force a base64-versus-numeric commitment) and
  `ZonedDateTime` / `OffsetDateTime` / `Period` (each carries its own normalization
  question).

  The public surface added is ten static factories and no new type: `ofUuid`,
  `ofBigDecimal`, `ofBigInteger`, `ofLocalTime`, `ofLocalDateTime`, `ofInstant`,
  `ofDuration` and `ofOptional(JsonCodec<T>)` on `JsonCodecs`, plus `ofEnumKey` and
  `ofUuidKey` on `JsonKeyCodec`. Derivation itself is reflection over the canonical
  constructor — **no bytecode generation, no new dependency**, and the module's POM
  is unchanged. A codec is derived at `resolve(...)` time and memoized per
  `JsonCodecFactory` instance, never per encode or decode; `rebuild()` starts a fresh
  memo, so a rebuilt factory never serves a codec built against the component codecs
  the consumer just replaced.

  Failures surface at `resolve(...)`, never on the first payload that happens to
  carry the field, and they name the full path from the root:

  ```text
  Cannot derive a JSON codec at Order.lines[].product.registeredAt: record
  com.example.Product, component 'registeredAt' of type java.time.ZonedDateTime:
  no codec is registered for it; register one with
  JsonCodecFactory.rebuild().with(Type, Mapping).build()
  ```

  **Opting out is one registration** — no flag was added because none is needed:

  ```java
  JsonCodecFactory strict = JsonCodecFactory.defaultInstance().rebuild()
      .with(Record.class, ctx -> { throw new UnsupportedOperationException(); })
      .build();
  ```

  **One narrow behaviour change beyond "types that threw now work."** Derivation is
  registered on `java.lang.Record` inside `JsonCodecFactory.builder()`, so it always
  precedes any entry a consumer adds through `rebuild().with(...)`, and
  `TypeScannerRegistry.match` promotes only a candidate assignable to the incumbent.
  A registration on a record's **exact type** still wins, as it always did. A
  registration on a record's **supertype or interface** no longer does — before this
  change it was the only candidate and won by default; now the derived codec does.
  Nothing in this repository regresses (the only `JsonCodecFactory` registrations
  outside the module are `cloud-lsmt-cube`'s and `launchers/crdt`'s, and `CrdtData`
  is a `final class`, not a record), but a consumer relying on an interface-level
  registration for a `record` implementation must move it to the record type, or use
  the opt-out above.

  A second, smaller consequence: resolving a **record** whose derivation fails now
  raises `IllegalArgumentException` with the original failure as `getCause()`, where
  the untouched `Object.class` fallback still raises a bare
  `UnsupportedOperationException`. The two signals stay distinguishable, and the
  fallback's behaviour for every non-record type is byte-for-byte what it was.

- **JSON-RPC 2.0 protocol core.** A new profile-gated module `extra/cloud-jsonrpc`
  (`activej-jsonrpc`, package `io.activej.jsonrpc`) carrying the JSON-RPC 2.0
  **envelope** and nothing else: request, notification, response, error object and
  batch as immutable `record`s behind sealed interfaces, a decoder over a
  contiguous `byte[]` that leaves `params` / `result` / `error.data` undecoded, and
  a deterministic encoder. There is no transport, no dispatcher and no code
  generation; the module holds no `Reactor`, returns no `Promise` and touches no
  `ByteBuf`, so it is usable and testable without an eventloop.

  It is built **only** under `-P extra` and adds no third-party dependency — it
  consumes `activej-json` and `activej-common`, and inherits dsl-json `1.10.0`
  from `extra/pom.xml` without redeclaring it.

  The specification's §7 examples ship as replayable conformance vectors under
  `src/test/resources/io/activej/jsonrpc/conformance/`, alongside vectors for this
  implementation's own strictness decisions. Vector **names are stable once
  published**; later features reference them by name.

  Three safe-by-default bounds, each an `ApplicationSettings` key resolved from
  `io.activej.jsonrpc.JsonRpcLimits.<setting>` or `JsonRpcLimits.<setting>`, so
  the fully qualified and the short spelling work alike. All three ship
  **enabled** — a consumer opts out by raising one, never in by enabling one:

  | Setting | Default | What it bounds | Behaviour at the bound | Opt out with |
  |---|---|---|---|---|
  | `maxBodySize` | `1mb` | the length of one envelope, checked before parsing | refused with `-32001` | `-DJsonRpcLimits.maxBodySize=4mb` |
  | `maxBatchSize` | `100` | elements in one batch, applied **while** they are decoded | refused with `-32002`, one document for the whole batch | `-DJsonRpcLimits.maxBatchSize=1000` |
  | `maxJsonDepth` | `64` | JSON nesting depth, by a string-aware scan run **before** the parser | refused with `-32003` | `-DJsonRpcLimits.maxJsonDepth=256` |

  The depth bound cannot be delegated to the parser: dsl-json's
  `JsonReader.skip()` recurses one stack frame per nesting level and exposes no
  depth hook, so a deep document exhausts the stack *inside* the parser before any
  in-parse check could run. `1mb` is chosen because a JSON-RPC envelope is a
  control message — `core-http`'s `100mb` body default is sized for arbitrary
  bodies and is the wrong reference point.

  `JsonRpcLimits.MAX_BODY_SIZE` is readable without an instance so that a
  transport can bound its own accumulation loop *before* a full envelope array
  exists; applying a size bound to an array already allocated has paid most of the
  cost already.

  Four error codes are allocated inside the `-32099 … -32000` range JSON-RPC 2.0
  §5.1 reserves for implementation-defined server errors. **These are published
  contract** — changing the meaning of one later is a breaking change:

  | Code | Message | Meaning |
  |---|---|---|
  | `-32001` | `Request too large` | the envelope exceeded `maxBodySize` |
  | `-32002` | `Batch too large` | the batch exceeded `maxBatchSize` |
  | `-32003` | `Nesting too deep` | the document exceeded `maxJsonDepth` |
  | `-32004` | `Invalid response` | a peer's Response object violates §5 — both or neither of `result`/`error`, a missing `id`, or a malformed error object |

  A distinct code per bound is deliberate: a peer that receives `-32600` cannot
  tell "your envelope was malformed" from "your envelope was too big", and the two
  have different remedies. `-32004` exists because a peer's malformed *response*
  is not an internal error on our side, and `-32603` would say it was.

  The five predefined codes of §5.1 (`-32700`, `-32600`, `-32601`, `-32602`,
  `-32603`) are exposed as named constants on `JsonRpcErrors` with the
  specification's canonical messages. Its `of(...)` factory **refuses** a code in
  the reserved `-32768 … -32000` range so an application cannot accidentally
  publish a document a client will read as "method not found"; decoding a peer's
  error accepts any code, so nothing a conforming peer sends is ever discarded.

- **JSON-RPC service layer: an annotated interface, a dispatcher, a typed client
  proxy and a transport SPI.** Two new packages in `extra/cloud-jsonrpc`
  (`activej-jsonrpc`, still profile-gated behind `-P extra`):
  `io.activej.jsonrpc.service` and `io.activej.jsonrpc.transport`. Publishing a
  method is an interface plus one `withService(...)`; consuming it is one
  `proxy(...)`. Neither side carries a line of routing, correlation or
  serialization code.

  ```java
  @JsonRpcService("user")
  public interface UserApi {
      @JsonRpcMethod("get")
      Promise<User> getUser(@JsonRpcParam("id") long id);

      @JsonRpcNotification("touch")
      void touch(@JsonRpcParam("id") long id);
  }
  ```

  The new public surface, and nothing else: the four runtime-retained annotations
  `@JsonRpcService`, `@JsonRpcMethod`, `@JsonRpcNotification` and `@JsonRpcParam`;
  `JsonRpcServiceContract` with `JsonRpcMethodDescriptor` and
  `JsonRpcParamDescriptor`; `JsonRpcContractException`; `JsonRpcParamStyle`;
  `JsonRpcDispatcher`; `JsonRpcClient`; `JsonRpcPeerHandler`; and the
  `JsonRpcTransport` SPI with its nested `Listener`. The envelope layer
  (`io.activej.jsonrpc`, `io.activej.jsonrpc.impl`) is **untouched** and stays
  reactor-free and `Promise`-free — `ModuleBoundaryTest` now scopes that rule by
  package rather than by module, so a `Promise` or `Reactor` import in an envelope
  package still fails the build.

  A wire name is the service's prefix, a dot, and the method's own name. **A method
  that leaves `@JsonRpcMethod`'s `value()` empty puts its Java identifier on the
  wire**, so a later rename is a wire-format change with no compile error anywhere;
  an explicit `@JsonRpcMethod("…")` is the mitigation, and the README says so where
  a consumer reads it before writing an interface. The Java signature itself is
  unaffected — renaming the method still breaks compilation on both the implementing
  and the calling side, asserted by driving the JDK compiler over three throwaway
  sources rather than claimed in prose.

  A contract is validated **entirely at construction**, and one exception reports
  **every** violation rather than the first. A method signature two unrelated
  super-interfaces declare independently is rejected deterministically, naming
  every declaring type, rather than silently inheriting whichever annotations an
  unspecified reflection order hands down — the remedy, redeclaring the method on
  the service interface, is named in the message. Two parameters sharing one
  `@JsonRpcParam` name are rejected by name and position at the same time, instead
  of leaving the second parameter unreachable by named `params` forever. Dispatch is total: `dispatch(byte[])`
  and `dispatch(JsonRpcInput)` never complete exceptionally, so a transport author
  writes no failure branch — an unknown method is `-32601` and undecodable `params`
  are `-32602`, both without invoking the implementation, and "no response document"
  is a zero-length array, which is neither `[]` nor `{}`. On the client, correlation
  is by whole `JsonRpcId` through a single removal path, and the entry is removed
  **before** the payload is decoded, so an undecodable result cannot leak an entry
  and an orphan value is never constructed; an answer with an unknown `id` is ignored
  silently.

  Error mapping is a one-way valve: a `JsonRpcException` travels verbatim — code,
  message and `data` — while **anything else** becomes exactly
  `{"code":-32603,"message":"Internal error"}` with no `data`, no class name, no
  message fragment and no stack frame. A notification's failure has nowhere to go on
  the wire (§4.1), so it goes to `withFailureHandler(...)`, defaulting to
  `Reactor.logFatalError`.

  Transport authors get an SPI over contiguous `byte[]` documents with seven stated
  obligations (join before decoding, bound the accumulation rather than the result,
  never deliver a zero-length document, `send` means *written* not *answered*, assume
  nothing about pairing or order, close idempotently and report it exactly once, keep
  everything transport-specific inside the implementation), plus
  `AbstractTransportConformanceTest`: implement one method and inherit the whole
  conformance suite, all 30 vectors replayed end to end through a real dispatcher.
  The harness ships as a `test-jar` (the module's POM runs the `maven-jar-plugin`
  `test-jar` goal), so a transport module consumes the suite at test scope — the
  HTTP transport below is the first consumer.

  **Purely additive.** No new `ApplicationSettings` limit is introduced, so there is
  no behaviour-changing default and nothing here belongs under Breaking changes; the
  three envelope bounds above (`maxBodySize`, `maxBatchSize`, `maxJsonDepth`) remain
  the module's only settings, unchanged. Per-call timeouts and an in-flight bound are
  deliberately **not** here — `JsonRpcClient` exposes `inFlightCount()` as the
  diagnostic a later feature's bound would be observed through. No third-party
  dependency, no bytecode generation, no `ByteBuf`, no transport and no `activej-rpc`:
  the module adds two internal edges only, `activej-promise` and `activej-eventloop`,
  both confined to the two new packages.

- **JSON-RPC 2.0 over HTTP POST: a servlet and a client transport.** A new
  profile-gated module `extra/cloud-jsonrpc-http` (`activej-jsonrpc-http`, package
  `io.activej.jsonrpc.transport.http`) — the **first transport** for the SPI above,
  and the wire half of `JsonRpcClient.proxy(...)`. `JsonRpcServlet` mounts the
  dispatcher behind `AsyncServlet`; `JsonRpcHttpClientTransport` implements
  `JsonRpcTransport` with one `POST` per `send` (no batching, no retry, no
  reordering — connection reuse is entirely the injected `IHttpClient`'s). One
  `POST` in, one `dispatch(byte[])` out, the dispatcher's bytes written back
  **unaltered** — never re-encoded, with no response `Content-Type` inspection on
  the client side (strict on the request, lenient on the response).

  The wire contract is a six-row semantics table evaluated **in order** before any
  body byte is read: `405` + `Allow: POST` for a non-`POST` method, `415` for an
  absent or non-JSON media type (parameters ignored), `413` for a declared
  `Content-Length` over the bound, the connection tier's hardcoded `400` + close
  for a mid-stream crossing, `204` (builder-configurable to `200`) when the
  dispatcher answers with nothing — a notification, an all-notification batch —
  and `200` carrying the document for every JSON-RPC outcome: `-32700` Parse error
  (including a **zero-length body**), `-32600`…`-32603`, application errors and
  batches. The table is pinned by frozen curl/fetch interoperability vectors, so a
  change to a status, a header or a body fails the build. On the client, a `2xx`
  with a body delivers it to the listener **before** `send`'s promise completes; a
  `204` or an empty body delivers nothing; a non-`2xx`, a network failure or an
  oversize response fails **that call only**, never `onClosed`; `send` after close
  fails immediately with no request issued; the injected `IHttpClient` is never
  closed.

  The module reuses feature 012's bounds rather than adding keys: the
  `JsonRpcLimits.maxBodySize` (`1mb` default) applies to the request on the
  servlet side and to the response on the transport side, with
  `withMaxBodySize(MemSize)` as the per-instance override — and **no
  `ApplicationSettings` key exists** in this module. It declares `activej-jsonrpc`,
  `activej-http` and the platform modules it uses directly (`activej-common`,
  `activej-bytebuf`, `activej-promise`, `activej-eventloop`), adds no third-party
  dependency and is built only under `-P extra`. No `Breaking changes` entry: the
  module is new, and the existing behaviour it builds on (`core-http`'s body
  handling, gzip decoded before the bound applies, feature 012's envelope and SPI)
  is unchanged — apart from the `core-csp` `acceptAll` failure-path fix under
  Notable fixes below.

- **JitPack publishes the `extra/` artefacts too.** JitPack's default build command is
  `mvn install -DskipTests`, and the `extra` profile is `activeByDefault=false` — so
  every artefact under `extra/` was silently absent from the published build.
  [jitpack.yml](jitpack.yml) now overrides that command with
  `mvn install -B -DskipTests -P extra`, which adds the seventeen extra artefacts
  (`activej-json`, `activej-jsonrpc`, `activej-jsonrpc-http`, `activej-cube`,
  `activej-crdt`, `activej-dataflow`, `activej-dataflow-jdbc-driver`, `activej-etl`,
  `activej-etcd`, `activej-memcache`, `activej-multilog`, `activej-ot`, `activej-redis`
  and the four extra launchers) to the reactor's. Each is one coordinate, as for any
  other module: `com.github.<user>.<repo>:activej-jsonrpc:<tag>`. The profile stays
  off for a plain `mvn verify` and for CI, which is deliberate — turning it on there
  would drag the extra modules' third-party dependencies (Calcite, jetcd, MinIO,
  Jackson, …) into every build of a reactor that refuses them.

### Notable fixes

- **`WebSocketServlet` no longer strands the request body stream when an upgrade is
  refused** (in `core-http`). `takeBodyStream()` used to run *before* `onRequest`,
  with the recycler wired only to the *returned* promise's exception path — so a
  synchronous throw out of `onRequest`, or a `101` response illegally carrying a
  body, bypassed it and leaked the already-buffered pooled `ByteBuf` (and, in the
  body case, the response's own body too). The servlet now takes the stream only
  once a legal `101` has been produced; every refusal and every failure before
  that point leaves the stream with the request, which its owner
  (`HttpServerConnection`) recycles the same way it does for any other servlet.
  Regression tests: `WebSocketServletUpgradeFailureTest`
  (`aThrowFromOnRequestDoesNotStrandTheRequestBodyStream`,
  `aOneOhOneCarryingABodyDoesNotStrandTheRequestBodyStreamNorItsOwnBody`).

- **`WebSocketBufsToFrames` now releases its read half instead of leaking it on
  every close** (in `core-http`). `doClose()` used to close only the outgoing side;
  the bytes still buffered in the input `BinaryChannelSupplier` — and any socket
  read still in flight — were never recycled, on any of a protocol error, a
  non-1000 peer close, or a locally-initiated `closeEx`. A direct `input.closeEx()`
  from `doClose()` was not an option: `doClose()` runs *before* the outgoing CLOSE
  frame is written, and cascading into the socket at that point would drop the
  pending write. The new package-private `closeInput(Exception)` instead closes the
  *unsanitized* underlying supplier — recycling the buffered bytes and cancelling
  any in-flight read without re-entering the process's own `closeEx` — and is
  triggered from both `WebSocketServlet` (server) and `HttpClientConnection`
  (client) once the CLOSE frame has been sent **and** the decoder has finished,
  however it finished; gating on `closeSentPromise` alone is not enough, since that
  promise never settles when this side closes first. Three existing
  `@ByteBufRule.IgnoreLeaks` opt-outs are removed as a direct result:
  `WebSocketServerProtocolErrorTest`, and (built only under `-P extra`)
  `extra/cloud-jsonrpc-ws`'s `JsonRpcWsOversizeTest` and (narrowed rather than fully
  removed — see below) `JsonRpcWsPurgeTest`. Regression tests:
  `WebSocketBufsToFramesTest#closeInputRecyclesTheBytesBufferedWhenTheProtocolErrorWasRaised`
  (pooled bufs, to actually exercise `ByteBufRule` — the class's other tests use
  unpooled `ByteBuf.wrapForReading`, which the rule cannot see), plus the three
  `@IgnoreLeaks` removals themselves as regression proof, plus
  `JsonRpcWsHostileTest`'s RSV1 frame test now sends the complete frame instead of
  truncating it at the offending byte to dodge this same leak.

  A related, *different* defect surfaced verifying this fix and is left unfixed:
  one row of `JsonRpcWsPurgeTest`'s four-row purge matrix (peer error close) still
  strands a raw 16 KB `TcpSocket.onReadReady` read buffer, confirmed with a
  `ByteBufPool` allocation-site probe to be unrelated to `WebSocketBufsToFrames` —
  an in-flight low-level socket read orphaned when that test harness's
  belt-and-suspenders raw-socket `closeEx` cuts the connection mid-read. That is a
  `core-net` defect, out of scope for this fix; `JsonRpcWsPurgeTest` keeps a
  narrowed `@IgnoreLeaks` naming only that one row.

- **WebSocket frame parsing no longer continues after a protocol error** (in
  `core-http`'s `WebSocketBufsToFrames`). Three error branches reported the
  violation and then fell through into further parsing on the closed, recycled
  parser state: the mask-required / mask-not-allowed branches of the length
  byte, and the 8-byte extended length with the most significant bit set (a
  negative, peer-controlled length that then reached payload parsing). All
  three now stop immediately, like every other error branch. Regression tests:
  `WebSocketBufsToFramesTest` (`unmaskedFrameWhenMaskIsRequiredIsAProtocolError`,
  `maskedFrameWhenMaskIsNotExpectedIsAProtocolError`,
  `negativeLongLengthIsAProtocolError`, each with a chunked variant).

- **`ChannelConsumer.acceptAll(Iterator)` no longer leaks the not-yet-accepted items
  on failure** (in `core-csp`). A failed `accept` mid-iteration used to recycle the
  *iterator* via `Recyclers.recycle(it)` — a no-op for the iterator every production
  caller passes (`ByteBufs.asIterator()` is not `Recyclable`), so each pooled
  `ByteBuf` still queued behind the failed item was dropped unrecycled. The
  remaining **items** are now drained and recycled
  (`it.forEachRemaining(Recyclers::recycle)`), which is what the javadoc always
  promised and now states explicitly. The `List` overload already recycled this way
  and is unchanged. The affected callers are `BufsConsumerGzipDeflater`,
  `BufsConsumerDelimiter` and `BufsConsumerGzipInflater`, and only on their failure
  paths. Regression test: `ChannelConsumerTest.testAcceptAllIteratorRecyclesRemainderOnFailure`,
  pinned by `ByteBufRule`.

The remaining fixes are in `extra/util-json` (`activej-json`), built only under
`-P extra`.

- **`ObjectJsonCodec.BuilderArray` no longer discards every default when *every*
  field has one.** `doBuild()` branches on whether any field is still
  `NO_DEFAULT_VALUE`; the mixed branch already seeded the accumulator from the
  prototype, but the all-defaulted branch allocated a fresh `Object[]` and threw the
  defaults away, so `{}` decoded to all-nulls instead of to the defaults. The two
  branches now differ only in their finaliser, which is the only thing they should
  ever have differed in. No shipped consumer reached the broken branch — the cube's
  two `ObjectJsonCodec.builder(...)` call sites use the mixed branch and a
  `BuilderObject` respectively — but a record whose components are all `Optional`
  reaches it on the first decode.

- **`AbstractMapJsonCodec.read` and `AbstractArrayJsonCodec.read` no longer drop
  members after a skipped one.** A subclass returning `null` from `decoder(...)` to
  ignore a member had its cursor left one token past the separator: `skip()`
  consumes the following `,` **and returns it**, and both templates then did a bare
  `continue`, re-entering `readKey()` on the byte after the comma. Both now use the
  byte `skip()` returns as the separator and `break` into the existing
  `checkObjectEnd()` / `checkArrayEnd()` when it is not a comma. Unreachable from
  the codecs shipped in the module (every `decoder(...)` override there either
  returns a codec or throws), so nothing in this repository changes behaviour; any
  subclass outside it that skips unknown members was silently losing data, or —
  more often, since the misplaced cursor usually lands on a separator where
  `readKey()` demands a quote — failing with a `ParsingException` that named a
  position rather than the cause. Regression test: `SkipBranchTest`.

- **Three placeholder exceptions in shipped code now say what went wrong.** The
  `Map` mapping's `IllegalArgumentException("TODO")` for an unsupported key type now
  names the whole map type, the offending key type and the supported set; the same
  mapping's `(Class<?>)` cast on the key type became `Types.getRawType(...)`, so
  `Map<List<String>, V>` reaches that named refusal instead of a bare
  `ClassCastException` thrown out of the factory before any branch ran; and
  `JsonKeyCodec.ofNumberKey` replaces both a `JsonValidationException("TODO")` on a
  malformed key and a completely message-less `IllegalArgumentException` on an
  unsupported number type — the latter became reachable in this release, because
  `Number.class.isAssignableFrom(BigDecimal.class)` routes `Map<BigDecimal, V>` into
  that branch. Every existing `Class`-keyed map — `String`, `Integer`, an `Enum`,
  a `UUID` — is bit-for-bit unaffected.

- **Two more message-less exceptions from the derivation path now say what went
  wrong**, and one existing message stopped contradicting itself. `BuilderArray`'s
  all-defaults-missing path — now the default path for every derived record with a
  required component — names the missing field instead of throwing a bare
  `JsonValidationException`; `JsonCodecs.ofSet`'s duplicate-element rejection names
  the offending value; and `JsonCodecFactory`'s `Map` mapping gives an annotated
  `String` key (e.g. `Map<@JsonNullable String, V>`) its own refusal message instead
  of falling into the generic "unsupported key type" one, which used to name
  `String` as both unsupported and supported in the same sentence. Also documented,
  no behavior change: `DerivationCache`'s Javadoc and `JsonCodecFactory.defaultInstance()`
  now note that resolving a record through the shared static instance pins that
  record's classloader for the JVM's lifetime — relevant to hosts that unload
  classloaders at runtime (plugins, hot redeploy), which should keep a scoped
  `builder()` instance per deployment instead.

## v7.0.0 — 2026-08-09 — QUIC / HTTP-3 stack, and security hardening

**This release requires Java 25.** The baseline moved from 17, so artifacts no
longer load on a 17 or 21 runtime — see the first entry under Breaking changes.

The first release to carry the QUIC and HTTP/3 stack: three modules that did not
exist in `v6.0-ce1` — `activej-quic` (RFC 9000/9001/9002), `activej-http3`
(RFC 9114 with RFC 9204 QPACK) and `activej-launchers-http3`.

It also hardens core and networked components against resource exhaustion,
request smuggling and abuse. Several safe-by-default limits are now enforced;
applications that relied on the previous unlimited behavior must opt out
explicitly.

**On the version number.** This release leaves the `6.0-ce` line. Upstream
ActiveJ has been unmaintained since January 2026, so this fork versions itself
independently from here on, under plain semantic versioning: `7.0.0` is a major
bump because of the breaking changes below, not a claim on any upstream release.

### Breaking changes

- **Java 25 is the baseline.** The reactor compiles with `maven.compiler`
  `release=25`, so every artifact carries class file version 69 and **a consumer
  running Java 17 or 21 will fail to load it** with `UnsupportedClassVersionError`.
  Previously the baseline was 17. Consequences worth knowing:

  - The compiler configuration moved from `<source>`/`<target>` to `<release>`.
    The old pair only bounded the emitted bytecode, leaving javac free to link
    whatever API the build JDK shipped — a call added after the baseline compiled
    fine and failed at runtime with `NoSuchMethodError`. `<release>` pins the API
    surface too, so that class of escape is now caught at compile time.
  - The CI matrix drops JDK 21 and builds on 25 only; a 21 job could not compile
    the reactor at all.
  - The archetype templates and the `examples/tutorials` projects moved to 25 for
    the same reason: a project targeting 17 cannot read a version-69 class file.
  - JitPack provisions no JDK 25 (`jdk: - openjdk25` silently falls back to Java 8,
    see jitpack/jitpack.io#7547), so [jitpack.yml](jitpack.yml) installs it through
    sdkman in `before_install`.

- **HTTP server read/write timeout.** `HttpServer` now applies a default
  read/write timeout of 60 seconds (previously unlimited). Long-lived responses
  (SSE, long polling, infinite streaming) must override it:
  `-Dactivej.http.HttpServer.readWriteTimeout=0` or
  `HttpServer.builder(...).withReadWriteTimeout(...)`.
- **Maximum HTTP body size.** Both `HttpServer` and `HttpClient` reject bodies
  larger than 100 MB by default (previously unlimited). Adjust with
  `-Dactivej.http.HttpServer.maxBodySize` / `-Dactivej.http.HttpClient.maxBodySize`
  (setting the size to `0` disables the limit). Chunked bodies are additionally
  bounded by the same setting while decoding.
- **FileSystemServer upload cap.** TCP `FileSystemServer` rejects uploads larger
  than 1 GB by default (previously unlimited). Adjust with
  `FileSystemServer.builder(...).withMaxUploadSize(...)`.
- **Stricter HTTP parsing (RFC 7230).** The following are now rejected with
  `400 Bad Request` (server) or a connection error (client), where they were
  previously tolerated:
  - bare-`LF` line endings in request/response heads (`CRLF` is required);
  - obsolete line folding (`obs-fold`) in headers;
  - messages carrying both `Transfer-Encoding` and `Content-Length`;
  - multiple `Transfer-Encoding` header fields;
  - a `Transfer-Encoding` whose final coding is not `chunked`
    (e.g. `Transfer-Encoding: chunked, gzip`).
- **ByteBufPool retention limit.** The `maxItemsPerSlab` default of 1024 items
  per slab is replaced by a per-slab byte budget,
  `-Dactivej.bytebuf.ByteBufPool.maxRetainedBytesPerSlab` (default 32 MB per
  slab), which scales the retained item count with the slab size. Set
  `maxItemsPerSlab` explicitly to restore a fixed per-slab item count
  (`0` means no limit).
- **`Config.combineWith` error messages** no longer include the conflicting
  values (which may contain secrets); the conflicting key path is reported in
  the exception chain instead.
- **`Launcher` fatal error handling.** `Error`s escaping the launch flow are
  rethrown unwrapped, and the JVM is terminated via the new overridable
  `Launcher.onFatalError(Throwable)` hook (`System.exit(-1)` by default).
  Override the hook in tests or embedded environments.
- **QUIC stream limits are now advertised.** With the new
  `io.activej.quic.stream` package, a `QuicConnection` advertises non-zero
  RFC 9000 §18.2 stream transport parameters where it previously advertised
  zero for all of them. **A peer that could open no stream at all can now open
  one** — up to 100 bidirectional and 3 unidirectional streams, each with a
  256 KiB receive window and 1 MiB across the connection — and this endpoint
  will buffer that much of its data. Applications that relied on the
  effectively stream-less transport of the previous release must set the values
  back to `0` explicitly, either on `QuicConnectionSettings.builder()` or with
  the corresponding system property.

  All nine settings are `ApplicationSettings` keys under the namespace
  `QuicConnection` already used by the connection layer, so both
  `-Dio.activej.quic.connection.QuicConnection.initialMaxData=4mb` and
  `-DQuicConnection.initialMaxData=4mb` work. The first six leave the process
  as transport parameters; the last three are local bounds the peer never sees.

  | Setting | Default | What it bounds | Behaviour at the bound |
  |---|---|---|---|
  | `initialMaxData` | `1mb` | bytes the peer may send across all streams before being granted more | peer over it → `FLOW_CONTROL_ERROR`; local sender at it → the write is withheld |
  | `initialMaxStreamDataBidiLocal` | `256kb` | bytes the peer may send on a bidirectional stream **we** opened | as above |
  | `initialMaxStreamDataBidiRemote` | `256kb` | bytes the peer may send on a bidirectional stream **it** opened | as above |
  | `initialMaxStreamDataUni` | `256kb` | bytes the peer may send on a unidirectional stream it opened | as above |
  | `initialMaxStreamsBidi` | `100` | bidirectional streams the peer may have open at once | peer over it → `STREAM_LIMIT_ERROR` |
  | `initialMaxStreamsUni` | `3` | unidirectional streams the peer may have open at once | peer over it → `STREAM_LIMIT_ERROR` |
  | `maxOutstandingStreamBytes` | `512kb` | stream bytes handed to the connection but neither acknowledged nor declared lost, summed over all streams | the writer's completion is withheld; never an error |
  | `maxReceiveRangesPerStream` | `32` | **discontiguous buffered ranges** per receiving part | connection closed with `INTERNAL_ERROR` |
  | `maxPendingStreamOpens` | `128` | local open requests withheld at once for want of peer stream credit | a further request fails with `QuicStreamLimitException` naming the key |

  Two of these are refused at `build()` rather than merely warned about, because
  each is a deadlock rather than a misconfiguration: `maxOutstandingStreamBytes`
  must stay below `maxSendQueueBytes` (so a control frame always fits), and no
  `initialMaxStreamData*` may exceed `initialMaxData` (so a per-stream window
  never promises credit the connection window cannot honour).

  **`maxReceiveRangesPerStream` does not bound memory.** It counts
  *discontiguous* buffered ranges — gaps — exactly as the connection layer's
  `maxAckRanges` counts separate runs of packet numbers, and **not** the number
  of buffered `ByteBuf` pieces. A run of buffered pieces that touch end-to-end
  is one range however many pieces it is made of, so an ordinary lossy path (one
  lost frame followed by a window of in-order frames) is a single range. Buffered
  *bytes* are bounded by flow control instead — by the stream receive window this
  endpoint advertised — so raising this setting does not raise the memory a peer
  can make a stream hold, and lowering it does not lower it.

  What it *does* scale, as of this release, is a second and independent bound:
  the number of buffered **pieces** per receiving part, at
  `maxReceiveRangesPerStream × 64` (so **2048** by default), exceeding which
  closes the connection with the same `INTERNAL_ERROR`. Flow control bounds the
  payload, not the cost of tracking it: a peer that withholds the first byte of a
  stream and sends every later byte as its own `STREAM` frame holds the range
  count at one forever while every byte buys a map entry, a boxed key and a
  buffer header. The multiplier is a documented constant rather than a tenth
  setting — 64 pieces per permitted range sits an order of magnitude above the
  ~220 pieces a full 256 KiB window of MTU-sized frames behind one gap produces,
  so ordinary loss and reordering never approach it.
- **`Http3Settings` QPACK constants replaced.** The `public static final int`
  constants `Http3Settings.QPACK_MAX_TABLE_CAPACITY` and
  `Http3Settings.QPACK_BLOCKED_STREAMS`, each fixed at `0`, are removed and
  replaced by the `ApplicationSettings`-backed
  `Http3Settings.DEFAULT_QPACK_MAX_TABLE_CAPACITY` and
  `Http3Settings.DEFAULT_QPACK_BLOCKED_STREAMS`, read per endpoint through the
  accessors `qpackMaxTableCapacity()` / `qpackBlockedStreams()` and set through
  the builder methods `withQpackMaxTableCapacity(...)` /
  `withQpackBlockedStreams(...)`.

  A `public static final int` is inlined into consumer bytecode by the Java
  compiler, so replacing one is ordinarily a binary break that recompiling this
  module cannot repair. It is safe here for one reason only: `activej-http3` has
  never appeared in a published release — every `core-http3` entry in this file
  is under **Unreleased** — so no consumer bytecode can be holding the old
  values. Keeping the removed constants beside the new accessors would have left
  two sources of truth for one number, with the fixed one reading as the
  authoritative pair.

  Both keys resolve against `Http3Settings`, so the fully qualified and the
  short form work alike:
  `-Dio.activej.http3.Http3Settings.qpackMaxTableCapacity=<value>` or
  `-DHttp3Settings.qpackMaxTableCapacity=<value>`, and
  `-Dio.activej.http3.Http3Settings.qpackBlockedStreams=<value>` or
  `-DHttp3Settings.qpackBlockedStreams=<value>`.

  **Nothing moved on the wire.** Both new constants resolve to the same `0` the
  removed ones held, so the QPACK dynamic table stays disabled by default and
  the SETTINGS frame a default `Http3Settings` produces is byte-for-byte what it
  was. This entry records an API change and no behaviour change.
- **`io.activej.quic.tls.EncryptionLevel` gains a `ZERO_RTT` constant**, appended
  **last** so that `INITIAL`, `HANDSHAKE` and `ONE_RTT` keep their ordinals — the
  enum's `ordinal()` sizes arrays in `QuicConnection`, `SendQueue` and both TLS
  engines, and reordering it would have moved every one of them. Appending is
  still a source-compatible-only change for anything switching exhaustively over
  the enum: an exhaustive `switch` in consumer code stops compiling until the new
  constant is handled. `ZERO_RTT` maps to the **Application** packet-number space
  through `EncryptionLevel.packetNumberSpace()`, shared with `ONE_RTT`
  (RFC 9000 §12.3) —
  there is no fourth space — and it carries no CRYPTO stream, so
  `hasCryptoStream()` is `false` for it alone.

### Notable fixes

- **DI: a generic module's `new Key<A>() {}` resolves again at source level 18 and
  above.** `Key` and `KeyPattern` recover the enclosing module instance — the one
  that says what `A` actually is — through the synthetic `this$0` field, and javac
  emits that field only up to source 17: from 18 on it is omitted whenever the
  anonymous subclass never reads the enclosing instance, which is precisely the
  shape of `new Key<A>() {}`. The instance is passed to the constructor and
  discarded, so reflection cannot recover it, and every such key failed with
  `IllegalArgumentException: Key should not contain a type variable`.

  `AbstractModule` now publishes itself for the two windows in which user code
  writes those keys — its own construction, covering field initializers, and
  `configure()` — and `ReflectionUtils.getOuterClassInstance` falls back to that
  only after the `this$0` lookup fails, only for an instance of the anonymous
  class's own enclosing class. Type-variable binding then substitutes exactly the
  variables that class declares, so a hint that does not fit changes nothing and
  the original error still stands.

  This was reachable before Java 25 became the baseline: **any consumer compiling
  their own generic module at source 18 or above already hit it**, whatever source
  level ActiveJ itself was built with. Ordinary keys such as
  `new Key<List<String>>() {}` were never affected.

- Fixed an authentication bypass in `BasicAuthServlet` when a username was
  absent from the credentials store, while preserving constant-time comparison.
- **HTTP/3: an uppercase field name is now rejected whatever its spelling.**
  RFC 9114 §4.1.1 forbids uppercase in a field name, but `HttpHeaders`
  interning returns the registry's own token for any case-insensitive match, so
  by the time validation ran, a peer's `Content-Type` was indistinguishable from
  `content-type` and was normalised instead of refused. The QPACK decoder — the
  last place holding the received octets — now reports what they said, so the
  rule holds for the ~150 registered names and not only for unregistered ones.
- **HTTP/3 QPACK: the static encoder now lowercases a field name it writes as a
  literal**, per RFC 9114 §4.1.1. The send-side mirror of the fix above, and it
  had the same cause: outbound names are lowercased into a `String`, then
  re-interned through `HttpHeaders`, whose case-insensitive registry hands back
  its own canonically-cased token — so `accept-charset` became `Accept-Charset`
  again and the encoder copied those octets onto the wire. It never showed for
  the 99 QPACK static-table names, which are sent as an index and never as
  literal octets, so it bit only legal names absent from that table but present
  in the registry (`Accept-Charset`, `Proxy-Authorization`, …). The lowercasing
  now happens in `QpackField.lowercaseNameBytes()`, the one funnel every
  `QpackEncoder` takes literal name octets from, so a future dynamic-table
  encoder cannot reintroduce it. Static-table lookups are unaffected
  (`HttpHeader` equality is already case-insensitive).
- **HTTP/3 client: a `Content-Length` that disagrees with the body now fails
  with `MalformedHttpException`**, like every other malformed response, instead
  of a raw `Http3Exception`. The mismatch is only detectable once the body ends,
  so it surfaces on the body channel rather than on the promise `request(...)`
  returned, and only the head path was being translated.

- **HTTP/3 QPACK: decompression failures now carry the RFC 9204 scope the
  cause calls for**, instead of always resetting only the stream. RFC 9204
  assigns the scope per cause while every cause shares
  `QPACK_DECOMPRESSION_FAILED` (0x0200): an invalid **static** table index
  (§3.1), a reference to an unavailable dynamic-table entry (§2.2.3) and an
  unexpected Required Insert Count (§4.5.1) are **connection** errors, while a
  value too large to decode (§7), a truncated field section and an exceeded
  size bound stay **stream** errors. The static-index rule applied to the table
  this implementation already ships, so this closes a live conformance gap
  rather than a theoretical one. The dividing line is whether the peers still
  agree about the format: a local limit kills one exchange, a format
  disagreement means nothing that follows on any stream is trustworthy.

- **QUIC/TLS: a legal empty `ticket_nonce` no longer kills an established
  connection.** RFC 8446 §4.6.1 declares `opaque ticket_nonce<0..255>`, so an
  **empty** nonce is valid; `TlsMessages` enforced a minimum of 1 byte, having
  copied the lower bound from the `ticket<1..2^16-1>` field beside it, where it
  is correct. Because `NewSessionTicket` arrives **after** the handshake is
  confirmed, the rejection raised `CRYPTO_ERROR(50)` and tore down a fully
  established connection moments after it began carrying traffic. Any peer that
  sends an empty nonce was affected — quic-go does, which made `Http3Client`
  (and any other `core-quic` client) unusable against quic-go and Caddy, while
  the handshake itself looked healthy. Interop with quiche (curl, Cloudflare)
  and Chrome was unaffected and hid the bug. The `ticket` field's minimum of 1
  is unchanged, and the nonce is still capped at 255.
- Hardened the binary serializer against malformed input: truncated streams,
  negative lengths, oversized allocations and out-of-range enum/subclass
  ordinals now raise `CorruptedDataException` instead of corrupting memory or
  looping indefinitely.
- RPC server: configurable limits for message size, in-flight requests and
  initial connections; per-connection state is closed idempotently.
- `Specializer` no longer loads generated classes while holding the
  specialization lock, removing a potential class-loader deadlock.
- `BlockingReactorExecutor` computations can no longer be completed twice.
- ByteBuf: opt-in strict recycling mode
  (`-Dactivej.bytebuf.ByteBuf.strictRecycle=true`) that fails fast on double
  recycling; the default behavior is unchanged.
- Codegen: `GeneratedBytecode.usesStaticConstants()` exposes whether bytecode
  storage must preserve static constants; `DefiningClassLoader` no longer
  rewrites the `.class` storage format.

### Notable additions

- **`activej-test` gains `io.activej.test.EventloopThread`.** An `Eventloop` that
  owns a dedicated daemon thread, with a blocking submit bridge and an idempotent,
  time-bounded teardown (`keepAlive` released, thread joined, `breakEventloop()` as
  a last resort). It is the tool for a test whose JUnit thread must *block* — on a
  subprocess, on a second reactor, on a promise only that loop can complete — where
  `EventloopRule`, which puts a loop on the current thread, cannot serve. Resources
  are registered for teardown with `onClose(...)`, run on the loop in reverse
  registration order. Purely additive; no existing rule or helper changed.

- **HTTP/3 core, foundations.** A new leaf module `core-http3` (`activej-http3`,
  package `io.activej.http3`) begins RFC 9114 HTTP/3 with static-table RFC 9204
  QPACK, sitting above both `core-http` and `core-quic`. This entry covers the
  module skeleton and the two additive `core-http` changes; the transport and
  QPACK codec follow in subsequent entries as the feature completes.

  Exactly two additive changes to `core-http`, and nothing else — no behaviour
  change, no signature change to any existing method, no trailers API:
  - `HttpVersion` gains `HTTP_3_0` (`HTTP_2_0` already existed and was never
    produced, so the enum was already ahead of the implementation).
  - A new `@ExposedInternals` `HttpMessages` (`io.activej.http.HttpMessages`)
    lets an alternative HTTP transport construct an `HttpRequest`/`HttpResponse`
    with an explicit `HttpVersion` — the existing version-carrying constructors
    are package-private and every public factory hardcodes `HTTP_1_1`. Reaching
    them from `HttpMessages` required loosening `HttpRequest.Builder()` and
    `HttpResponse.Builder()` from `private` to package-private (an
    accessibility change only — no parameter list changed on any method).

  `Http3Settings` carries the feature's tunable limits, every field an
  `ApplicationSettings` key resolved from
  `io.activej.http3.Http3Settings.<setting>` (or `Http3Settings.<setting>`):

  | Setting | Default | What it bounds |
  |---|---|---|
  | `maxFieldSectionSize` | `64kb` | RFC 9114 §4.2.2 accounted field-section size, checked on decoded output |
  | `maxBodySize` | `100mb` | total DATA payload per message |
  | `maxControlFrameSize` | `16kb` | SETTINGS / GOAWAY / control-stream frames |
  | `maxConcurrentRequestStreams` | `100` | advertised as the QUIC bidirectional-stream transport parameter |
  | `maxUniStreams` | `3` (fixed, not a builder field) | advertised `initial_max_streams_uni` — control + both QPACK streams, nothing more (FR-017) |
  | `maxConnections` | `256` | `Http3Client` pool size; the least-recently-used idle connection is evicted past it |
  | `maxQueuedRequests` | `100` | requests waiting for stream credit; overflow fails immediately, retryably |
  | `maxInterimResponses` | `8` | informational (`1xx`) responses a client reads past on one exchange; past it the exchange fails with `H3_EXCESSIVE_LOAD` |
  | `requestTimeout` | `60s` | per request on both sides, queued time included |
  | `shutdownTimeout` | `1s` | ceiling on the GOAWAY drain of `Http3Server.close()`; `0` closes at once |

  None of these bounds is ever allocated in advance. A declared frame length is
  checked against its bound before anything proportional to it is taken, and a
  DATA frame is then read in 16 KiB instalments as its payload actually
  arrives — so a peer that declares a 100 MB body and then sends a kilobyte of
  it costs a chunk per stream rather than the bound per stream, and the peak a
  connection can be driven to is its concurrent-stream limit times a chunk.

  Each of `maxFieldSectionSize`, `maxBodySize` and `maxControlFrameSize` is
  refused at `build()` above `Integer.MAX_VALUE`: all three reach a frame
  reader's declared-length check, which allocates a validated length as an
  `int`, so a larger bound would wrap negative instead of doing what it says.

  `Http3Errors`/`Http3Exception` carry the RFC 9114 §8.1 and RFC 9204 §6
  application error codes — a third axis alongside `HttpError` (status code)
  and `QuicTransportException` (RFC 9000 §20 transport code).

  **Message validation is RFC 9114 §4.1.2/§4.3.1 strict.** Beyond the
  pseudo-header rules: a field *value* carrying NUL, CR or LF at any position,
  or leading/trailing space or tab, is `H3_MESSAGE_ERROR` — HTTP/3 has no
  line-oriented framing, but the value reaches a servlet and the hop after that
  may be HTTP/1.1. A `:path` under `http`/`https` must be origin-form
  (`/`-prefixed); the asterisk form (`OPTIONS *`) has no representation in
  `core-http`'s URL model and is refused with the retryable
  `H3_REQUEST_REJECTED` rather than mangled into an origin-form URL. A repeated
  `host`, or a repeated `content-length` whose values disagree, is
  `H3_MESSAGE_ERROR` (RFC 9110 §7.2, §8.6): only the first was ever reconciled
  against the DATA that arrived, and for a gateway re-serializing to HTTP/1.1
  the pair is a request-smuggling primitive. Repeated *identical*
  `content-length` stays legal, as RFC 9110 §8.6's list form.

  **Informational (`1xx`) responses are consumed, not delivered.** RFC 9114 §4.1
  lets a server send any number ahead of the final response, and `103 Early
  Hints` arrives unsolicited from real CDNs; `Http3Client` reads past each and
  delivers the final response, bounded by `maxInterimResponses` above. They are
  not surfaced to the caller — `IHttpClient` has nowhere to put one.

  **Server.** `Http3Server.builder(reactor, servlet)` serves an existing
  `AsyncServlet` unmodified over QUIC — `withListenAddress`/`withListenPort` or
  `withSocket`, `withServerIdentity`, `withSettings`, `withHttpErrorFormatter`,
  then `listen()` and an idempotent `close()`. It owns its `QuicEndpoint` and
  attaches through `QuicEndpoint.Builder.withFrameHandlerFactory`, so it neither
  extends `AbstractReactiveServer` (UDP has no accept loop) nor reaches into
  `core-quic` internals. A failed servlet promise is rendered through the same
  `HttpExceptionFormatter` `HttpServer` uses, so an error response is identical
  across HTTP/1.1 and HTTP/3, and the stream carrying it ends normally rather
  than being reset. **`close()` is graceful** (RFC 9114 §5.2): every connection
  announces GOAWAY carrying the last request stream it will process, the
  exchanges already under way are left to finish, and only then does the
  endpoint go — so a shutdown no longer fails a request the server had already
  begun serving. A request stream opened after the announcement is refused with
  `H3_REQUEST_REJECTED` without reaching the servlet, which tells the peer to
  retry it elsewhere. The drain is bounded by the new `shutdownTimeout`
  (**1 second** by default, `0` to close at once), because a peer that never
  finishes must not be able to hold a closing server open; it ends as soon as
  the last announced stream closes, which is the ordinary case. `Http3RequestStream` is the per-stream exchange underneath:
  it drives the RFC 9114 §4.1 frame sequence, carries bodies as CSP channels
  transformed from `QuicStream.reader()`/`writer()` (no buffering between HTTP
  and QUIC, so backpressure stays QUIC's stream flow control), and surfaces a
  peer's `RESET_STREAM`/`STOP_SENDING` to the request unwrapped, carrying the
  peer's own application error code. `Http3RequestStream` is symmetric — a server
  calls `receiveRequest()` then `sendResponse(...)`, a client `sendRequest(...)`
  then `receiveResponse()` — so both roles drive one frame-sequence, QPACK and
  body-channel implementation rather than two.

  **Client.** `Http3Client.builder(reactor, dnsClient)` implements `IHttpClient`
  — `Promise<HttpResponse> request(HttpRequest)` — so a caller's existing
  `HttpRequest`/`HttpResponse` code is unchanged. It is a separate component from
  `HttpClient`, whose connection model is TCP-bound. It owns one `QuicEndpoint`
  on an ephemeral port (or `withSocket`) shared by every pooled connection, and
  keeps **at most one QUIC connection per authority** (scheme, host, port);
  concurrent requests racing to the same authority share one in-flight connect
  promise and resolve the host once. `withTlsEngineFactory(host -> ...)` supplies
  the TLS engine per authority host — the default trusts the platform PKIX store,
  with SNI and RFC 6125 endpoint identification against that host. At
  `maxConnections` the least-recently-used **idle** connection leaves with
  GOAWAY to make room; with every pooled connection busy the request fails
  immediately with a retryable `Http3Exception` naming the setting key, and the
  pool never grows. **Idle** is the strict reading: an exchange ends at the
  response head, but a connection still streaming that response's body into a
  caller's hands is busy, so no eviction severs a transfer in progress. A GOAWAY
  received on a pooled connection **retires** it: the next request to that
  authority opens a fresh connection, while the retired one stays open for the
  requests it is already carrying — and is closed as soon as it carries nothing,
  so a peer that GOAWAYs every connection it accepts leaves nothing accumulating
  (`retiredConnectionCount()` reports what it does). A request that races
  the announcement fails with a retryable `H3_REQUEST_REJECTED` naming the
  condition. Request streams above a received GOAWAY identifier fail the same
  way — the peer stated it never processed them — while streams at or below it
  are left to finish; a successor identifier **higher** than one already
  received closes the connection with `H3_ID_ERROR`.
  A request that finds no bidirectional-stream credit waits (the transport
  withholds the open and announces `STREAMS_BLOCKED`) until `MAX_STREAMS` grants
  some, bounded by `maxQueuedRequests`; past that bound it fails immediately and
  retryably, again naming the key. `requestTimeout` covers queued time as well as
  in-flight time and resets the stream with `H3_REQUEST_CANCELLED` on expiry. A
  non-`https` scheme fails before any socket work or name resolution, and a
  transport or TLS failure reaches the caller **unwrapped** as the
  `QuicTransportException` that carries the RFC 9000 §20 code. A response that is
  not a well-formed HTTP message fails the promise with `MalformedHttpException`
  — the type `HttpClient` already raises for its own malformed responses, so code
  written against `IHttpClient` catches one type whichever client is under it —
  carrying the `Http3Exception(H3_MESSAGE_ERROR)` as its cause. `close()` is
  idempotent and fails every outstanding request exactly once. The response the
  promise delivers is the caller's, as is the `ByteBuf` its `loadBody()` produces.

  **Server push and extended CONNECT are refused, precisely.** Server push is
  permanently out of scope: the client never sends `MAX_PUSH_ID`, so its push
  limit stays 0, and the server never pushes. Each construct a peer can still
  reach for is rejected with its exact RFC 9114 code rather than ignored — a
  push stream opened at a client, or a `PUSH_PROMISE` frame, closes the
  connection with `H3_ID_ERROR` (RFC 9114 §4.6/§7.2.5 judge both against the
  push limit, so this is *not* the generic `H3_FRAME_UNEXPECTED` that
  `CANCEL_PUSH`/`SETTINGS`/`GOAWAY`/`MAX_PUSH_ID` on a request stream get); a
  `CANCEL_PUSH` naming a push id that was never promised — which, here, is every
  one of them — is `H3_ID_ERROR`; and a `MAX_PUSH_ID` reaching a *client* is a
  direction violation, `H3_FRAME_UNEXPECTED`. A server records the `MAX_PUSH_ID`
  a client sends, acts on it never, and closes the connection with `H3_ID_ERROR`
  if a successor carries a **lower** value. Extended CONNECT and WebSocket over
  HTTP/3 (RFC 9220) are likewise out of scope: a `CONNECT` request, or any
  request carrying a `:protocol` pseudo-header, is refused with
  `H3_REQUEST_REJECTED` — retryable, and without the servlet ever seeing it —
  rather than partially handled.

  **Diagnostics, without a JMX dependency.** As in `core-quic`, in two forms:
  plain counter accessors — `Http3Server.requestsServed()`,
  `Http3Client.queuedRequestCount()` and the rest — and an optional `Inspector`
  hook on each of `Http3Server` and `Http3Client`, both extending
  `BaseInspector`, both **absent by default**, and neither ever gating a
  counter: every accessor reads the same with an inspector attached and without
  one. Between them they report a request started and completed (stream id,
  method, status, DATA byte counts), a stream reset and a connection error (the
  RFC 9114 §8.1 code), a frame discarded under RFC 9114 §9's GREASE rule, a
  GOAWAY in either direction, and — on the client — a request queued and
  dequeued with the queue depth. Every parameter is a number, an HTTP method or
  a direction: no inspector call, log line, counter or exception message ever
  carries a field value, a body byte, a cookie, an authorization credential or
  key material. `core-http3` gains no `activej-jmxapi` edge for this.

- **HTTP/3 QPACK dynamic table, configured but off by default.** `core-http3`
  gains the RFC 9204 §3.2 dynamic table on both sides — the encoder inserts,
  references and duplicates entries on its QPACK encoder stream, the decoder
  applies a peer's insertions and answers on its decoder stream — so a repeated
  field costs an index rather than its bytes. **Nothing changes for an existing
  consumer**: the capacity default is `0`, which opens no QPACK stream, emits no
  instruction, references nothing dynamic and produces the byte-for-byte SETTINGS
  frame and field sections of the static-table implementation in the entry above.
  One builder call enables it:
  `Http3Settings.builder().withQpackMaxTableCapacity(MemSize.kilobytes(4))`. The
  two `Http3Settings` constants that moved to make the capacity configurable are
  recorded under **Breaking changes**.

  All five settings are `ApplicationSettings` keys resolved against
  `Http3Settings`, so the fully qualified and the short spelling work alike —
  `-Dio.activej.http3.Http3Settings.qpackMaxTableCapacity=4kb` or
  `-DHttp3Settings.qpackMaxTableCapacity=4kb`, and likewise for the other four.

  | Setting | Default | What it does | Opt out with |
  |---|---|---|---|
  | `qpackMaxTableCapacity` | `0` (disabled) | advertised `SETTINGS_QPACK_MAX_TABLE_CAPACITY`; a non-zero value is the single switch that enables the table, per direction per connection | `…qpackMaxTableCapacity=0` / `withQpackMaxTableCapacity(MemSize.ZERO)` |
  | `qpackBlockedStreams` | `16` | request streams this endpoint would permit to be blocked on an unreceived insertion; bounds `qpackBlockedStreams × maxFieldSectionSize` = 1 MB of held sections at the defaults | `…qpackBlockedStreams=0` / `withQpackBlockedStreams(0)` |
  | `qpackNeverIndexedFields` | `authorization,proxy-authorization,set-cookie` | field names the encoder never indexes, emitting the RFC 9204 §7.1 never-indexed literal instead. **`cookie` is deliberately absent** — it is the largest repeated field in browser traffic and the main reason the table pays for itself; a deployment with a compression-oracle threat model adds it | `…qpackNeverIndexedFields=authorization,proxy-authorization,set-cookie,cookie` / `withQpackNeverIndexedFields(Set.of(…))` |
  | `qpackMaxInstructionSize` | `16kb` | bounds one buffered encoder- or decoder-stream instruction, which arrives a few bytes at a time; past it the connection closes with `QPACK_ENCODER_STREAM_ERROR` (0x0201) or `QPACK_DECODER_STREAM_ERROR` (0x0202), whichever stream it was, rather than buffering on | `…qpackMaxInstructionSize=64kb` / `withQpackMaxInstructionSize(…)` |
  | `qpackBlockedStreamTimeout` | `10s` | how long a field section blocked on an unreceived insertion is held before the connection closes with `QPACK_DECOMPRESSION_FAILED` (0x0200); `0` disables the timeout | `…qpackBlockedStreamTimeout=0s` / `withQpackBlockedStreamTimeout(Duration.ZERO)` |

  Each of the last four is inert while the capacity is `0`: nothing is inserted,
  so nothing is indexed, no instruction is buffered and no section can block.
  `SETTINGS_QPACK_BLOCKED_STREAMS` is advertised as `0` whatever
  `qpackBlockedStreams` says for as long as a blocked section has nowhere to
  wait — a permission this endpoint cannot honour would cost conformance, while
  advertising `0` costs only compression. `initial_max_streams_uni` stays `3`:
  control plus both QPACK streams was always the right number, and the streams
  being used now does not make them more numerous.

  Three counters join each `Inspector`, **as defaulted methods**, so no existing
  implementation breaks: `onQpackInsertions` and `onQpackEvictions` (which of
  the connection's two tables, how many entries, and its RFC 9204 §3.2.1 size
  afterwards) and `onQpackFieldSectionEncoded` (field lines and how many of them
  came out of the table — the two numbers a hit rate is computed from, reported
  per section so a consumer picks its own window). Every parameter is a number
  or which-table: a field name or a field value has no way to reach an inspector.

  Measured, on a Chrome-shaped 17-field request repeated over one connection at
  the 4 KB capacity (`benchmarks/http3`, `-P examples`): request 1 costs 20 B of
  field section plus 482 B of encoder stream, and requests 2..N cost **20 B each
  — 96 % less than request 1 and 96 % less than the 493 B the static-table
  encoder spends on every one of them**. A browsing sequence whose `:path` and
  `referer` change scores 93 %. A **256 B** capacity is a pessimisation, not a
  small win: entries are evicted before they are reused, so every request pays
  its insertions again on the encoder stream and the connection sends about
  twice what phase 1 would. Pick a capacity that holds the traffic's repeated
  fields, or leave it at `0`.

  Both dynamic-table lookups — by name and by name and value — are O(1) average
  in the table's size, and are now measured to be
  (`QpackDynamicTableLookupComplexityTest`). They index on a polynomial hash of
  the field name's octets rather than on `HttpHeader.hashCode()`, which is the
  *sum* of those octets: right for the open-addressed registry it was written
  for, and a linear scan through the back door here, since 2 560 distinct names
  produce only 83 distinct sums and `HttpHeader` is not `Comparable`, so
  `HashMap` cannot treeify the bucket either.

- **TLS 1.3 session resumption and HTTP/3 0-RTT, off by default.** `core-quic`
  gains RFC 8446 §2.2 / §4.6.1 resumption — a server issues `NewSessionTicket`
  messages sealed under its own `QuicTicketKeys`, a client stores them in a
  `QuicSessionCache` and offers one back as a `pre_shared_key` — and, on top of
  it, the RFC 9001 §4.6 0-RTT packet (long header, type `0x01`) in the
  Application packet-number space. `core-http3` wires both together: with the
  switch on, an `Http3Client` that has a usable ticket for an origin sends the
  next request to it in a 0-RTT packet, a full round trip before the handshake
  completes, and the `Http3Server` serves it there.
  **Nothing changes for an existing consumer**: `zeroRttEnabled` defaults to
  `false` on both endpoints, which offers no ticket, issues none, allocates no
  ticket store and no sealing keys, and produces the byte-for-byte phase-1
  handshake.

  Two builder calls turn it on — `Http3Server.builder(…).withTicketKeys(…)` (or
  none, letting `listen()` generate a set) and
  `Http3Client.builder(…).withSessionCache(…)` (or none, for the bounded
  in-memory default) — plus `withSettings(Http3Settings.builder()
  .withZeroRttEnabled(true).build())` on each. A client that supplies its own
  `withTlsEngineFactory` owns its `TlsClientConfig` and so opts out of resumption
  entirely; `withTlsClientConfig(Initializer<TlsClientConfig.Builder>)` is the
  new seam for decorating the config the client builds instead of replacing it.

  **Why it is off by default, and what turning it on accepts.** Early data is
  weaker than a fresh handshake in two ways that a deployment has to decide about
  rather than inherit:

  1. **Forward secrecy is delayed for the early data specifically.** The session
     is resumed with `psk_dhe_ke` and never `psk_ke`, so the (EC)DHE exchange
     always happens and the connection as a whole keeps forward secrecy — but the
     0-RTT packets are protected under a key derived from the *stored ticket*,
     which was derived before that exchange. Anything that recovers the ticket
     recovers the early data with it, and the ticket sits in the client's store
     for `sessionTicketLifetime` — longer if a consumer supplies a
     `QuicSessionCache` that persists across restarts, which is exactly the
     trade-off that interface exists to let them make.
  2. **Early data is replayable, and the defence against it is a policy plus a
     register.** An observer who captures a 0-RTT flight can send it again to the
     same server, which has no handshake state to tell the copy from the
     original. RFC 8470's answer is applied at the HTTP layer: a request whose
     HEADERS arrived at `ZERO_RTT` is screened by the new `Http3EarlyDataPolicy`
     after it is mapped and before it is dispatched, the default accepts only the
     RFC 9110 §9.2.1 safe methods (`GET`, `HEAD`, `OPTIONS`, `TRACE`), and
     anything else is answered `425 (Too Early)` **without the servlet being
     invoked** — the client re-issues it once, transparently, at 1-RTT, so a
     caller sees only the final outcome. A request the policy accepts reaches the
     servlet carrying `Early-Data: 1` (RFC 8470 §5.1) — replacing any field of
     that name the peer sent, so the indication is the server's verdict and not a
     peer's claim — and application code can apply its own rule on top of it.
     Replace the deployment-wide rule with
     `Http3Server.builder(…).withEarlyDataPolicy(request -> …)`; an ordinary
     1-RTT request never reaches the policy at all.

     The other half — refusing a ticket that has already been used for early data
     — is the bounded single-use register `QuicReplayGuard`, one per
     `Http3Server`, consulted at the point the grant is actually made rather than
     at pre-shared-key selection: a ticket is single-use for *early data*, not for
     resumption, so a second presentation loses its early data and nothing else.
     The pre-shared key still authenticates the connection, the certificate flight
     is still skipped, and the handshake still completes at 1-RTT — failing it
     instead would hand an attacker a denial-of-service primitive out of the
     defence itself. It stores a SHA-256 digest of the sealed ticket identity, not
     the identity, so the entry bound is a real memory bound (~4 MB at the
     shipped 65 536) and no ticket material is held at rest; the lookup is
     constant-time **including the not-found case**, the same
     `MessageDigest.isEqual` primitive the PSK binder check uses. Eviction
     **fails closed**: a live record is never dropped, so a register under
     pressure degrades towards refusing every *new* 0-RTT grant rather than
     towards admitting a replay — a register that treated an evicted record as
     unseen would make its own bound the attack.

     Early data **without** a register is refused outright rather than run
     unprotected: `TlsServerConfig.Builder.build()` throws an
     `IllegalStateException` naming both settings when `earlyDataEnabled` is on
     and no `replayGuard` is set, so a direct `core-quic` consumer cannot reach a
     replay-vulnerable 0-RTT server by omission. `Http3Server` sets the two in one
     breath and is unaffected.

     **Residual limitation, stated as one rather than dressed as a mitigation.**
     The single-use register is **process-local** — and reactor-local: one per
     `Http3Server` instance, not shared across workers, processes or nodes.
     Behind a load balancer, an early-data flight replayed to a *different*
     instance is **not caught by it**. What protects that case is the safe-method
     default policy, which is why that default is not merely advisory and why
     widening it widens the exposure of a whole deployment rather than of one
     process. A deployment needing more must supply its own policy; a distributed
     strike register is out of scope. Emptiness after a restart is not a gap:
     `QuicTicketKeys` never persists its sealing keys, so no ticket issued before
     a restart can be opened after one.

  A request whose early data the server rejected — whether by omitting
  `early_data` from EncryptedExtensions or by answering `425 (Too Early)` — is
  re-issued once, transparently, on a fresh 1-RTT stream, and only the final
  outcome reaches the caller. The retry is deliberately explicit rather than left
  to QUIC loss recovery re-sending the frames once the 1-RTT keys exist: at most
  one retry per request, whichever signal arrived, and it is reported through
  `Http3Client.Inspector.onEarlyDataRetried` / `earlyDataRetried()`, which is the
  one event that would otherwise make the fallback invisible.

  The eight resumption bounds are `ApplicationSettings` keys resolved against
  `QuicConnection`, so the fully qualified and the short spelling work alike —
  `-Dio.activej.quic.connection.QuicConnection.sessionTicketLifetime=30m` or
  `-DQuicConnection.sessionTicketLifetime=30m` — and the HTTP/3 switch resolves
  against `Http3Settings` like every other one there.

  | Setting | Default | What it does | Change it with |
  |---|---|---|---|
  | `zeroRttEnabled` | `false` | the outer switch, per HTTP/3 endpoint. Off: no ticket offered or issued, no sealing keys, no ticket store, phase-1 bytes | `-DHttp3Settings.zeroRttEnabled=true` / `withZeroRttEnabled(true)` |
  | `sessionTicketLifetime` | `1h` | how long an issued ticket may be resumed, and how long a stored one is kept. It is also the replay window a deployment is accepting, so shortening it is the cheapest mitigation available | `-DQuicConnection.sessionTicketLifetime=15m` |
  | `sessionTicketKeyRotation` | `6h` | how often the server generates a fresh sealing key. Two keys are retained, so `lifetime ≤ rotation` guarantees no valid ticket becomes unopenable purely because of a rotation; `QuicTicketKeys.create` refuses a pair that does not satisfy it, and `Http3Server.listen()` fails its promise with a named `IllegalStateException` rather than throwing out of a void path | `-DQuicConnection.sessionTicketKeyRotation=1h` |
  | `sessionTicketsPerHandshake` | `2` | `NewSessionTicket` messages issued per completed handshake. More than one so a client can resume more than once without a fresh full handshake; `0` issues none | `-DQuicConnection.sessionTicketsPerHandshake=0` |
  | `maxSessionTickets` | `256` entries | the client's bounded LRU of stored tickets, keyed by (server name, port, ALPN). Expired entries are discarded on lookup | `-DQuicConnection.maxSessionTickets=32` |
  | `maxSessionTicketSize` | `8kb` | bounds one sealed ticket a server may send. A `NewSessionTicket` is untrusted input arriving *after* the handshake, and past this the connection closes rather than buffering on | `-DQuicConnection.maxSessionTicketSize=16kb` |
  | `maxSessionTicketsPerConnection` | `8` | bounds how many post-handshake tickets one connection may deliver, so a server cannot buy an unbounded number of PSK derivations on the client's reactor thread | `-DQuicConnection.maxSessionTicketsPerConnection=2` |
  | `ticketAgeTolerance` | `10s` | the window the server checks a ticket's obfuscated age against (RFC 8446 §4.2.11.1). Outside it the ticket is refused and the handshake falls back to a full one — never a failure | `-DQuicConnection.ticketAgeTolerance=30s` |
  | `maxEarlyDataReplayRecords` | `65536` entries | ticket identities the single-use replay register holds — one register per `Http3Server`, built only when `zeroRttEnabled` is on. Eviction fails closed, so this is the point at which a saturated register starts refusing *new* 0-RTT grants (`zeroRttRefusedAtCapacity()`) rather than admitting a replay; size it against the ticket lifetime and the resumption rate | `-DQuicConnection.maxEarlyDataReplayRecords=262144` |

  A ticket that cannot be opened, has expired, carries a different ALPN or server
  name, or fails its age check produces a **full handshake**, never an error and
  never an unauthenticated session. A server that accepts the ticket but declines
  the early data signals that by *omitting* `early_data` from EncryptedExtensions,
  which is likewise not a failure.

  Nothing on this path reaches a log line, an exception message, a `toString()`
  or an inspector: not a ticket byte, not a ticket identity, not a resumption
  secret, not a PSK binder, not a sealing key. The counters that do join each
  `Inspector` — **as defaulted methods**, so no existing implementation breaks —
  are numbers and two enums: `onSessionTicketOffered` / `onSessionTicketStored` /
  `onZeroRttAttempted` / `onZeroRttDecision` / `onEarlyDataRetried` on
  `Http3Client.Inspector`, and `onSessionTicketsIssued` / `onSessionResumed` /
  `onEarlyDataRefused` on `Http3Server.Inspector`, mirrored by the plain
  accessors `sessionTicketsOffered()`, `sessionTicketsStored()`,
  `zeroRttAttempted()`, `zeroRttAccepted()`, `zeroRttRejected()`,
  `earlyDataRetried()`, `sessionTicketsIssued()`, `sessionsResumed()` and
  `zeroRttAccepted()`.

  "Why was 0-RTT refused" is deliberately four numbers rather than one, because a
  deployment reacts to them differently. `onEarlyDataRefused` carries an
  `Http3Connection.EarlyDataRefusal` — `REPLAYED` (the security signal: the
  register had already granted that ticket identity a use), `AT_CAPACITY` (an
  availability signal: `maxEarlyDataReplayRecords` is too small for the ticket
  lifetime in force), `EXPIRED` (defence in depth; the TLS engine already skips
  an expired ticket at pre-shared-key selection, so it stays 0) and `POLICY` (a
  request answered `425` by the early-data policy, which under the default is
  ordinary traffic meeting the safe-method rule). Each has its own accessor on
  `Http3Server` — `zeroRttRefusedAsReplay()`, `zeroRttRefusedAtCapacity()`,
  `zeroRttRefusedAsExpired()`, `earlyDataRequestsRefused()` — and those read the
  register directly, so they are exact whether or not an inspector is attached.
  All four stay 0 for the life of a server with `zeroRttEnabled` off. Bear the
  process-local limit above in mind when reading `zeroRttRefusedAsReplay()`: it
  counts the replays *this instance* caught, and is not a count of the replays
  aimed at the deployment.

- **HTTP/3 datagrams, off by default.** `core-quic` gains the RFC 9221 DATAGRAM
  frame's semantics — the `max_datagram_frame_size` transport parameter (0x20), a
  bounded outbound queue, and the per-encryption-level legality that keeps a
  DATAGRAM out of Initial and Handshake packets — and `core-http3` gains RFC 9297
  on top of it: `SETTINGS_H3_DATAGRAM` (0x33), the quarter-stream-ID payload
  encoding, and `H3_DATAGRAM_ERROR` (0x33) on `Http3Errors`. (Setting identifier
  `0x33` and error code `0x33` are two independent registries; the numeric
  coincidence means nothing.) **Nothing changes for an existing consumer**:
  `datagramsEnabled` defaults to `false` on both endpoints, which advertises no
  transport parameter, sends no `SETTINGS_H3_DATAGRAM`, allocates no queue and
  produces the byte-for-byte phase-1 connection.

  One builder call on each endpoint turns it on —
  `withSettings(Http3Settings.builder().withDatagramsEnabled(true).build())` — and
  `Http3Server` / `Http3Client` derive the transport parameter from
  `maxDatagramSize` themselves, so there is nothing else to configure.

  The developer-facing surface is a **per-exchange handle** on the existing
  `HttpMessage` attachment mechanism — the seam HTTP/3 trailers already use, so
  **`core-http` is unchanged**. A servlet reaches it from the request it is
  serving, a client caller from the request it issued or the response it received:
  `Http3DatagramChannel datagrams = Http3Datagrams.of(message)`, `null` when
  datagrams are off or the message is not an HTTP/3 one. It exposes
  `isAvailable()` (queryable *before* the first send), `maxPayloadSize()`,
  `send(ByteBuf)`, `poll()`, `setReceiveHandler(…)` and four counters. **No QUIC
  stream ID appears anywhere in it**; the quarter stream ID is an internal
  encoding detail.

  `send` **takes ownership of the payload on every path, refusals included** — it
  is recycled before the checked `Http3DatagramException` is thrown, and recycling
  it again at the call site is a double free. The four refusal reasons are
  `NOT_NEGOTIATED`, `OVERSIZE`, `QUEUE_FULL` and `EXCHANGE_ENDED`; none is a
  protocol violation and none closes anything. An oversize payload is refused
  **whole** rather than truncated or split, because RFC 9221 §3 permits neither. A
  datagram is deliberately **not** a CSP channel: CSP promises a producer that a
  withheld promise means "wait", and on a channel with no retransmission there is
  nothing to wait for.

  | Setting | Default | What it does | Change it with |
  |---|---|---|---|
  | `datagramsEnabled` | `false` | the outer switch, per HTTP/3 endpoint. Off: nothing advertised, nothing sent, no queue allocated, phase-1 bytes | `-DHttp3Settings.datagramsEnabled=true` / `withDatagramsEnabled(true)` |
  | `maxInboundDatagramsPerStream` | `32` | the bounded per-exchange inbound queue. At the bound the **oldest** queued datagram is dropped and counted — never the connection, never unbounded growth. `0` accepts none | `-DHttp3Settings.maxInboundDatagramsPerStream=8` / `withMaxInboundDatagramsPerStream(8)` |
  | `maxDatagramFrameSize` | `0` (disabled) | the RFC 9221 §3 transport parameter. `0` means *not supported* and is not advertised at all; an `Http3Server`/`Http3Client` with datagrams on sets it to the largest frame that fits `maxDatagramSize`, which is 1309 bytes at the 1350-byte default | `-DQuicConnection.maxDatagramFrameSize=1200` |
  | `maxOutboundDatagrams` | `64` | the bounded outbound queue. At the bound the send is **refused** rather than queued, and a datagram that cannot be placed in the next packet within its bound is dropped and counted rather than held indefinitely | `-DQuicConnection.maxOutboundDatagrams=256` |

  The two HTTP/3 settings resolve against `Http3Settings` and the two QUIC ones
  against `QuicConnection`, so the fully qualified and the short spelling work
  alike — `-Dio.activej.http3.Http3Settings.datagramsEnabled=true` or
  `-DHttp3Settings.datagramsEnabled=true`, and likewise for the rest.

  A lost DATAGRAM frame is **released, never retransmitted** (RFC 9221 §5), which
  is what the existing `QuicFrameHandler.onFrameLost` default already did. A
  datagram whose quarter stream ID names an exchange that has completed, been
  reset or not yet been opened is **dropped silently and counted** — reordering
  makes that normal on an unreliable channel, and it is not an error. What *is* an
  error, closing the connection with `H3_DATAGRAM_ERROR`, is a quarter stream ID
  that maps to a stream which is not client-initiated bidirectional, one whose
  `× 4` would exceed 2^62−1, and a truncated varint; a `SETTINGS_H3_DATAGRAM`
  value other than `0` or `1`, and a peer sending `1` without
  `max_datagram_frame_size`, close with `H3_SETTINGS_ERROR` (0x0109) instead.

  Five counters join each `Inspector`, **as defaulted methods**, so no existing
  implementation breaks: `onDatagramSent`, `onDatagramReceived`,
  `onDatagramDroppedByQueue`, `onDatagramDroppedByLoss` and
  `onDatagramRefusedOversize`. Every parameter is a stream id, a size or a running
  total — a payload byte has no way to reach an inspector, a log line or an
  exception message.

  RFC 9220 Extended CONNECT stays **refused** exactly as before: datagrams here
  bind to ordinary request/response exchanges only, and WebTransport and MASQUE
  remain out of scope.

- **QUIC connection layer.** A new `io.activej.quic.connection` package in
  `core-quic` turns the wire codec and the TLS engines into a working transport:
  `QuicConnection` (the reactor-confined state machine — handshake, ACK
  scheduling, RFC 9002 loss detection and probe timeouts, NewReno congestion
  control, RFC 9000 §10 termination, Version Negotiation and Retry) and
  `QuicEndpoint` (many connections over one UDP socket, dispatched by
  destination connection ID). `QuicFrameHandler` is the extension point for the
  layer above; the transport keeps PADDING, PING, ACK, CRYPTO,
  CONNECTION_CLOSE and HANDSHAKE_DONE for itself. This is the first
  reactor-facing surface in `core-quic`, and the module's first dependency on
  `activej-net`. A handler contributes frames with
  `QuicConnection.enqueueFrame` (appended) and retransmits with
  `QuicConnection.requeueFrame` (ahead of everything queued, RFC 9002 §6.5) —
  the distinction matters because a handler may have queued far more than a
  congestion window, and an appended retransmission would wait out that whole
  backlog while the receiver holds every byte past the gap it fills.

  Diagnostics come in two forms, neither of which pulls in a JMX dependency:
  plain counter accessors, and an optional `Inspector` hook on both
  `QuicConnection` (packet sent/received/lost, RTT metrics, congestion-state
  change, connection-state transition) and `QuicEndpoint` (datagram
  received/dropped, connection created/refused), following the
  `UdpSocket.Inspector` precedent in `core-net`. Both default to none;
  `QuicEndpoint.Builder.withConnectionInspector` gives one to every connection
  the endpoint creates, which is the only way to reach an accepted server
  connection. The same events are logged at debug level under the qlog event
  vocabulary (`transport:packet_sent`, `transport:packet_received`,
  `recovery:packet_lost`, `recovery:metrics_updated`,
  `recovery:congestion_state_updated`); no log line carries key material or
  frame payloads.

  Every limit is an `ApplicationSettings` key resolved from
  `io.activej.quic.connection.QuicConnection.<setting>` (or
  `QuicConnection.<setting>`), except the two endpoint bounds, which resolve
  from `QuicEndpoint`:

  | Setting | Default | What it bounds |
  |---|---|---|
  | `maxDatagramSize` | `1350` bytes | the largest UDP payload sent; must be 1200–65527 (RFC 9000 §14.1) |
  | `maxIdleTimeout` | `30s` | silence before a connection is discarded; `0` disables, and the effective value is floored at 3 × PTO |
  | `handshakeTimeout` | `10s` | time for the handshake to complete before the connection is abandoned |
  | `maxAckRanges` | `32` | ACK ranges tracked per packet number space; the oldest are dropped past it |
  | `maxCryptoBufferBytes` | `64kb` | out-of-order CRYPTO data held per level; exceeding it closes with `CRYPTO_BUFFER_EXCEEDED` |
  | `maxSendQueueBytes` | `1mb` | queued outgoing frame bytes; exceeding it fails the enqueue with `INTERNAL_ERROR` |
  | `initialCongestionWindow` | RFC 9002 §7.2 formula | the starting congestion window; must be at least 2 × `maxDatagramSize` |
  | `maxBufferedDatagramsAwaitingKeys` | `4` | packets held for keys not yet installed; the oldest is dropped past it |
  | `connectionIdLength` | `8` bytes | the length of connection IDs this endpoint issues |
  | `keepAliveInterval` | unset | opt-in keep-alive PING; refused at `build()` above half `maxIdleTimeout` |
  | `QuicEndpoint.maxConnections` | `10000` | live connections per endpoint; new inbound attempts are dropped past it |
  | `QuicEndpoint.maxHandshakingConnections` | `1000` | connections still handshaking — a much smaller bound, because a half-open connection costs a TLS engine for an unvalidated peer |

- **QUIC TLS 1.3 handshake engine.** A new `io.activej.quic.tls` package in
  `core-quic` implements the TLS 1.3 handshake over the QUIC transport (RFC 8446
  with RFC 9001 profile): `TlsClientEngine`/`TlsServerEngine`, message codec,
  key schedule, certificate verification, ALPN and QUIC transport parameter
  negotiation. The engine is synchronous (non-reactive) per the QUIC module
  convention; thread confinement is the caller's contract.

- **HTTP/3 launcher and examples.** Two new modules make HTTP/3 runnable as an
  application rather than a library. `launchers/http3` (`activej-launchers-http3`,
  default build) ships `Http3ServerLauncher` — a `Launcher` subclass wiring DI,
  the service graph, `Config` and a `Http3ServerServiceAdapter` that bridges
  `listen()`/`close()` into the service lifecycle — plus `Initializers`
  (`ofHttp3Server(Config)` / `ofHttp3Settings(Config)`) and a demo `main`.
  `examples/core/http3` (`examples-http3`, `-P examples`) ships `Http3HelloWorld`
  and `Http3ClientExample` with a committed development-only self-signed
  certificate (openssl command recorded beside it). The `core-http3` interop
  harness additionally gains an automated, skipping regression suite
  (`Http3CurlInteropTest`, `Http3RealSocketInteropTest`) that drives a real
  foreign curl across seven cases when one is available.

  **No default changed and no new `ApplicationSettings` limit was added.** Every
  launcher config key falls back to `Http3Settings`' own defaults (or is
  required, for the certificate pair); the feature only adds the two modules
  above, so nothing that previously ran behaves differently after upgrading.
  One new failure mode is worth noting: `Http3ServerLauncher.onStart()` logs
  the actually bound address through a **bounded** submit bridge to the
  server's reactor (10 seconds), because the accessor is reactor-thread-guarded
  and the hook runs on the launcher thread — a wedged reactor now fails startup
  with `TimeoutException` rather than logging a stale configured address.

- **Bound-address accessors and single-certificate trust.** Three additive
  public-API additions across the QUIC/HTTP-3 stack. `IUdpSocket` (`activej-net`)
  gains a **defaulted** `getLocalAddress()` answering the bound address or
  `null` — `default` rather than abstract because `activej-net` is published and
  an abstract method would break every existing implementer, and `null` covers
  both "not bound" and an implementation that models no local address.
  `Http3Server` (`activej-http3`) gains `getBoundAddress()`, delegating to the
  socket it already holds, so every construction path — `withListenPort(0)`
  included — can be asked where it is actually listening, throughout the GOAWAY
  drain; like every public method of a reactive component it is
  reactor-thread-guarded, so a caller on another thread reads it through a
  submit bridge.   `TlsClientConfig.Builder` (`activej-quic`) gains
  `withTrustedCertificate(X509Certificate)` beside `insecureTrustAll()` — trust
  exactly one end-entity certificate, with the certificate's validity window
  still checked, RFC 6125 endpoint identification left at its default (on for
  hostnames) and client authentication refused — replacing the five
  byte-identical `trustingLeaf` copies that used to be hand-rolled at each call
  site.

  **No default changed and no new `ApplicationSettings` limit was added**, so
  nothing here belongs under Breaking changes.
