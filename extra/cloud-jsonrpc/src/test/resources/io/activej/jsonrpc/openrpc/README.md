# Frozen OpenRPC reference documents

Byte-exact OpenRPC documents generated from the **reference service fixture** and compared
byte-for-byte by `OpenRpcSchemaTest`. They are the contract non-regression gate for the JSON-RPC
schema-discovery line: a change to the fixture, to the `Type` → JSON Schema mapping, or to the
document's member order shows up here as a deliberate diff, and nowhere else silently.

Everything in this directory is loaded from the classpath at test time, so a stale or corrupt
reference fails a real test rather than sitting unreferenced. (`README.md` and `VERDICT.md` are the
two exceptions — they are read by people, not by tests.)

## Files

| File | Provenance |
|---|---|
| `reference-api.openrpc.json` | **4227 bytes**, sha256 `6229b2ebe6bab8474c6b086ab5567d2b7dddb6343a8633ce516b9a0cb0501134`. Generated from [`ReferenceApi`](../../../../../java/io/activej/jsonrpc/schema/fixtures/ReferenceApi.java) by `JsonRpcSchemaGenerator.generateBytes(new OpenRpcInfo("Reference API", "1.0.0"), JsonRpcServiceContract.of(ReferenceApi.class, JsonCodecFactory.defaultInstance()))` (task T024) and commit-frozen. Describes the fixture's ten wire names — `reference.archive`, `.audit`, `.boxedScalars`, `.catalogue`, `.containers`, `.createOrder`, `.orderSeen`, `.ping`, `.scalars`, `.sum` — sorted by wire name (rule M2), at `"openrpc": "1.4.0"`. Externally validated: see `VERDICT.md` |
| `VERDICT.md` | The recorded **external-validator verdict** (tool, version, date, meta-schema, outcome, exact invocation). Run 2026-08-21, outcome **pass** |
| `README.md` | This page — what lives here, how it was produced, and how to re-freeze it deliberately. |

Naming convention: one file per fixture, `<fixture-in-kebab-case>.openrpc.json`. There is exactly
one today, and the plural is kept throughout this page because adding a second fixture is an
addition, not a restructuring.

⚠ The frozen file is the generator's output **verbatim**: a single line of UTF-8 JSON, **no trailing
newline** and no BOM. `.editorconfig` covers `*.java` and `*.xml`/`*.js`/… but not `*.json`, so
nothing adds one automatically — and `OpenRpcSchemaTest.theFrozenReferenceIsCommittedAsTheGeneratorWroteIt`
fails if an editor does it by hand.

## How these are produced

The reference fixture is
[`ReferenceApi`](../../../../../java/io/activej/jsonrpc/schema/fixtures/ReferenceApi.java)
(`extra/cloud-jsonrpc/src/test/java/io/activej/jsonrpc/schema/fixtures/ReferenceApi.java`, task
T001). It is not a realistic service and is not meant to be: it exists to exercise **every** shape
the mapping claims to support plus at least one it does not (FR-053) — methods and notifications,
named and positional parameters, a `record` DTO, each row of the pinned subset in
`specs/018-jsonrpc-schema-discovery/data-model.md`'s mapping table, and one type on the documented
partial fallback (`"schema": true`).

The document itself is produced by `io.activej.jsonrpc.schema.JsonRpcSchemaGenerator` — the *same*
generator the runtime `rpc.discover` method and the HTTP discovery endpoint serve from, which is
what makes freezing these bytes meaningful for all three (FR-040). The generator is static,
synchronous and reactor-free; nothing about producing a reference document involves an eventloop, a
`Promise`, a socket or a network.

The encoder is deterministic — fixed member order, methods sorted by wire name — which is the
property that lets the comparison be byte-for-byte rather than member-wise. Member-wise comparison
was considered and rejected: it tolerates drift nobody asked to tolerate (research Decision 7).

## Regenerating a reference document (deliberate contract change)

Re-freeze **only** when the change to the emitted document is intentional. `OpenRpcSchemaTest`
failing is not by itself a reason to regenerate — it is the gate doing its job. Read the diff first
and decide whether the *contract* changed or the *code* broke.

1. **Make the deliberate change** — to `ReferenceApi`, to the mapping in `io.activej.jsonrpc.schema`,
   or to the document shape pinned by
   `specs/018-jsonrpc-schema-discovery/contracts/openrpc-mapping.md`.

2. **Observe the failure**, and read the drift diff the test prints:

   ```bash
   mvn -P extra -pl extra/cloud-jsonrpc -am test \
       -Dtest=OpenRpcSchemaTest -Dsurefire.failIfNoSpecifiedTests=false
   ```

   ⚠ `-Dsurefire.failIfNoSpecifiedTests=false` is **mandatory** on any `-Dtest=` run under `-am`,
   and `-am` is itself mandatory (this module test-depends on `activej-test`).

   The failure names the drift rather than an array index — it reads both sides back through
   `OpenRpcDocument.CODEC` and reports methods gained and lost, a renamed parameter, a changed
   schema, a `result` member appearing or disappearing (which *is* the notification marker, rule M3)
   and a broken sort order, then prints the first differing byte with its context:

   ```text
   the generated OpenRPC document is not the frozen reference /io/activej/jsonrpc/openrpc/reference-api.openrpc.json
     frozen:    4227 bytes
     generated: 4105 bytes

   structural drift (frozen -> generated):
     - reference.audit: in the frozen reference, absent from the generated document
     ~ reference.orderSeen: gained a result member -- it was a notification in the frozen reference and is a callable method now (rule M3)
     ~ reference.scalars: param "count" schema {"type":"integer"} -> {"type":"number"}

   first byte divergence at offset 266: frozen 0x61 'a', generated 0x62 'b'
     frozen:    ...:"result","required":true,"schema":{"type":"null"}}},{"name":"reference.audit","params":[{"name":"at","required":true,"schema":true},{"name":"by
     generated: ...:"result","required":true,"schema":{"type":"null"}}},{"name":"reference.boxedScalars","params":[{"name":"flag","required":true,"schema":{"type":
                                                                                           ^
   ```

   (That example is verbatim, from a document in which `reference.audit` was dropped,
   `reference.orderSeen` gained a `result` and `reference.scalars`' `count` widened to `number` —
   three unrelated changes, all three named.)

3. **Regenerate the bytes** with the shipped generator — never by hand-editing the JSON. Run it
   against the compiled test classes, so what is frozen is exactly what this implementation emits.
   This is what task T024 actually ran:

   ```bash
   mvn -P extra -pl extra/cloud-jsonrpc -am test-compile
   mvn -P extra -pl extra/cloud-jsonrpc dependency:build-classpath \
       -Dmdep.outputFile=/tmp/jsonrpc-cp.txt -Dmdep.includeScope=test

   cat > /tmp/FreezeReference.java <<'EOF'
   import io.activej.json.JsonCodecFactory;
   import io.activej.jsonrpc.schema.JsonRpcSchemaGenerator;
   import io.activej.jsonrpc.schema.OpenRpcInfo;
   import io.activej.jsonrpc.schema.fixtures.ReferenceApi;
   import io.activej.jsonrpc.service.JsonRpcServiceContract;

   import java.nio.file.Files;
   import java.nio.file.Path;

   public final class FreezeReference {
       public static void main(String[] args) throws Exception {
           byte[] document = JsonRpcSchemaGenerator.generateBytes(
               new OpenRpcInfo("Reference API", "1.0.0"),
               JsonRpcServiceContract.of(ReferenceApi.class, JsonCodecFactory.defaultInstance()));

           Files.write(Path.of(args[0]), document);
           System.out.println("wrote " + document.length + " bytes to " + args[0]);
       }
   }
   EOF

   java --class-path "extra/cloud-jsonrpc/target/classes:extra/cloud-jsonrpc/target/test-classes:$(cat /tmp/jsonrpc-cp.txt)" \
       /tmp/FreezeReference.java \
       extra/cloud-jsonrpc/src/test/resources/io/activej/jsonrpc/openrpc/reference-api.openrpc.json
   ```

   (Java's single-file source launcher compiles it in memory; there is no `javac` step and no class
   file to clean up.) A `jshell` session over the same classpath is equivalent:

   ```java
   var info = new io.activej.jsonrpc.schema.OpenRpcInfo("Reference API", "1.0.0");
   var contract = io.activej.jsonrpc.service.JsonRpcServiceContract.of(
           io.activej.jsonrpc.schema.fixtures.ReferenceApi.class,
           io.activej.json.JsonCodecFactory.defaultInstance());
   byte[] document = io.activej.jsonrpc.schema.JsonRpcSchemaGenerator.generateBytes(info, contract);
   java.nio.file.Files.write(java.nio.file.Path.of(
           "extra/cloud-jsonrpc/src/test/resources/io/activej/jsonrpc/openrpc/reference-api.openrpc.json"),
           document);
   ```

   ⚠ The `OpenRpcInfo` **must** be the one `OpenRpcSchemaTest.INFO` holds — `("Reference API",
   "1.0.0")`. It is emitted verbatim into the document's `info` member (FR-013), so a different
   title or version is a different document, and the test would report `info.title` / `info.version`
   drift.

   A throwaway `@Test`/`main` that writes the same bytes is equally acceptable — but it is
   **throwaway**: it does not get committed (WI-18). The committed artifacts are the `.json` files,
   this page and `VERDICT.md`.

4. **Re-run step 2** — it must now be green.

5. **Re-validate externally and re-record the verdict.** A regenerated document is an
   *unvalidated* document until a validator has seen it: see the next section, then update
   [`VERDICT.md`](VERDICT.md) with the new tool version, date and outcome. A reference commit that
   changes a `.json` file without touching `VERDICT.md` is incomplete.

6. **Commit the `.json` diff as the visible record of the contract change**, alongside the code
   change that caused it. The diff *is* the review surface — that is the whole reason these files
   are versioned (spec §Data & State, FR-050…FR-052).

## External validation — offline, by a developer, never in CI

CI has no network and never validates against anything remote: it compares the generated bytes to
the frozen files here, and that is the entire CI story (FR-051, SC-003). Conformance to OpenRPC
itself is established **once, offline, by a person**, and the outcome is recorded in
[`VERDICT.md`](VERDICT.md) (FR-050, FR-052, SC-002).

The procedure, as pinned by task T025 and executed on 2026-08-21:

1. **Obtain the OpenRPC meta-schema for the version the document declares.** These documents carry
   `"openrpc": "1.4.0"` (`OpenRpcDocument.OPENRPC_VERSION`), so the applicable meta-schema is the
   **OpenRPC 1.4** one, published as npm **`@open-rpc/spec-types@0.0.2`** →
   `spec.OpenRPCSpecificationSchema1_4`.

   ⚠ **Not** `https://meta.open-rpc.org/`, and not npm `@open-rpc/meta-schema`. Both currently serve
   a **1.3-era** meta-schema whose `openrpc` member is an enum topping out at `1.3.2` — it was last
   published 2024-05-08, before OpenRPC 1.4.0 (2026-02-24). Validating against it reports exactly
   one error, on `/openrpc`, and nothing else. `VERDICT.md` note 1 records that run, the control that
   isolates it, and why it is the meta-schema lagging rather than the document drifting.

2. **Validate with the pinned draft-07 validator**: **`ajv-cli` 5.0.0** driving **`ajv` 8.17.1**,
   `--spec=draft7 --strict=false --all-errors`. The full invocation — including the two
   registrations of `https://meta.json-schema.tools` the OpenRPC meta-schema `$ref`s, one of which
   must be an id alias for ajv to resolve it — is in [`VERDICT.md`](VERDICT.md) §Invocation,
   verbatim and reproducible. Cross-checked with the OpenRPC project's own
   `@open-rpc/schema-utils-js@2.2.1` `validateOpenRPCDocument`.

   No validator, JSON Schema library or OpenRPC library enters the build: all of the above is
   installed into a scratch directory and thrown away (constitution I — zero new third-party
   dependencies, SC-005).

3. **Record tool, version, date, meta-schema, the exact invocation and the outcome** in
   [`VERDICT.md`](VERDICT.md), with a new row in its History table. A failure is not recorded and
   shipped: fix the mapping or the fixture, regenerate, and validate again before committing the
   reference.

4. **Prove the run is not vacuous.** A misconfigured validator reports "valid" for everything —
   `VERDICT.md` note 3 keeps four deliberately broken copies of the document and the errors each one
   drew, so a future run can be checked against them.

## Related

- [`OpenRpcSchemaTest`](../../../../../java/io/activej/jsonrpc/schema/OpenRpcSchemaTest.java) — the
  byte-exact comparison and the drift message (T023).
- [`ReferenceApi`](../../../../../java/io/activej/jsonrpc/schema/fixtures/ReferenceApi.java) — the
  fixture (T001).
- `contracts/openrpc-mapping.md` and `data-model.md` in
  `specs/018-jsonrpc-schema-discovery/` — the normative document shape and the pinned
  `Type` → JSON Schema subset.
- `../conformance/` — the JSON-RPC 2.0 envelope conformance vectors (feature 010). Same idea, one
  layer down: frozen bytes as the regression gate.
