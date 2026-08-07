# Development-only TLS identity for the HTTP/3 examples

`dev-cert.pem` / `dev-key.pem` are the **development-only** identity the examples serve: a
self-signed ECDSA P-256 certificate, 100-year validity, SANs covering `localhost` and
`example.test`, with an unencrypted PKCS#8 key.

**Never use this identity anywhere but a development machine.** It is committed so
`Http3HelloWorld` runs with no arguments and `Http3ClientExample` can trust exactly its leaf
(RFC 6125 hostname verification stays live — no `insecureTrustAll` anywhere). A private key
committed to a repository is fine for a demo certificate and unforgivable for anything else.

| File | Provenance |
|---|---|
| `dev-cert.pem` / `dev-key.pem` | Byte-for-byte copies (md5 `763ab0f3…` / `9768cd59…`) of `launchers/http3/src/test/resources/io/activej/launchers/http3/ecdsa-{cert,key}.pem` (T035), which are themselves copies of the `core-quic` / `core-http3` test fixtures. Copied rather than shared: those are test-scope resources of other modules, and main scope must not reach them. |

Regeneration (recorded verbatim from `core-quic/src/test/resources/io/activej/quic/tls/README.md`):

```bash
# ECDSA P-256 chain + unencrypted PKCS#8 key, SANs covering localhost
openssl req -x509 -newkey ec -pkeyopt ec_paramgen_curve:P-256 \
  -keyout dev-key.pem -out dev-cert.pem \
  -days 36500 -nodes -subj "/CN=localhost" \
  -addext "subjectAltName=DNS:localhost,DNS:example.test"
```

**Do not regenerate for a run or a test**: `Http3ClientExample` and the launcher serve tests trust
exactly this leaf, so a differently-generated certificate fails in a way that reads like a TLS bug.
Keep the files in lockstep with the `core-quic` / `core-http3` / `launchers/http3` fixtures.
