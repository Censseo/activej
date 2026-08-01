# QPACK test fixtures

Binary field-section fixtures decoded by `QpackFixturesTest`. Every fixture below is loaded from
this directory at test time via the classpath, so a stale/corrupt fixture fails a real test rather
than sitting unreferenced.

| Fixture | Bytes | Provenance |
|---|---|---|
| `rfc9204-appendix-b1-static-name-reference.bin` | `00 00 51 0b 2f 69 6e 64 65 78 2e 68 74 6d 6c` (15) | RFC 9204 Appendix B.1, "Literal Field Line with Name Reference" example (`:path: /index.html`). Copied byte-for-byte from the RFC text; verified against the RFC 2026-08-01. See `QpackRfcVectorsTest` for the decode + (round-trip, not byte-identical — see that test's Javadoc) re-encode assertions. |
| `synthetic-get-request.bin` | `00 00 d1 d7 c1 50 88 2f 91 d3 5d 05 5c 87 a7 5f 50 88 25 b6 50 c3 cb be b8 3f` (26) | **Synthetic**, not a real capture — see "On curl/Chrome captures" below. A realistic GET request field section (`:method: GET`, `:scheme: https`, `:path: /`, `:authority: example.com`, `user-agent: curl/8.9.0`) built by hand from common static-table entries and encoded with this module's own `QpackStaticEncoder`, so it is a self-consistent, spec-conformant fixture even though it was not captured from a live peer. |
| `synthetic-200-response.bin` | `00 00 d9 f4 54 83 08 9b 73 5f 4d 84 aa 63 55 e7` (16) | **Synthetic**, likewise. A realistic `200 OK` response field section (`:status: 200`, `content-type: text/html; charset=utf-8`, `content-length: 1256`, `server: nginx`), same construction as above. |

## On curl/Chrome captures

The task list calls for "one curl-generated and one Chrome-generated captured field section." Both
require a live HTTP/3 exchange — a real `curl --http3` request against a real server, or a real
Chrome navigation, captured on the wire (e.g. via `qlog` or a packet capture) — which this sandboxed
environment cannot produce: there is no outbound network access and no Chrome binary available here.

Real captures are **deferred to the manual interop step, task T100** (`quickstart.md`'s
`curl --http3 -k -v https://localhost:4433/` checks, run once a real `Http3Server` exists in a later
phase). The two `synthetic-*.bin` fixtures above stand in for them now: they exercise the same
representations (`Indexed Field Line`, `Literal Field Line with Name Reference`, and their Huffman
variants) a real curl or Chrome request/response would use, just not literally captured from one.
When T100 produces real captures, they should be added here as `curl-*.bin` / `chrome-*.bin` with
the exact command/build that produced them recorded in this table, and this note removed.

## Regenerating the synthetic fixtures

Both were produced by encoding the field lists above with `QpackStaticEncoder` (this module) via a
throwaway `jshell` session against the compiled `activej-http3` classes — not hand-encoded — so they
are exactly what this implementation's own encoder emits for those inputs. `QpackFixturesTest`
decodes each one back to its field list, so any drift between the archived bytes and the encoder's
current output surfaces as a normal test failure rather than a silent fixture rot.
