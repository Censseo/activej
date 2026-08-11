# ActiveJ JSON-RPC

JSON-RPC 2.0 **envelope** layer for the ActiveJ platform: an immutable message model (request,
notification, response, error, batch), a decoder over a contiguous `byte[]` that leaves
`params` / `result` / `error.data` undecoded, and a deterministic encoder.

This module is a part of ActiveJ platform.

## Maturity

**Experimental — `extra/` modules are not production-ready.** This module lives under `extra/`, which
is profile-gated and whose per-module maturity is an explicitly open question for the platform. Treat
it as experimental: the API may change without a deprecation cycle, and the default `mvn verify` does
not build or test it at all.

## How this differs from `activej-rpc`

`activej-jsonrpc` is a JSON-RPC 2.0 text-protocol envelope layer with no transport, no reactor and no
server, whereas `activej-rpc` (`cloud-rpc`) is a full binary RPC client/server stack.

Concretely, this module contains no `Reactive` type, no `Promise`, no `ByteBuf` and no bytecode
generation — it is a synchronous codec over `byte[]`, testable without an eventloop. Serving JSON-RPC
over a transport is a separate, later concern and does not live here.

## Build and test

The `extra` profile is **mandatory** — without it this module is not in the Maven reactor at all.

```bash
# the module plus its upstream modules
mvn -P extra -pl extra/cloud-jsonrpc -am test

# a single test class. -Dsurefire.failIfNoSpecifiedTests=false is REQUIRED whenever -Dtest= is
# combined with -am: Surefire matches the pattern against every upstream module first and fails
# before reaching this one
mvn -P extra -pl extra/cloud-jsonrpc -am test \
    -Dtest=SomeTest -Dsurefire.failIfNoSpecifiedTests=false

# without -am, the flag is not needed
mvn -P extra -pl extra/cloud-jsonrpc clean test

# the whole extra tree
mvn -P extra verify
```

## The contiguous-`byte[]` contract

The decoder takes one contiguous `byte[]` holding the whole envelope. This is a hard contract rather
than a convenience: an undecoded payload is an index pair into that exact array, and the underlying
parser's index space does not survive a buffer refill. A transport whose bytes arrive in pieces must
join them before decoding.

## Worked example

> Every code block below is copied verbatim from
> [`ReadmeExampleTest`](src/test/java/io/activej/jsonrpc/ReadmeExampleTest.java), which compiles and
> runs as part of the build. If this README drifts from the API, that test fails — it does not quietly
> become wrong.

### Decode a request

`method` and `id` are decoded; `params` is deliberately left as raw bytes until you know what it
should be.

```java
byte[] envelope = """
    {"jsonrpc":"2.0","id":1,"method":"sum","params":[1,2,3]}""".getBytes(UTF_8);

JsonRpcInput input = JsonRpcDecoder.decode(envelope);

switch (input) {
    case JsonRpcRequest request -> {
        assertEquals("sum", request.method());                   // decoded
        assertEquals(new JsonRpcId.Num(1), request.id());        // decoded

        // `params` is NOT decoded yet — decode it once you know what it should be
        List<Integer> params = request.params().decode(LIST_OF_INT);
        assertEquals(List.of(1, 2, 3), params);
    }
    case JsonRpcNotification notification -> fail("no response may be built for " + notification);
    case JsonRpcResponse response -> fail("a peer answering us: " + response);
    case JsonRpcMalformed malformed -> fail("render malformed.toResponse(): " + malformed);
    case JsonRpcBatch batch -> fail("one outcome per element: " + batch);
}
```

`decode` **never throws** for malformed input. A failure arrives as `JsonRpcMalformed` carrying the
error object and the id if one was recoverable — a batch needs one outcome per element, which an
exception cannot express.

### Answer it

```java
JsonRpcPayload result = JsonRpcPayload.encoded(JsonCodecs.ofInteger(), 6);
byte[] bytes = JsonRpcEncoder.encode(JsonRpcResponse.ofResult(id, result));

assertEquals("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":6}", new String(bytes, UTF_8));
```

### Fail it

```java
byte[] bytes = JsonRpcEncoder.encode(JsonRpcResponse.ofError(id, JsonRpcErrors.INVALID_PARAMS));

assertEquals("{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":{\"code\":-32602,\"message\":\"Invalid params\"}}",
    new String(bytes, UTF_8));
```

An application-defined code must stay out of the reserved `-32768 … -32000` range. Decoding is
deliberately more permissive: a peer's reserved code is kept verbatim, because discarding it would
throw away exactly what the peer meant to say.

```java
JsonRpcError mine = JsonRpcErrors.of(1001, "Insufficient funds");     // fine
assertEquals(1001, mine.code());

assertThrows(IllegalArgumentException.class,
    () -> JsonRpcErrors.of(-32601, "my own error"));                  // reserved range

// decoding is deliberately permissive: a peer's reserved code is kept verbatim
JsonRpcInput peer = JsonRpcDecoder.decode("""
    {"jsonrpc":"2.0","id":1,"error":{"code":-32601,"message":"Method not found"}}""".getBytes(UTF_8));
assertEquals(-32601, ((JsonRpcResponse) peer).error().code());
```

### A notification produces nothing

There is no method anywhere in this module that builds a response from a `JsonRpcNotification` — the
type has no `id` component at all. "A notification produces no response" is a compile-time fact, not
a runtime check.

```java
byte[] bytes = JsonRpcEncoder.encode(JsonRpcOutput.none());

assertEquals(0, bytes.length);          // zero bytes — NOT "[]", which on the wire means -32600
assertArrayEquals(new byte[0], bytes);
```

### Batches

```java
JsonRpcInput input = JsonRpcDecoder.decode(batchBytes);
byte[] out = new byte[0];

if (input instanceof JsonRpcBatch batch) {
    List<JsonRpcMessage> responses = new ArrayList<>();
    for (JsonRpcDecoded element : batch.elements()) {
        switch (element) {
            case JsonRpcRequest request -> responses.add(JsonRpcResponse.ofResult(
                request.id(), JsonRpcPayload.encoded(JsonCodecs.ofInteger(), 3)));
            case JsonRpcNotification ignored -> { }                     // no response
            case JsonRpcMalformed m -> responses.add(m.toResponse());    // one bad element != bad batch
            case JsonRpcResponse ignored -> { }
        }
    }
    // a batch renders as an array even at size 1; no responses at all renders as nothing
    out = JsonRpcEncoder.encode(
        responses.isEmpty() ? JsonRpcOutput.none() : JsonRpcOutput.batch(responses));
}
```

Three rules worth stating, because they are the ones most often implemented wrongly:

- An **empty** input array `[]` is not a batch — it decodes to a single `-32600`, rendered as one
  object, not an array.
- A batch of **only notifications** produces **no response document at all**, distinct from `[]`.
- Response **order is not guaranteed** and must not be relied on. Correlation is by `id` alone.

### Bounds

Three limits ship enabled. You raise them with a system property; you never enable them.

| Setting key | Default | Refusal |
|---|---|---|
| `JsonRpcLimits.maxBodySize` | `1mb` | `-32001 Request too large` |
| `JsonRpcLimits.maxBatchSize` | `100` | `-32002 Batch too large` |
| `JsonRpcLimits.maxJsonDepth` | `64` | `-32003 Nesting too deep` |

```bash
java -DJsonRpcLimits.maxBodySize=4mb -DJsonRpcLimits.maxBatchSize=500 ...
```

The size bound is meant to be applied **while** you accumulate, not after — a bound checked against
an array you have already allocated arrives after the allocation it was supposed to prevent. Read
`JsonRpcLimits.MAX_BODY_SIZE` in your accumulation loop.

The depth bound is checked by a scan over your array **before** any parsing, because the underlying
parser's `skip()` recurses once per nesting level and would exhaust the stack before any in-parser
check could fire.

### Payload lifetime

A decoded payload holds a reference to **your** array plus a `[start, end)` pair — it does not copy.

```java
// retaining a payload keeps the WHOLE envelope array reachable
byte[] independent = request.params().toByteArray();   // the escape hatch
assertArrayEquals("[1,2,3]".getBytes(UTF_8), independent);

// mutating the envelope after decoding invalidates every payload derived from it
assertEquals(7, request.params().size());
```

This module never mutates your array, and never retains it beyond what a payload you hold keeps
alive.

## What this module is not

| You want | Where it lives |
|---|---|
| An annotated Java interface, a dispatcher, a client proxy | not here — a later feature |
| HTTP / WebSocket / TCP transport | not here — a later feature |
| Automatic `JsonCodec` derivation for a `record` | `activej-json` |
| Per-call timeouts, in-flight bounds | not here — a later feature |
| A DI module, JMX, a launcher | not here — a later feature |

For an ActiveJ-to-ActiveJ binary protocol, use `activej-rpc` instead — it is faster and in the
default build. This module exists for interoperability with clients that are not ActiveJ.
