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

### Notable fixes

- Fixed an authentication bypass in `BasicAuthServlet` when a username was
  absent from the credentials store, while preserving constant-time comparison.
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
  | `requestTimeout` | `60s` | per request on both sides, queued time included |

  `Http3Errors`/`Http3Exception` carry the RFC 9114 §8.1 and RFC 9204 §6
  application error codes — a third axis alongside `HttpError` (status code)
  and `QuicTransportException` (RFC 9000 §20 transport code).

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
