# Interop: the frozen vectors and the live curl tier (US4 — FR-060…FR-065)

This package holds the two tiers that keep the feature's headline claim — *a non-JVM caller reaches
a Java method* — true on every build:

| Tier | What it does | Network? | External binary? | Runs when |
|---|---|---|---|---|
| `InteropVectorsTest` | replays the frozen vectors in-process through `StubHttpClient` (FR-062, FR-056) | **no** | **no** | always, under `-P extra` |
| `JsonRpcCurlInteropTest` | replays the four `curl`-origin vectors with a **real** curl against a **real** server on `:0` | yes (loopback) | curl, capability-probed (ADR-027) | when a usable curl exists; **skips** with a stated reason otherwise; **fails** when the `activej.jsonrpc.interop.curl` property names a client that cannot work (FR-063b) |

The frozen vectors are the wire contract's regression gate (FR-065): a change to the response
bytes of any vector fails the build, so a deliberate wire change is a deliberate vector change, and
an accidental one is a red build.

---

## The files

| File | Purpose |
|---|---|
| `http-vectors.json` | the frozen exchanges, in the format of `data-model.md` §3 — see [below](#the-vector-format) |
| `InteropVectors.java` | the loader of that format (mirrors feature 010's `ConformanceVectors`) |
| `InteropVectorsTest.java` | the no-network replay (FR-062) + the required-coverage check (FR-061) |
| `CurlProbe.java` | discovery (property → `PATH`, capability from curl's own feature list) and bounded invocation (timeout, force-kill, capture, length + SHA-256) — FR-063a/c, ADR-027; local, no `core-http3` dependency |
| `JsonRpcCurlInteropTest.java` | the live tier (FR-063, FR-063b) |
| `CaptureServer.java` | the regeneration vehicle — a `main`, **not** a test (Surefire never runs it) |
| `README.md` | this file — the capture procedure (FR-064) |

## The two origins

FR-061 requires the frozen set to include exchanges captured from a **real `curl` invocation** and
from a **standard JavaScript `fetch()` call**. Both origins are required because they are the two
non-JVM callers the feature exists for, and they differ: curl is a command-line tool, `fetch()` is
what a browser or a Node program sends. Each origin currently contributes the same four shapes:

1. a **single request** — `POST`, one JSON-RPC request document, answered `200` with
   `Content-Type: application/json` and the response document;
2. a **notification** — `POST`, one JSON-RPC notification (no `id`), answered `204` with no body
   and no `Content-Type`;
3. a **batch** — `POST`, a JSON-RPC batch array, answered `200` with the responses array;
4. a **rejection path** — plain `GET`, answered `405` with `Allow: POST` and no body (the method
   gate of `contracts/http-semantics.md` §2 row 1).

## Regenerating the vectors

### 1. Start the capture server

`CaptureServer` serves the **real** `JsonRpcServlet` over the module's `TestApi` service on a real
`HttpServer` bound to port `0` — the port is printed, never guessed (FR-050a, ADR-028). The
service's wire methods are `test.add`, `test.notify`, `test.failDeliberately`, `test.failWithData`
and `test.failAccidentally` (see `fixtures/TestApi.java`).

```bash
# compile the module and its test tree once
mvn -P extra -pl extra/cloud-jsonrpc-http -am test-compile -DskipTests

# build the test classpath
mvn -P extra -pl extra/cloud-jsonrpc-http dependency:build-classpath \
    -Dmdep.outputFile=/tmp/jsonrpc-http-cp.txt -Dmdep.includeScope=test -q

# serve until killed; prints: CAPTURE_SERVER <host>:<port>
java -cp "extra/cloud-jsonrpc-http/target/test-classes:extra/cloud-jsonrpc-http/target/classes:$(cat /tmp/jsonrpc-http-cp.txt)" \
    io.activej.jsonrpc.transport.http.interop.CaptureServer
```

Note the printed port (`P` below). The server answers until the process is killed.

### 2. Capture with curl

The four verbatim commands (with `-i` so the full response — status line, headers, body — is
visible). Substitute the port from step 1:

```bash
P=39209  # the port CaptureServer printed

# 1. single request
curl -sS -i -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":1,"method":"test.add","params":{"a":2,"b":3}}' \
  "http://127.0.0.1:$P/"

# 2. notification
curl -sS -i -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","method":"test.notify","params":{"message":"hello from curl"}}' \
  "http://127.0.0.1:$P/"

# 3. batch
curl -sS -i -H 'Content-Type: application/json' \
  -d '[{"jsonrpc":"2.0","id":101,"method":"test.add","params":{"a":2,"b":3}},{"jsonrpc":"2.0","id":102,"method":"test.add","params":{"a":4,"b":5}}]' \
  "http://127.0.0.1:$P/"

# 4. rejection path (GET is not supported — FR-096)
curl -sS -i "http://127.0.0.1:$P/"
```

The response bytes that go into the vectors are exactly what these commands print after the blank
line — the body is the servlet's dispatcher output, verbatim (FR-013).

### 3. Capture with fetch()

The standard `fetch()` of Node ≥ 18 (and of every current browser) against the same server:

```js
const base = process.argv[2];          // e.g. http://127.0.0.1:39209

async function show(name, init) {
  const res = await fetch(base + '/', init);
  console.log(name, res.status, res.headers.get('content-type'), res.headers.get('allow'),
              JSON.stringify(await res.text()));
}

// 1. single request
await show('single', { method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({ jsonrpc: '2.0', id: 1, method: 'test.add', params: { a: 2, b: 3 } }) });
// 2. notification
await show('notify', { method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({ jsonrpc: '2.0', method: 'test.notify', params: { message: 'hello from fetch' } }) });
// 3. batch
await show('batch', { method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify([{ jsonrpc: '2.0', id: 101, method: 'test.add', params: { a: 2, b: 3 } },
                        { jsonrpc: '2.0', id: 102, method: 'test.add', params: { a: 4, b: 5 } }]) });
// 4. rejection path
await show('reject', { method: 'GET' });
```

```bash
# save the snippet above as capture-fetch.mjs, then:
node capture-fetch.mjs "http://127.0.0.1:$P/"
```

### 4. Freeze

Edit `src/test/resources/io/activej/jsonrpc/transport/http/interop/http-vectors.json`: one object
per exchange, in the format below, with `origin: "curl"` for the curl captures and
`origin: "fetch"` for the fetch captures. Then run the suite:

```bash
mvn -P extra -pl extra/cloud-jsonrpc-http -am test -Dtest=InteropVectorsTest,JsonRpcCurlInteropTest \
    -Dsurefire.failIfNoSpecifiedTests=false
```

### The vector format

Exactly `data-model.md` §3 — one JSON array, one object per exchange:

| Field | Type | Meaning |
|---|---|---|
| `name` | string | unique; used in assertion messages and to name a failure |
| `origin` | string | `curl` or `fetch` — which real client captured this exchange |
| `request.method` | string | `POST`, `GET`, … |
| `request.headers` | object | header name → value, **as sent** — the stable ones only |
| `request.body` | string \| null | the body, verbatim; `null` for a bodiless request |
| `expect.status` | number | the expected HTTP status |
| `expect.headers` | object | **only the headers this vector asserts** — an allow-list, not the full set |
| `expect.bodyAbsent` | boolean | `true` for the bodiless responses (`204`, `405`); when `true`, `expect.body` must be absent |
| `expect.body` | string | expected body; compared by feature 010's `ConformanceJson` JSON-value rules, never by string equality |

Two recording rules worth knowing:

- **`request.headers` records only the stable request headers** (`Content-Type`, `User-Agent`).
  `Host` embeds the ephemeral port, and `Content-Length` is computed by the client's HTTP stack —
  both vary per run and are deliberately not frozen. The replay sets exactly the recorded headers;
  the servlet only reads `Content-Type` (FR-016).
- **`expect.headers` is an allow-list.** The full response carries connection-tier headers
  (`Connection`, a rendered `Content-Length`…) that are core-http's business, not the wire
  contract's. The vector asserts what the servlet itself puts on the wire: `Content-Type:
  application/json` on the `200` shapes, `Allow: POST` on the `405`. The `204` asserts no header at
  all. (`contracts/http-semantics.md` §5: "adding a response header — case by case; the frozen
  vectors assert an allow-list of headers, not the full set".)

### Telling a deliberate wire change from an accidental one

The vectors are the wire contract's only regression gate (FR-065). When a build fails on a vector:

1. **Regenerate** the exchange with the exact commands above (or with a fresh capture against the
   current code) — the mismatch must reproduce with a real client.
2. **Read the difference in the semantics table** (`contracts/http-semantics.md` §2): does the new
   status, header or body correspond to a row you changed? If yes — a deliberate wire change — the
   vector is *supposed* to change: update `http-vectors.json`, state the change in the module's
   changelog/commit message, and re-run the suite. If no — the change is **accidental**: the wire
   drifted from the contract, and the failing vector is the alarm, not the thing to silence.

A deliberate change to the wire contract is also a breaking change for every non-JVM caller with no
compile error anywhere (§2.3 of the data model); the vector diff is the reviewable record of it.

## The live tier: discovery and configuration

`JsonRpcCurlInteropTest` follows ADR-027's four rules, re-stated locally in `CurlProbe` (no
dependency on `core-http3`'s test artifacts):

| Situation | Outcome |
|---|---|
| property unset, usable `curl` on `PATH` | run — 4 cases against a real server on `:0` |
| property unset, no usable `curl` on `PATH` | **skip** with a stated reason; no server started |
| property **set**, path missing / not executable / lacking the `http` protocol in its own `--version` output | **fail** — an explicit configuration that does not work is a mistake (FR-063b) |

| Property | Default | Meaning |
|---|---|---|
| `activej.jsonrpc.interop.curl` | `curl` on `PATH` | Path to a curl. May be a wrapper script. Capability is read from the binary's **own reported feature list** (`Protocols:` line of `curl --version`), never inferred from its presence (FR-063a) |
| `activej.jsonrpc.interop.timeoutSeconds` | `30` | Per-invocation bound; expiry destroys the process forcibly and fails the case with everything captured so far (FR-063c) |

Every live invocation is an argument vector — **no shell** — with the response body reduced to
length + SHA-256 digest before it can reach any assertion message, and the response head dumped
(`-D -`) so the asserted headers are checked exactly. The live tier asserts each response matches
its frozen vector: status, body bytes (by digest), asserted headers, and HTTP version `1.1`.

Run the live tier explicitly:

```bash
mvn -P extra -pl extra/cloud-jsonrpc-http -am test -Dtest=JsonRpcCurlInteropTest \
    -Dsurefire.failIfNoSpecifiedTests=false
```

Simulate a curl-less machine (the frozen tier must stay green — it is network-free and curl-free by
construction): run the suite with a `PATH` that contains `java`/`mvn` but not `curl`, e.g.
`PATH="/tmp/nocurl-bin:<jdk>/bin:<maven>/bin"` with `curl` absent from every entry.

## The frozen tier: no network, no binary

`InteropVectorsTest` never probes `PATH` and never opens a socket: every vector is replayed by
driving the real `JsonRpcServlet` in-process through `StubHttpClient.of(servlet)` (FR-062) — the
only place `StubHttpClient` is allowed in this module (FR-056). It also checks the required
coverage structurally (FR-061): the four shapes from each origin. It runs on every build under
`-P extra`, curl or no curl.
