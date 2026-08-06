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

## Automated suite (feature 007)

Since feature 007 the same module also carries a **skipping** regression suite next to the manual
harness: `Http3CurlInteropTest` starts a real `Http3Server` on a test-chosen loopback port and drives
a real foreign `curl` across seven cases, while `Http3RealSocketInteropTest` covers the client
direction over a real loopback socket with no external binary. Both are selected by the
`…InteropTest` naming rule:

```bash
mvn -pl core-http3 -am test -Dtest='*Interop*' -Dsurefire.failIfNoSpecifiedTests=false
```

The two `main` programs above are `Http3InteropServer` / `Http3InteropClient` — **not** `…Test` — so
Surefire's default include (`*Test.java`) never runs them, and the wildcard selects exactly the new
suite (FR-001a). New JUnit classes that are part of the suite must keep the `…InteropTest` suffix;
the harness's own unit tests (`CurlProbeTest`, `Http3ServerReactorFixtureTest`) deliberately do not
carry it and run under the default include instead.

### The two system properties

| Property | Default | Meaning |
|---|---|---|
| `activej.interop.curl` | `curl` on `PATH` | Path to the external HTTP/3 client. May be a **wrapper script** — that is how a `ymuski/curl-http3` container invocation is supplied without any container logic in this repository |
| `activej.interop.timeoutSeconds` | `30` | Per-invocation bound. Expiry destroys the process forcibly and fails the case with everything captured so far |

### Skip semantics — a skip is a result, a misconfiguration is a failure

`CurlProbe` probes the client **once per JVM** (`curl -V`, requiring `HTTP3` in the reported
features) and caches the outcome. Its three `Outcome`s map onto three behaviours:

| Outcome | When | Behaviour |
|---|---|---|
| `USABLE` | property unset and `PATH` curl reports `HTTP3`; or property set and the binary runs and reports `HTTP3` | the cases **run** |
| `ABSENT` | property unset and `curl` is missing, not executable, or has no `HTTP3`; or property set and the binary runs but reports no `HTTP3` (the reason names the missing feature) | **skip** — a JUnit assumption naming the probed path and the property; **no server is started**; exit 0 |
| `MISCONFIGURED` | property **set** and the path is missing or not executable | **fail** — an explicit configuration that does not work is a mistake, not an absence |

This sandbox's curl (7.88.1) has no `HTTP3`, so the suite skips here — `Tests run: 5, Failures: 0,
Errors: 0, Skipped: 1` with BUILD SUCCESS (the whole seven-case class skipped as **one** class-level
event — JUnit 4.13 `@BeforeClass` assumption counting — plus the four always-running
`Http3RealSocketInteropTest` cases) is the *specified* outcome on this machine (SC-002) — it is
**not** interop coverage, and must not be recorded as one. Point `-Dactivej.interop.curl` at an
HTTP/3-capable build (the `ymuski/curl-http3` wrapper from `quickstart.md` §0) to run the matrix.

### The seven cases (`Http3CurlInteropTest`)

All seven assert **HTTP version 3** on every response (`-w '%{http_version}'`) — a 200 over HTTP/1.1
or HTTP/2 is a failure, not a pass (FR-007) — and compare bodies by **length + SHA-256 digest** only,
never by byte or field value (FR-013):

| Case | What it exercises |
|---|---|
| `get` | `GET /` → 200, version 3, body byte-identical |
| `postSmallEcho` | 512-byte body to `/echo`, byte-identical round-trip |
| `postLargeEcho` | ≥ 2 MiB to `/echo` — across the 256 kB stream and 1 MB connection flow-control windows, under the 100 MB body cap |
| `multipleRequestsOneConnection` | several URLs via curl `--next`; every response 200/version 3, and `connectionsAccepted()` reads exactly **1** — read only after the process is reaped |
| `concurrentClientProcesses` | N curl processes at once; every response correct and unmixed, counters read only after **all** are reaped |
| `customHeaderFields` | custom request fields arrive intact and custom response fields come back intact; assertions name fields, never values |
| `gracefulServerClose` | after the exchanges, closing the server drains within `shutdownTimeoutMillis` and leaks nothing |

The servlet under test is `InteropTestServlet` — the same routes the manual profiles serve:
`GET /` (fixed body), `POST /echo` (request body streamed back), `GET /headers` (echoes the
`x-interop-` fields).

### How the suite relates to the manual `baseline`/`qpack`/`zerortt` profiles

Both doors drive the same real `Http3Server`/`Http3Client` components; they differ in who chooses the
port, the client and the configuration:

| | Automated suite | Manual harness |
|---|---|---|
| Server | test-owned `Http3Server` on a **test-chosen loopback port** (`:0` bound and kept — research D10) | `Http3InteropServer` on a fixed port (`-Dport=4433` default) |
| Client | `curl` through `CurlProbe` — a subprocess, no shell, bounded | anything a human can run: curl, Chrome, `Http3InteropClient` |
| Configuration | feature-006 defaults — the capabilities are **off** | `-Dprofile=baseline` / `qpack` / `zerortt` / `all` flips QPACK capacity and 0-RTT |
| Direction | server direction (foreign client → our server) **plus** the loopback client direction (`Http3RealSocketInteropTest`) | both directions, including **foreign servers** |
| When | every CI run that has an HTTP/3-capable curl — the regression net | when a feature-006 capability is the point, or the peer is not a curl subprocess |

The automated suite therefore automates exactly the coverage the `baseline` profile is for, and
covers it **unattended**. The `qpack` and `zerortt` profiles remain manual by design: the suite does
not opt into feature-006 capabilities (T038) and cannot drive Chrome or a foreign server. The two
harness halves share no process, no port and no JVM; a skipped suite and a manual run can sit side by
side.

`Http3RealSocketInteropTest` runs **unconditionally** — no external binary — so a fully-skipped green
run still exercises the client direction over a real socket and a real clock: GET, ≥ 2 MiB in each
direction byte-identical, several concurrent requests on one connection, and an abrupt server close
that fails the pending promise with the transport exception unwrapped.

## Serving to a foreign client

```bash
mvn -q -pl core-http3 -am install -DskipTests
mvn -q -pl core-http3 dependency:build-classpath -Dmdep.outputFile=/tmp/cp.txt -Dmdep.includeScope=test
CP="$(cat /tmp/cp.txt):core-http3/target/classes:core-http3/target/test-classes"

java -cp "$CP" io.activej.http3.interop.Http3InteropServer          # -Dport=4433
```

> **The `install` step is not optional, and skipping it fails obscurely.** `build-classpath` resolves
> `activej-quic` and `activej-http` from the **local repository**, so a `core-quic` change that has not
> been installed produces a `NoSuchMethodError` in the middle of an otherwise healthy connection —
> which reads exactly like a wire bug and is not one. The same `-am` trap `core-http3/CLAUDE.md`
> documents for single-module test runs.

### Profiles

The three capabilities of feature 006 ship off by default, so the harness keeps phase 1 reachable
unchanged and puts each departure behind `-Dprofile=`:

| `-Dprofile=` | QPACK capacity | 0-RTT | For |
|---|---|---|---|
| `baseline` (default) | 0 | off | feature 005 SC-001…SC-003 — byte-for-byte the phase-1 server every earlier result was taken against |
| `qpack` | 4096 | off | feature 006 SC-003 — dynamic-table use with a foreign encoder/decoder |
| `zerortt` | 0 | on | feature 006 SC-006 — session resumption and early data |
| `all` | 4096 | on | both at once, which is what a browser drives |

`-DqpackCapacity=`, `-DqpackBlockedStreams=` and `-DzeroRtt=` override whatever the profile set; the
`READY` line reports what was actually applied:

```
READY port=4433 profile=qpack qpackCapacity=4096 qpackBlockedStreams=16 zeroRtt=false
```

### What the server prints, and how to read it

A foreign client tells you almost nothing about what the two QPACK tables and the ticket path did, so
the server says so itself through its `Inspector`, one greppable line per event (`-Dverbose=false`
turns it off):

| Prefix | Meaning |
|---|---|
| `REQ` / `RESP` | a request head decoded / a response fully written, with running totals |
| `QPACK insert table=ENCODER` | **this server's** encoder inserted — which can only happen if the peer advertised a non-zero `SETTINGS_QPACK_MAX_TABLE_CAPACITY` |
| `QPACK insert table=DECODER` | **the peer's** encoder inserted into the table we decode with — the proof that the foreign client is using its dynamic table |
| `QPACK encoded stream=N fields=F dynamicRefs=D` | per response field section: `D` of `F` field lines went out as a dynamic reference instead of a literal |
| `QPACK blocked` / `unblocked` / `refused` | head-of-line blocking transitions, with `exit=DECODED` the only ordinary one |
| `SESSION tickets=` / `SESSION resumed` | tickets issued, and a handshake that resumed from one |
| `ZERORTT refused reason=` | early data refused, by which of the four defences |
| `H3ERR` | a stream reset, a connection error, a GOAWAY, a discarded frame |

**Absence is a result too.** With `-Dprofile=qpack`, no `QPACK` line at all means the peer advertised
a capacity of 0 and the negotiated capacity is `min(4096, 0) = 0` — so both sides fell back to the
static table. That is a correct and conformant outcome (and the one to expect from a quiche-based
curl, whose QPACK encoder has historically advertised 0), but it is a *negotiation* result, not
evidence for SC-003. Chrome is the peer that actually drives a dynamic table; see below.

### Feature 005 — SC-001 and SC-002 (profile `baseline`)

From a container sharing that network namespace:

```bash
# SC-001 — GET
curl --http3 -k -sS -w '\nVER=%{http_version} ST=%{http_code}\n' https://localhost:4433/

# SC-002 — echo, small and >1 MiB; the large case crosses the 256 kB stream and 1 MB connection windows
head -c 2097152 /dev/urandom > large.bin
curl --http3 -k -sS --data-binary @large.bin -o large.out https://localhost:4433/echo
cmp large.bin large.out && echo IDENTICAL
```

Image used: `ymuski/curl-http3` (curl 8.2.1-DEV, quiche 0.18, `Features: … HTTP3`).

### Feature 005 SC-003 — Chrome page load

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

**Recorded run — 2026-08-06, feature 007 E2E-15 (SC-005), against `Http3HelloWorld`**
(`examples/core/http3`, the `Http3ServerLauncher` subclass serving the dev leaf at `localhost:4433`):

- Chrome 145.0.7632.45 (headless) on this sandbox; SPKI pinned from
  `examples/core/http3/src/main/resources/dev-cert.pem`.
- One extra flag beyond the block above: `--host-resolver-rules="MAP localhost 127.0.0.1"` —
  headless Chrome resolved `localhost` to `::1` first, the server binds IPv4 loopback, and the
  QUIC packets to `[::1]:4433` went nowhere (`ERR_QUIC_HANDSHAKE_FAILED` in the error page).
  With the map, the page loads and the netlog shows:
  `QUIC_SESSION_VERSION_NEGOTIATED {"version": "RFCv1"}`, `HTTP3_HEADERS_DECODED` with
  `:status: 200` (`content-type: text/plain; charset=utf-8`, `content-length: 18`), and
  `HTTP3_DATA_FRAME_RECEIVED` `{"payload_length": 18}` — plus a second exchange on stream 4
  (favicon) with the same decode, i.e. two requests on one RFCv1 connection.
- `--ignore-certificate-errors` alone was **not** used — SPKI pinning only, per the warning above.

### Feature 006 SC-003 — two sequential requests on one connection (profile `qpack`)

Start the capacity-bearing server:

```bash
java -cp "$CP" -Dprofile=qpack -Dport=4433 io.activej.http3.interop.Http3InteropServer
```

`--next` is what keeps both requests on **one** connection, which is the whole point: a dynamic-table
reference on the second request is only possible because the first one's insertions are still there.

```bash
curl --http3 -k -sS -w '\nVER=%{http_version} ST=%{http_code}\n' \
  https://localhost:4433/qpack --next https://localhost:4433/qpack
```

`/qpack` is the route to use rather than `/`: its response carries several repeated, indexable header
fields, so there is something for an encoder to insert and reference. Both requests must return
`VER=3 ST=200`.

**Confirming the dynamic table was actually used** — three independent ways, in decreasing order of
directness:

1. *This server's own counters*, printed as they happen. The pattern to look for is a first response
   that inserts, and a second that references without inserting again — this is a real transcript of
   two `GET /qpack` on one connection, taken loopback against this module's own client, so it is what
   the shape looks like rather than an idealisation:

   ```
   REQ  stream=0 method=GET
   QPACK insert table=ENCODER n=5 tableBytes=323
   QPACK encoded stream=0 fields=6 dynamicRefs=5
   RESP stream=0 status=200 reqBody=0 respBody=62 served=0
   QPACK insert table=DECODER n=2 tableBytes=100
   REQ  stream=4 method=GET
   QPACK encoded stream=4 fields=6 dynamicRefs=5        ← no insert line: the five were already there
   RESP stream=4 status=200 reqBody=0 respBody=62 served=1
   ```

   `dynamicRefs > 0` on the second `QPACK encoded` line, with **no** `insert table=ENCODER` line before
   it, **is** SC-003 in the response direction. A `QPACK insert table=DECODER` line is the same claim in
   the request direction: the peer's encoder inserting into the table this server decodes with. (The
   sixth field line stays a literal in the transcript above — a `dynamicRefs` equal to `fields` is not
   the bar; a non-zero one that costs no new insertion is.)
2. *The peer's qlog.* quiche writes one per connection when `QLOGDIR` is set, so
   `docker run -e QLOGDIR=/qlog -v "$PWD/qlog:/qlog" …` leaves a JSON trace whose
   `qpack:instruction_created` / `qpack:instruction_parsed` events name the insertions directly. Only
   useful if the image's curl was built with the `qlog` feature; check for files appearing.
3. *Byte count.* `-w '%{size_header}'` on both requests of the `--next` pair. It is the weakest of the
   three — it measures the request head, not the field section, and includes framing — but a second
   request materially smaller than the first is corroboration when no qlog is available.

If none of the three shows anything and both requests still return 200, record the run as *"peer
negotiated capacity 0; graceful fallback to the static table verified, SC-003 not exercised"* rather
than as a pass. See the "absence is a result too" note above.

### Feature 006 SC-006 — 0-RTT on the second connection (profile `zerortt`)

```bash
java -cp "$CP" -Dprofile=zerortt -Dport=4433 io.activej.http3.interop.Http3InteropServer
```

```bash
curl --http3 -k -v https://localhost:4433/ --next https://localhost:4433/
```

> **`--next` alone does not prove resumption**, and this is the easiest thing to get wrong in the
> whole file: curl reuses the connection across a `--next`, so the second request may well ride the
> *first* handshake and never offer a ticket. Two separate `curl` processes sharing a session file is
> the unambiguous form —
> `curl --http3 -k -v --tls-session-file /tmp/h3.sess https://localhost:4433/` run twice — where
> supported by the build. Run the `--next` form first because it is what `tasks.md` names, then
> confirm with the two-process form.

What confirms it, in order of authority:

1. **This server's lines.** `SESSION tickets=N issued=N` on the first connection, then on the second:

   ```
   SESSION resumed earlyDataAccepted=true resumed=1 zeroRttAccepted=1
   ```

   `resumed=1` is resumption; `earlyDataAccepted=true` with `zeroRttAccepted=1` is 0-RTT specifically.
   Resumption **without** early data prints `earlyDataAccepted=false` and is a perfectly good result —
   it just is not SC-006.
2. **curl's `-v` output.** `* Using HTTP/3` on both, and on the second a TLS line naming an abbreviated
   or resumed handshake (`SSL reusing session` / `TLS session resumed`, wording varies by build). curl
   does not say "0-RTT" in so many words; the absence of a fresh certificate exchange is the signal.
3. **A `425` is not a failure.** The default early-data policy runs RFC 9110 §9.2.1 safe methods only,
   so a `POST` sent in early data is answered `425 (Too Early)` without the servlet running, and the
   server prints `ZERORTT refused reason=POLICY`. That is the specified behaviour (FR-064), and the
   reason the curl case above uses `GET`.

Both requests must succeed with `ST=200` whichever way the handshake went — a rejected ticket is a
full handshake, never a failure (RFC 8446 §4.2.10).

### Feature 006 — Chrome against the capacity-bearing profile

Chrome is the peer that actually implements a QPACK **encoder** with a dynamic table, which is what
makes it the useful half of SC-003 rather than curl:

```bash
java -cp "$CP" -Dprofile=qpack -Dport=4433 io.activej.http3.interop.Http3InteropServer
```

```bash
google-chrome --headless=new --disable-gpu --no-sandbox \
  --enable-quic --origin-to-force-quic-on=localhost:4433 \
  --ignore-certificate-errors-spki-list="$SPKI" \
  --log-net-log=netlog.json --dump-dom https://localhost:4433/page
```

The page load must complete with no `QPACK_*` error and no `ERR_QUIC_PROTOCOL_ERROR`. Chrome
advertises a substantial table capacity and a blocked-stream permission of its own (historically
64 KB and 100, but **read them off the run rather than trusting that** — they have changed between
milestones), so this is the case where this implementation's encoder is actually asked to use a
dynamic table and its decoder is actually asked to hold blocked sections.

Netlog event names differ between Chrome versions, so grep the constant table the netlog **carries**
rather than a hard-coded list:

```bash
python3 -c "
import json; d=json.load(open('netlog.json'))
types=d['constants']['logEventTypes']
print('--- event types this build has, matching QPACK/HTTP3/SETTINGS ---')
for name in sorted(types):
    if 'QPACK' in name or 'HTTP3' in name or 'SETTINGS' in name: print(' ', name)"
```

Then dump the ones that matter:

```bash
python3 -c "
import json; d=json.load(open('netlog.json'))
t={v:k for k,v in d['constants']['logEventTypes'].items()}
want=('QUIC_SESSION_VERSION','HTTP3_SETTINGS','HTTP3_HEADERS','HTTP3_DATA','QPACK')
for e in d['events']:
    n=t.get(e['type'],'')
    if any(k in n for k in want): print(n, e.get('params'))"
```

What to look for:

- `HTTP3_SETTINGS_RECEIVED` — the SETTINGS **this server** sent. Its QPACK capacity pair should carry
  4096, which is the direct confirmation that the capacity-bearing profile is the one under test.
- `HTTP3_SETTINGS_SENT` — Chrome's own capacity and blocked-stream advertisement; note the values in
  the run record instead of relying on the numbers above.
- `HTTP3_HEADERS_DECODED` carrying `:status: 200`, and `HTTP3_DATA_FRAME_RECEIVED` — the page really
  came over h3.
- Anything with `QPACK` in the name that reports an *error* — none should appear. A Chrome build
  logging QPACK stream events by name will also show the encoder/decoder streams being created; their
  presence is welcome corroboration, their absence only says this build does not log them.
- Cross-check against this server's own `QPACK insert table=DECODER` lines: Chrome's encoder inserting
  into the table we decode with is the half a netlog cannot show from the outside.

## Requesting from a foreign server

> **This manual procedure remains the route for a foreign *server*.** The automated suite above
> covers the server direction (a foreign client against our server) and the loopback client direction
> (`Http3RealSocketInteropTest`, our client against our server) — it never dials a third party's
> endpoint, because that would make the suite depend on someone else's network and uptime. Testing
> `Http3Client` against a **foreign server** (Cloudflare, Caddy/quic-go, …) is what
> `Http3InteropClient` is for, and it stays hand-driven (feature 007, FR-016).
>
> The command lines below were re-verified for feature 007: the two `main` programs have not moved
> (`io.activej.http3.interop` in `core-http3/src/test`), feature 007 added **no** dependency to this
> module (the automated suite lives in the same test tree), so the classpath produced by
> `dependency:build-classpath` is unchanged and every `java -cp "$CP" …` invocation below works as
> written.

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

Both programs print `status=` / `version=` lines; the client exits non-zero if any exchange fails, so
it can gate a script.

### Feature 006 — resuming against a third-party server that offers tickets

```bash
java -cp "$CP" -DzeroRtt=true -Drounds=2 \
  -Dtarget=https://localhost:4434 -DgetPath=/hello -DpostPath=/hello \
  io.activej.http3.interop.Http3InteropClient
```

Caddy (quic-go) issues `NewSessionTicket` by default, which is what makes it the reference server for
this case as well; Cloudflare works too, with `-Dinsecure=false`.

**Two things in the client make this case possible**, and both are easy to lose in a refactor:

- **Each round is a fresh `Http3Client` over one shared `QuicSessionCache`.** A second request through
  one client reuses the pooled connection — correct behaviour, wrong experiment, since resumption is a
  property of a *new* handshake. The shared cache is the FR-059 seam that lets a ticket outlive the
  client that stored it.
- **The insecure path goes through `withTlsClientConfig`, not `withTlsEngineFactory`.** A whole engine
  factory owns its own `TlsClientConfig` and therefore **opts out of the resumption plumbing** — no
  ticket offered, none stored, `withSessionCache` inert. The harness used to take the factory route;
  it would have reported "no resumption" against a server that was offering tickets perfectly well.

What confirms it, per round, on the `SESSION` line the client prints:

```
SESSION round=1 ticketsStored=2 ticketsOffered=0 zeroRttAttempted=0 zeroRttAccepted=0 zeroRttRejected=0 earlyDataRetried=0
SESSION round=2 ticketsStored=2 ticketsOffered=1 zeroRttAttempted=1 zeroRttAccepted=1 zeroRttRejected=0 earlyDataRetried=0
```

- `ticketsStored > 0` on round 1 — the peer issued a ticket this client could open and keep.
- `ticketsOffered = 1` on round 2 — it was offered on the new handshake.
- `zeroRttAccepted = 1` — the peer took the early data. `zeroRttRejected = 1` instead is **also a
  pass** for correctness: the request is retried transparently at 1-RTT (FR-067,
  `earlyDataRetried` counts it) and the response must still be correct. Only a wrong or missing
  response is a failure.
- The response body on round 2 must be identical to round 1's — printed above the `SESSION` line, so
  the check is a `diff` of the two `=== GET round=N ===` blocks.

`-DqpackCapacity=4096` may be added to exercise Slice A against the same peer in the same run.

## Recorded findings

### 2026-08-06 (T058) — the seven-case matrix ran against real curl 8.2.1-DEV / quiche 0.18: **6/7 green, case 4 red — client-side**

The matrix was executed for the first time against a real foreign HTTP/3 client, with the result
recorded here rather than assumed. Client: `ymuski/curl-http3` (curl 8.2.1-DEV, quiche 0.18) — the
image this harness documents — extracted from the container and run on the host netns (see below).
Command, exactly the quickstart form:

```bash
mvn -pl core-http3 -am test -Dtest='Http3CurlInteropTest' -Dsurefire.failIfNoSpecifiedTests=false \
    -Dactivej.interop.curl=/tmp/curl-h3-host
```

Result: **`Tests run: 7, Failures: 1, Errors: 0, Skipped: 0`** — exit 1.

- **Passing (6/7)** — `get`, `postSmallEcho`, `postLargeEcho` (**2 MiB round-trip byte-identical by
  SHA-256 — SC-003 exercised and green**), `concurrentClientProcesses`, `customHeaderFields`,
  `gracefulServerClose`. Every passing case asserted `VER=3` (FR-007).
- **Failing — `multipleRequestsOneConnection`**, with this evidence that the defect is in the curl
  build's `--next` path, not in the server:
  - Transfer 1 serves `200`/`VER=3`; transfers 2–3 of the same curl process fail instantly
    (`curl: (7) Failed to connect to 127.0.0.1 port N after 0 ms`) and print no write-out line.
  - The server's qlog shows exactly **one** connection (`NEW → SETTINGS_SENT → READY → DRAINING →
    CLOSED`) and **no packet from transfers 2–3 ever arrived** — the client closed its own healthy
    connection after transfer 1 (`Peer closed the connection: NO_ERROR`) and then failed to open the
    next one.
  - The listening socket stays bound and **fresh single-URL connections succeed before, during and
    after** the failing run (verified against the same server, and against the running launcher).
  - `-v` shows the same build sometimes dialing `::1` first (refused — the server binds IPv4
    loopback), falling back to `127.0.0.1` and succeeding, i.e. the failure is timing/state-dependent
    inside this 2023 dev build, not a deterministic protocol behaviour.
  - A successful `--next` run against `Http3ServerLauncher` (via the example) confirmed one
    connection serves multiple transfers when the client behaves.
- **Docker Desktop netns caveat, demonstrated**: this sandbox's Docker is Docker Desktop; its daemon
  runs in its own VM, so the §0 wrapper's `--network host` **cannot** reach the host loopback —
  the full suite fails 7/7 with `QUIC: connection … refused` when driven through the container
  wrapper, while the identical binary extracted to the host netns passes 6/7. The wrapper remains the
  documented route on machines whose docker shares the host netns (Linux docker).

**Status**: SC-001 is *partially demonstrated* — six of seven cases green against a real foreign
client, version-3 asserted; SC-003's 2 MiB case is green. Case 4's one-connection assertion must be
re-run against a newer curl build (this one is from 2023) before the matrix can be recorded 7/7.
Until then, record the matrix as **6/7 with a documented client-side residual**, never as fully
passing.

**QA re-run (feature 007 E2E-07, 2026-08-06, same client)**: the matrix was executed again during
the end-to-end validation pass, with the identical outcome — `Tests run: 7, Failures: 1` where the
single failure is `multipleRequestsOneConnection` with the same signature as recorded above
(transfer 1 served and byte-identical, transfers 2–3 never reach the server, `curl: (7) Failed to
connect … after 0 ms`, exit 7, one write-out line parsed instead of three). The run confirms the
residual is reproducible and remains client-side.

### Open finding — `Http3Server` exposes no bound-address accessor (feature 007, research D11)

`AbstractReactiveServer` gives `HttpServer` `getListenAddresses()` / `getBoundAddresses()`, which
`HttpServerLauncherTest.bindZeroPort` relies on. `Http3Server` has no equivalent — its public no-arg
surface is `listen`, `close`, `isClosed` and the counters — so the launcher logs the address it read
from `Config`, and a test that needs a port passes one explicitly rather than binding `:0` and asking
the server what it got.

This is FR-034's "report the gap": **a future JMX feature that wants to publish the bound address,
and a `bindZeroPort`-shaped launcher test, will both want `Http3Server.getBoundAddress()`.** It is
recorded as an open question in `core-http3/CLAUDE.md` as well, since that is where the next feature
will look.

### 2026-08-05 (T144) — the phase-1 `Http3Client` ↔ quic-go gap is **closed**, and Slice A does not touch it

The symptom on record — "handshake and SETTINGS succeed, the request never reaches the peer's
handler" — was never a QPACK or a SETTINGS problem. It was `core-quic`'s TLS parser rejecting a legal
empty `ticket_nonce` in the **post-handshake** `NewSessionTicket`, which raised `CRYPTO_ERROR(50)` on
a connection that was already established and serving, moments after the first request went out.
Caddy therefore logged one handshake and zero handled requests, which is exactly what an HTTP/3 fault
looks like from the outside.

Evidence, all verifiable in-tree rather than from a run:

- `TlsMessages.readNewSessionTicket` now reads `ticket_nonce` with **no** lower bound and checks only
  that the declared length fits the remaining bytes; `NewSessionTicketMessage`'s constructor bounds it
  at `0..255`. The `ticket<1..2^16-1>` minimum beside it — the field the `1` was copied from — is
  unchanged.
- Regression tests: `TlsAlertTest.zeroLengthTicketNonceIsAccepted` (inverted from an assertion that
  encoded the misreading), `TlsMessagesTest` round-trips, and — added later, by Slice B —
  `NewSessionTicketIssuanceTest`, which asserts an empty nonce parses *and* derives the resumption PSK
  from it. The fix therefore survived the slice that rewrote ticket handling around it.
- Commit `e183d3ae2` records a live re-run: `Http3Client` GET/POST `200` against Caddy 2 (quic-go) on
  a static and a proxied route, with Cloudflare re-checked for regression.

**Slice A (QPACK dynamic table, `6fb663e10`) does not interact with it**, for two reasons that are
independent of each other:

1. *Different layer, different phase.* The defect was in TLS post-handshake message parsing. QPACK
   lives above the HTTP/3 control stream, and the connection was reaching `READY` — SETTINGS
   exchanged — before it died.
2. *Off by default, and provably inert when off.* `Http3Settings.qpackMaxTableCapacity` defaults to 0;
   at 0 `Http3Connection.localSettingsFrame()` advertises `0` for both QPACK identifiers, no local
   QPACK encoder or decoder stream is opened at all, and the static codecs are used — byte-for-byte
   what phase 1 sent (SC-011, asserted by `Http3DefaultsUnchangedTest`). Nothing on the path to a
   peer's handler changes.

What Slice A *does* change, when opted in, is worth stating so it is not mistaken for the above:
enabling a capacity adds two QPACK identifiers with non-zero values to SETTINGS and opens two more
unidirectional streams before the first request. That is new surface toward a foreign peer, and it is
what the SC-003 case above exists to exercise — but it is new surface, not reopened surface.

**One real cross-slice interaction, observed here rather than reasoned about** (profile `all`, an
ActiveJ↔ActiveJ smoke run of this harness): a request that arrives in **0-RTT early data** is answered
with the **static** encoder, because `Http3Connection` builds its dynamic encoder in
`onPeerSettingsApplied` and the peer's SETTINGS have not been processed that early. It shows up as a
`RESP` line with no `QPACK encoded` line before it. This is conformant — RFC 9204 requires an encoder
to reference nothing it has not inserted, and a 0-RTT response referencing a table the peer has not
sized is precisely what must not happen — and it costs the first early-data exchange its compression,
nothing more. It is Slice A × Slice B, not Slice A × quic-go.
