# Changelog

## Unreleased — security and robustness hardening

This release hardens core and networked components against resource exhaustion,
request smuggling and abuse. Several safe-by-default limits are now enforced;
applications that relied on the previous unlimited behavior must opt out
explicitly.

### Breaking changes

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

### Notable fixes

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
