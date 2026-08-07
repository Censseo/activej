# TLS test fixtures for the HTTP/3 launcher tests

Development-only identity used by `io.activej.launchers.http3.Http3ServerLauncherTest` to run a real
QUIC handshake in process against a launched `Http3ServerLauncher`. Self-signed, 100-year validity,
SANs covering `localhost` and `example.test`. **Never use outside tests.**

| File | Provenance |
|---|---|
| `ecdsa-cert.pem` / `ecdsa-key.pem` | Byte-for-byte copies of `core-http3/src/test/resources/io/activej/http3/testutil/ecdsa-{cert,key}.pem`, which are themselves copies of `core-quic/src/test/resources/io/activej/quic/tls/ecdsa-{cert,key}.pem` (ECDSA P-256 self-signed chain + unencrypted PKCS#8 key). Copied rather than shared because those are **test-scope resources of another module**, and research Decision 12 rejects a `test-jar` edge just to reach them. |

Regeneration (recorded verbatim from `core-quic/src/test/resources/io/activej/quic/tls/README.md`):

```bash
# ECDSA P-256 chain + unencrypted PKCS#8 key
openssl req -x509 -newkey ec -pkeyopt ec_paramgen_curve:P-256 \
  -keyout ecdsa-key.pem -out ecdsa-cert.pem \
  -days 36500 -nodes -subj "/CN=localhost" \
  -addext "subjectAltName=DNS:localhost,DNS:example.test"
```

**Do not regenerate for a test run**: the serve test's client trusts exactly this leaf, so a
differently-generated certificate would fail the suite in a way that reads like a TLS bug. Keep the
files in lockstep with the `core-quic` / `core-http3` fixtures.
