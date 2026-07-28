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

- **QUIC TLS 1.3 handshake engine.** A new `io.activej.quic.tls` package in
  `core-quic` implements the TLS 1.3 handshake over the QUIC transport (RFC 8446
  with RFC 9001 profile): `TlsClientEngine`/`TlsServerEngine`, message codec,
  key schedule, certificate verification, ALPN and QUIC transport parameter
  negotiation. The engine is synchronous (non-reactive) per the QUIC module
  convention; thread confinement is the caller's contract.
