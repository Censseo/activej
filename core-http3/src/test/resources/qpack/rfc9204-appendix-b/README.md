# RFC 9204 Appendix B — QPACK encoding and decoding examples

The five worked examples of [RFC 9204 Appendix B](https://www.rfc-editor.org/rfc/rfc9204.html#appendix-B),
transcribed byte-for-byte as machine-readable fixtures. This is the **primary QPACK acceptance
corpus** for feature `006-h3-advanced` (research D-11, contract `qpack-dynamic.md` § Test Contract,
SC-002).

**Provenance**: every byte here was copied from `https://www.rfc-editor.org/rfc/rfc9204.txt`, fetched
2026-08-04, not reconstructed from memory. Each hexadecimal group in the RFC's two-column
`Data | Interpretation` display was transcribed in order, and every value was then re-derived
independently — prefix-integer encodings, Huffman-free string lengths, RFC 9204 §4.5.1.1 Required
Insert Count / Delta Base arithmetic, and the §3.2.1 entry sizes (`len(name) + len(value) + 32`) —
against the `Size=` totals the RFC itself prints (0, 106, 160, 217, 215). All agree.

> Appendix B's dynamic-table diagrams show the **encoder's** table, which is why entries carry a
> `Ref` (outstanding unacknowledged field sections referencing that entry) and why an
> `^-- acknowledged --^` line appears. A decoder's table holds the same entries with no ref counts.

## Cumulative series — read this first

Appendix B is **one exchange in five parts, not five independent vectors**. B.2 sets the dynamic
table capacity that B.3–B.5 rely on; B.4's Duplicate names an entry B.2 inserted; B.5's eviction
depends on everything before it. Each file therefore declares `continues-from`, and
[`index.txt`](index.txt) lists the files in series order.

A test that wants B.4 in isolation must replay B.1 → B.2 → B.3 → B.4 into the same codec pair.
B.1 is the one file that stands alone (it predates the capacity instruction and needs only the
static table — it is the same vector feature 005 already archived as
`io/activej/http3/qpack/rfc9204-appendix-b1-static-name-reference.bin`, in raw binary rather than
this format).

## File format (v1)

Plain UTF-8 text, LF line endings, extension `.vec`. Designed to be parsed with `BufferedReader`
plus `String.split` and nothing else — no third-party parser, in keeping with the zero-dependency
rule that holds in test scope too.

### Lexical rules

| Rule | Detail |
|---|---|
| Comment | A line whose first non-whitespace character is `#`. Discard the whole line. Comments are **full-line only** — there are no trailing comments, so a `#` can never appear mid-value. |
| Blank line | Ignored everywhere. |
| Preamble | Every line before the first section header is a `key: value` metadata line. |
| Section header | A line of the form `[section-name]`, alone on its line. |
| Section body | Everything until the next section header or end of file. Its shape depends on the section (below). |
| Empty section | A section whose body has no non-comment lines. Legal and meaningful — an example may send no encoder-stream instruction, no field section, or no decoder-stream instruction. |

### Preamble keys

Split each preamble line at the **first** `':'`, then drop exactly one following space if present.

| Key | Type | Meaning |
|---|---|---|
| `name` | string | Example id: `B.1` … `B.5`. |
| `title` | string | The RFC's own section title. |
| `rfc` | string | Citation, e.g. `RFC 9204 Appendix B.2`. |
| `continues-from` | `name` or `none` | The example whose end state this one starts from. |
| `stream` | integer or `none` | The QUIC stream id the `[field-section]` was sent on; `none` when the example sends no field section. Also the id carried by a Section Acknowledgment / Stream Cancellation in `[decoder-stream]`. |
| `max-table-capacity` | integer (bytes) | `SETTINGS_QPACK_MAX_TABLE_CAPACITY` the decoding endpoint must advertise for this example to be legal. `220` throughout the series — the exact value B.2's `Set Dynamic Table Capacity` asks for, so it also exercises the "capacity == advertised maximum is legal, above it is `QPACK_ENCODER_STREAM_ERROR`" boundary. Any larger value also admits the series. |
| `table-capacity-before` | integer (bytes) | Current dynamic table capacity in force when the example starts. |
| `table-capacity-after` | integer (bytes) | Current capacity when it ends. These differ only in B.2 (`0` → `220`). |
| `max-entries` | integer | RFC 9204 §4.5.1.1 `MaxEntries` = `floor(max-table-capacity / 32)` = `6`. The modulus (`2 * MaxEntries` = 12) used to encode and reconstruct the Required Insert Count. Recorded because RIC reconstruction is where a decoder bug hides. |
| `blocked-streams` | integer | Minimum `SETTINGS_QPACK_BLOCKED_STREAMS` that admits the example under `delivery-order`. `0` for every example in this corpus, since the recorded order never leaves a section blocked. |
| `delivery-order` | comma-separated section names | The order the sections reach the decoding endpoint. Replay the byte sections in this order. |
| `blocked-delivery-order` | comma-separated section names | **B.4 only.** The alternative order the RFC's prose narrates (encoder-stream packet delayed), under which the field section blocks and `blocked-streams >= 1` is required. See the note at the top of that file. |

### Byte sections — `[encoder-stream]`, `[field-section]`, `[decoder-stream]`

Each non-comment line holds whitespace-separated lowercase hexadecimal byte pairs. Concatenate all
lines of the section, in file order, to obtain the section's byte string. Line breaks and grouping
carry **no** meaning — they follow the RFC's own display and the interpretation comments above each
group, so a reader can check a group against the RFC by eye.

- `[encoder-stream]` — RFC 9204 §4.3 instructions this endpoint's peer sent on the QPACK encoder
  stream (Set Dynamic Table Capacity, Insert With Name Reference, Insert With Literal Name, Duplicate).
- `[field-section]` — the encoded field section carried on the request stream named by `stream`,
  including its §4.5.1 prefix.
- `[decoder-stream]` — RFC 9204 §4.4 instructions sent back on the QPACK decoder stream
  (Section Acknowledgment, Stream Cancellation, Insert Count Increment).

### `[expected-fields]`

The decoded header list, **in order**, one field per line. This is what `[field-section]` decodes to.
Empty when the example sends no field section.

Parsing: find the first `':'` **at index > 0** (index 0 is excluded so a pseudo-header keeps its
leading colon), take everything before it as the field name, then drop exactly one following space if
present; the rest of the line, verbatim, is the field value.

```
:path: /index.html      ->  name ":path",      value "/index.html"
custom-key: custom-value->  name "custom-key", value "custom-value"
:path: /                ->  name ":path",      value "/"
```

Names are lowercase (RFC 9114 §4.1.1). Values are ASCII in this corpus and contain no leading or
trailing whitespace, no `#`, and no CR/LF; a value may be empty (`name:` with nothing after).

### `[expected-table]`

The **encoder's** dynamic table at the *end* of the example — after every section listed in
`delivery-order`, the decoder-stream instruction included. One entry per line, oldest (lowest
absolute index) first:

```
abs ref name value
```

`abs` and `ref` are integers; `name` is a token with no whitespace; `value` is the remainder of the
line, verbatim (it is never empty in this corpus). Split on the first three whitespace runs only.

Ref counts *within* an example are not represented — B.2 and B.4 each show entries at `Ref=1` between
their field section and their decoder-stream instruction, and those intermediate states are recorded
as comments in the files rather than as data. An empty table body means an empty table.

### `[expected-state]`

`key: value` lines, same split rule as the preamble.

| Key | Meaning |
|---|---|
| `insert-count` | Total insertions ever on this table; monotonic, survives eviction. |
| `known-received-count` | The encoder's view of how many insertions the decoder has acknowledged — the `^-- acknowledged --^` line in the RFC's diagrams. |
| `size` | `Σ (len(name) + len(value) + 32)` over the entries, RFC 9204 §3.2.1. Matches the `Size=` the RFC prints. |

## What each example covers

| File | Instructions exercised | Representations exercised | End state |
|---|---|---|---|
| `b1-literal-field-line-with-name-reference.vec` | — | Literal Field Line with Name Reference (static, `H=0`); prefix RIC=0/Base=0 | empty, size 0 |
| `b2-dynamic-table.vec` | Set Dynamic Table Capacity; Insert With Name Reference ×2 (static name); Section Acknowledgment | Indexed Field Line With Post-Base Index ×2; prefix RIC=2/`S=1`/Delta Base=1 | 2 entries, size 106, KRC 2 |
| `b3-speculative-insert.vec` | Insert With Literal Name; Insert Count Increment | — (no field section) | 3 entries, size 160, KRC 3 |
| `b4-duplicate-stream-cancellation.vec` | Duplicate; Stream Cancellation | Indexed Field Line dynamic ×2 and static ×1; prefix RIC=4/`S=0`/Delta Base=0 | 4 entries, size 217, KRC 3 |
| `b5-dynamic-table-insert-eviction.vec` | Insert With Name Reference (**dynamic** name) causing eviction | — (no field section) | 4 entries (abs 0 evicted), size 215, KRC 3 |

Between them the five cover all four encoder-stream instructions, all three decoder-stream
instructions, four of the five field-line representations, both `S` values of the field-section
prefix, and eviction. **Not** covered by Appendix B, so they need their own tests rather than a
fixture here: Huffman-coded (`H=1`) names or values, the never-indexed (`N=1`) literal forms, Literal
Field Line With Post-Base Name Reference, and any error path.

## Consumers

- `QpackVectors.java` (task T004) — the loader for this format.
- `QpackRfc9204VectorsTest.java` (task T016) — drives the decoder over these files.

Keep this document in step with the format: two other tasks read and write files here, and a format
change that is not documented here is a silent break.
