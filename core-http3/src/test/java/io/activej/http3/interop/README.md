# HTTP/3 interop harness

Two `main` programs for pointing this module at **someone else's** HTTP/3 implementation, and for
letting someone else's client point at ours. Not tests — Surefire never runs them.

## Why this exists

Every one of `core-http3`'s tests, and every one of `core-quic`'s, is ActiveJ↔ActiveJ. That
topology cannot catch a place where **both sides agree on something the RFC does not say**, and on
2026-08-04 it turned out to be hiding two real defects:

| Found | Defect | Where it actually was |
|---|---|---|
| `Http3InteropClient` vs Caddy (quic-go) | `ticket_nonce<1..255>` enforced where RFC 8446 §4.6.1 says `<0..255>`; an empty nonce tore down an **established** connection with `CRYPTO_ERROR(50)` | `core-quic`'s TLS parser — not HTTP/3 at all |
| the same run | QPACK failures always scoped to the stream, where RFC 9204 §3.1 requires a connection error for an invalid static-table index | `core-http3` |

Both presented as HTTP/3 faults. Neither was findable in-module. **Re-run these after changing any
wire parsing**: a green suite is not evidence of interoperability.

## Requirements

An HTTP/3-capable client. Debian's `curl` (7.88.1) is **not** — `curl --http3` errors out — so use
the Docker image below, whose curl is built against quiche.

> **Docker Desktop caveat.** Its daemon runs in its own VM, so `--network host` does *not* reach a
> WSL2 distro: run the server in a container too and attach the client with
> `--network container:<name>` so both share one loopback.

## Serving to a foreign client

```bash
mvn -q -pl core-http3 -am install -DskipTests
mvn -q -pl core-http3 dependency:build-classpath -Dmdep.outputFile=/tmp/cp.txt -Dmdep.includeScope=test
CP="$(cat /tmp/cp.txt):core-http3/target/classes:core-http3/target/test-classes"

java -cp "$CP" io.activej.http3.interop.Http3InteropServer          # -Dport=4433
```

Then, from a container sharing that network namespace:

```bash
# SC-001 — GET
curl --http3 -k -sS -w '\nVER=%{http_version} ST=%{http_code}\n' https://localhost:4433/

# SC-002 — echo, small and >1 MiB; the large case crosses the 256 kB stream and 1 MB connection windows
head -c 2097152 /dev/urandom > large.bin
curl --http3 -k -sS --data-binary @large.bin -o large.out https://localhost:4433/echo
cmp large.bin large.out && echo IDENTICAL
```

Image used: `ymuski/curl-http3` (curl 8.2.1-DEV, quiche 0.18, `Features: … HTTP3`).

### SC-003 — Chrome

```bash
SPKI=$(openssl x509 -pubkey -noout -in core-http3/src/test/resources/io/activej/http3/testutil/ecdsa-cert.pem \
  | openssl pkey -pubin -outform der | openssl dgst -sha256 -binary | openssl enc -base64)

google-chrome --headless=new --disable-gpu --no-sandbox \
  --enable-quic --origin-to-force-quic-on=localhost:4433 \
  --ignore-certificate-errors-spki-list="$SPKI" \
  --log-net-log=netlog.json --dump-dom https://localhost:4433/page
```

> **`--ignore-certificate-errors` is not honoured for QUIC.** BoringSSL still fails the chain and it
> surfaces as `ERR_QUIC_PROTOCOL_ERROR` — which reads like a protocol bug and is not one. The SPKI
> pin above is the option that works.

Confirm h3 was actually used, rather than a fallback, from Chrome's own netlog:

```bash
python3 -c "
import json; d=json.load(open('netlog.json'))
t={v:k for k,v in d['constants']['logEventTypes'].items()}
for e in d['events']:
    if t.get(e['type'],'').startswith(('QUIC_SESSION_VERSION','HTTP3_HEADERS','HTTP3_DATA')):
        print(t[e['type']], e.get('params'))"
```

Expect `QUIC_SESSION_VERSION_NEGOTIATED {"version": "RFCv1"}` and `HTTP3_HEADERS_DECODED` carrying
`:status: 200`.

## Requesting from a foreign server

```bash
java -cp "$CP" -Dtarget=https://cloudflare-quic.com -DresolveTo=$(getent ahostsv4 cloudflare-quic.com | head -1 | cut -d' ' -f1) \
  -DgetPath=/ -DpostPath=/ io.activej.http3.interop.Http3InteropClient
```

Against a local quic-go, Caddy is the least-effort reference server:

```
# Caddyfile
{ auto_https disable_redirects
  local_certs }
localhost:4434 {
	tls internal
	handle /hello { respond "hello-from-caddy" 200 }
	handle { reverse_proxy httpbin:80 }
}
```

```bash
docker network create h3net
docker run -d --name httpbin --network h3net kennethreitz/httpbin
docker run -d --name caddy --network h3net -v "$PWD/Caddyfile:/etc/caddy/Caddyfile:ro" caddy:latest
# then run Http3InteropClient with --network container:caddy against https://localhost:4434
```

Both programs print `status=` / `version=` lines; the client exits non-zero if either exchange
fails, so it can gate a script.
