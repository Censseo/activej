# TLS test fixtures for the HTTP/3 wire harness

Development-only identity used by `io.activej.http3.testutil.Http3TestTls` to run a real QUIC
handshake in process (`Http3WirePair`). Self-signed, 100-year validity, SANs covering `localhost`
and `example.test`. Never use outside tests.

| File | Provenance |
|---|---|
| `ecdsa-cert.pem` / `ecdsa-key.pem` | Byte-for-byte copies of `core-quic/src/test/resources/io/activej/quic/tls/ecdsa-{cert,key}.pem` (ECDSA P-256 self-signed chain + unencrypted PKCS#8 key). Copied rather than shared because those are **test-scope resources of another module**, and research Decision 12 rejects a `core-quic` `test-jar` edge just to reach them. Regeneration commands live in that module's `README.md`. |
