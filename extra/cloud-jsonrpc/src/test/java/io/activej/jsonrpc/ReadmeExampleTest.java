/*
 * Copyright (C) 2020 ActiveJ LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.activej.jsonrpc;

import io.activej.common.exception.MalformedDataException;
import io.activej.json.JsonCodec;
import io.activej.json.JsonCodecs;
import io.activej.jsonrpc.service.JsonRpcClient;
import io.activej.jsonrpc.service.JsonRpcDispatcher;
import io.activej.jsonrpc.service.fixtures.InMemoryTransport;
import io.activej.jsonrpc.service.fixtures.PaymentsApi;
import io.activej.jsonrpc.service.fixtures.PaymentsApiImpl;
import io.activej.jsonrpc.service.fixtures.User;
import io.activej.jsonrpc.service.fixtures.UserApi;
import io.activej.jsonrpc.service.fixtures.UserApiImpl;
import io.activej.jsonrpc.transport.JsonRpcTransport;
import io.activej.promise.Promise;
import io.activej.reactor.Reactor;
import io.activej.test.rules.EventloopRule;
import org.jetbrains.annotations.Nullable;
import org.junit.ClassRule;
import org.junit.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.activej.promise.TestUtils.await;
import static io.activej.promise.TestUtils.awaitException;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * The worked example from the module README, as compiled and executed code (SC-007, FR-103).
 * <p>
 * <b>The README's code blocks are copied from this file, not the other way round.</b> A README example
 * that only exists as prose rots into a lie the first time the API moves; this way the build fails
 * instead. If you change one, change the other — and run this test.
 * <p>
 * {@link #everyReadmeBlockIsCopiedFromCompilingCode()} makes that a build gate rather than a convention: it
 * reads {@code README.md} and asserts that every {@code java} block appears, line for line, in one of the
 * files it claims to be copied from.
 * <p>
 * Two README blocks are copied from files rather than from a method here, because their subject <i>is</i> a
 * whole file: the annotated interface is
 * {@link io.activej.jsonrpc.service.fixtures.UserApi UserApi}, and the conformance-harness subject is
 * {@link io.activej.jsonrpc.service.InMemoryTransportConformanceTest InMemoryTransportConformanceTest}. Both
 * compile and run in this same suite, so the guarantee is the same one.
 * <p>
 * The service-layer examples use the interface and the transport double the rest of the suite uses, so a
 * change that breaks them breaks far more than the README.
 */
public class ReadmeExampleTest {
	@ClassRule
	public static final EventloopRule eventloopRule = new EventloopRule();

	private static final JsonCodec<List<Integer>> LIST_OF_INT = JsonCodecs.ofList(JsonCodecs.ofInteger());

	// --- README: "Decode a request" ---------------------------------------------------------------
	@Test
	public void decodeARequest() throws MalformedDataException {
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
	}

	// --- README: "Answer it" ------------------------------------------------------------------------
	@Test
	public void answerIt() {
		JsonRpcId id = new JsonRpcId.Num(1);

		JsonRpcPayload result = JsonRpcPayload.encoded(JsonCodecs.ofInteger(), 6);
		byte[] bytes = JsonRpcEncoder.encode(JsonRpcResponse.ofResult(id, result));

		assertEquals("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":6}", new String(bytes, UTF_8));
	}

	// --- README: "Fail it" --------------------------------------------------------------------------
	@Test
	public void failIt() {
		JsonRpcId id = new JsonRpcId.Num(1);

		byte[] bytes = JsonRpcEncoder.encode(JsonRpcResponse.ofError(id, JsonRpcErrors.INVALID_PARAMS));

		assertEquals("{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":{\"code\":-32602,\"message\":\"Invalid params\"}}",
			new String(bytes, UTF_8));
	}

	// --- README: "Application error codes" ----------------------------------------------------------
	@Test
	public void applicationErrorCodesStayOutOfTheReservedRange() {
		JsonRpcError mine = JsonRpcErrors.of(1001, "Insufficient funds");     // fine
		assertEquals(1001, mine.code());

		assertThrows(IllegalArgumentException.class,
			() -> JsonRpcErrors.of(-32601, "my own error"));                  // reserved range

		// decoding is deliberately permissive: a peer's reserved code is kept verbatim
		JsonRpcInput peer = JsonRpcDecoder.decode("""
			{"jsonrpc":"2.0","id":1,"error":{"code":-32601,"message":"Method not found"}}""".getBytes(UTF_8));
		assertEquals(-32601, ((JsonRpcResponse) peer).error().code());
	}

	// --- README: "A notification produces nothing" --------------------------------------------------
	@Test
	public void aNotificationProducesNothing() {
		byte[] bytes = JsonRpcEncoder.encode(JsonRpcOutput.none());

		assertEquals(0, bytes.length);          // zero bytes — NOT "[]", which on the wire means -32600
		assertArrayEquals(new byte[0], bytes);
	}

	// --- README: "Batches" --------------------------------------------------------------------------
	@Test
	public void batches() {
		byte[] batchBytes = """
			[{"jsonrpc":"2.0","id":1,"method":"sum","params":[1,2]},\
			{"jsonrpc":"2.0","method":"log","params":["hi"]},\
			{"jsonrpc":"1.0","id":3,"method":"bad"}]""".getBytes(UTF_8);

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

		assertEquals("[{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":3}," +
					 "{\"jsonrpc\":\"2.0\",\"id\":3,\"error\":{\"code\":-32600,\"message\":\"Invalid Request\"}}]",
			new String(out, UTF_8));
	}

	// --- README: "The three rules most often implemented wrongly" -----------------------------------
	@Test
	public void theThreeBatchRulesMostOftenImplementedWrongly() {
		// 1. an empty array is NOT a batch — one -32600 object, not an array
		JsonRpcInput empty = JsonRpcDecoder.decode("[]".getBytes(UTF_8));
		assertTrue(empty instanceof JsonRpcMalformed);
		assertEquals("{\"jsonrpc\":\"2.0\",\"id\":null,\"error\":{\"code\":-32600,\"message\":\"Invalid Request\"}}",
			new String(JsonRpcEncoder.encode(((JsonRpcMalformed) empty).toResponse()), UTF_8));

		// 2. a batch of only notifications answers with nothing at all — distinct from "[]"
		JsonRpcInput notifications = JsonRpcDecoder.decode("""
			[{"jsonrpc":"2.0","method":"a"},{"jsonrpc":"2.0","method":"b"}]""".getBytes(UTF_8));
		assertEquals(2, ((JsonRpcBatch) notifications).size());
		assertEquals(0, JsonRpcEncoder.encode(JsonRpcOutput.none()).length);

		// 3. response order is not guaranteed; correlation is by id alone
	}

	// --- README: "Bounds" ---------------------------------------------------------------------------
	@Test
	public void bounds() {
		assertEquals("1Mb", JsonRpcLimits.MAX_BODY_SIZE.format());
		assertEquals(100, JsonRpcLimits.MAX_BATCH_SIZE);
		assertEquals(64, JsonRpcLimits.MAX_JSON_DEPTH);

		// a transport reads MAX_BODY_SIZE in its accumulation loop, before an envelope array exists
		assertTrue(JsonRpcLimits.MAX_BODY_SIZE.toLong() > 0);
	}

	// --- README: "Payload lifetime" -----------------------------------------------------------------
	@Test
	public void payloadLifetime() {
		byte[] envelope = """
			{"jsonrpc":"2.0","id":1,"method":"sum","params":[1,2,3]}""".getBytes(UTF_8);
		JsonRpcRequest request = (JsonRpcRequest) JsonRpcDecoder.decode(envelope);

		// retaining a payload keeps the WHOLE envelope array reachable
		byte[] independent = request.params().toByteArray();   // the escape hatch
		assertArrayEquals("[1,2,3]".getBytes(UTF_8), independent);

		// mutating the envelope after decoding invalidates every payload derived from it
		assertEquals(7, request.params().size());
	}

	// ---------------------------------------------------------------------------------------------------
	// The service layer. The interface behind every block below is the UserApi fixture, which the README
	// quotes verbatim.
	// ---------------------------------------------------------------------------------------------------

	// --- README: "Wire names" -----------------------------------------------------------------------
	@Test
	public void wireNames() {
		JsonRpcDispatcher dispatcher = JsonRpcDispatcher.builder(Reactor.getCurrentReactor())
			.withService(UserApi.class, new UserApiImpl())
			.build();

		// the service's prefix, a dot, and each method's own name
		assertEquals(Set.of("user.get", "user.touch"), dispatcher.wireNames());
	}

	// --- README: "Serve it" -------------------------------------------------------------------------
	@Test
	public void serveIt() {
		JsonRpcDispatcher dispatcher = JsonRpcDispatcher.builder(Reactor.getCurrentReactor())
			.withService(UserApi.class, new UserApiImpl())
			.build();

		byte[] response = await(dispatcher.dispatch("""
			{"jsonrpc":"2.0","id":1,"method":"user.get","params":[42]}""".getBytes(UTF_8)));

		assertEquals("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"id\":42,\"name\":\"user-42\"}}",
			new String(response, UTF_8));
	}

	// --- README: "Call it" --------------------------------------------------------------------------
	@Test
	public void callIt() {
		JsonRpcDispatcher dispatcher = JsonRpcDispatcher.builder(Reactor.getCurrentReactor())
			.withService(UserApi.class, new UserApiImpl())
			.build();

		// no transport ships in this module — this is the in-memory double the module's own tests use
		JsonRpcTransport transport = InMemoryTransport.create(dispatcher::dispatch);
		JsonRpcClient client = JsonRpcClient.builder(Reactor.getCurrentReactor(), transport).build();

		UserApi api = client.proxy(UserApi.class);

		Promise<User> user = api.getUser(42);   // {"jsonrpc":"2.0","id":1,"method":"user.get","params":[42]}
		api.touch(42);                          // {"jsonrpc":"2.0","method":"user.touch","params":[42]}

		assertEquals(new User(42, "user-42"), await(user));
		client.close();
	}

	// --- README: "A deliberate error, and an accidental one" ----------------------------------------
	@Test
	public void aDeliberateErrorAndAnAccidentalOne() {
		JsonRpcDispatcher dispatcher = JsonRpcDispatcher.builder(Reactor.getCurrentReactor())
			.withService(PaymentsApi.class, new PaymentsApiImpl())
			.build();

		// deliberate: the service's own code, message and `data` reach the peer verbatim
		byte[] deliberate = await(dispatcher.dispatch("""
			{"jsonrpc":"2.0","id":1,"method":"payments.charge","params":[1000]}""".getBytes(UTF_8)));
		assertEquals("{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":{\"code\":429,\"message\":\"Too many requests\"}}",
			new String(deliberate, UTF_8));

		// accidental: -32603 and nothing else. No class name, no message, no `data`, no stack frame
		byte[] accidental = await(dispatcher.dispatch("""
			{"jsonrpc":"2.0","id":2,"method":"payments.charge","params":[1]}""".getBytes(UTF_8)));
		assertEquals("{\"jsonrpc\":\"2.0\",\"id\":2,\"error\":{\"code\":-32603,\"message\":\"Internal error\"}}",
			new String(accidental, UTF_8));
	}

	// --- README: "What the caller gets" -------------------------------------------------------------
	@Test
	public void whatTheCallerGets() {
		JsonRpcDispatcher dispatcher = JsonRpcDispatcher.builder(Reactor.getCurrentReactor())
			.withService(PaymentsApi.class, new PaymentsApiImpl())
			.build();
		JsonRpcClient client = JsonRpcClient.builder(
			Reactor.getCurrentReactor(), InMemoryTransport.create(dispatcher::dispatch)).build();

		PaymentsApi payments = client.proxy(PaymentsApi.class);

		JsonRpcException deliberate = (JsonRpcException) awaitException(payments.charge(1000));
		assertEquals(429, deliberate.getError().code());
		assertEquals("Too many requests", deliberate.getError().message());

		JsonRpcException accidental = (JsonRpcException) awaitException(payments.charge(1));
		assertEquals(-32603, accidental.getError().code());

		client.close();
	}

	// --- README: "Serving over a transport" ---------------------------------------------------------
	@Test
	public void servingOverATransport() {
		JsonRpcDispatcher dispatcher = JsonRpcDispatcher.builder(Reactor.getCurrentReactor())
			.withService(UserApi.class, new UserApiImpl())
			.build();

		// `transport` is whichever JsonRpcTransport you plug in; this one is the in-memory double
		InMemoryTransport transport = InMemoryTransport.create(document -> Promise.of(new byte[0]));

		transport.setListener(new JsonRpcTransport.Listener() {
			@Override
			public void onDocument(byte[] document) {
				// one complete, contiguous document — the transport joined the pieces before calling us
				dispatcher.dispatch(document).whenResult(response -> {
					// zero bytes is how "no response document" is spelled: a notification, or a batch of
					// only notifications. It must never reach the wire (obligation 3)
					if (response.length != 0) transport.send(response);
				});
			}

			@Override
			public void onClosed(@Nullable Exception e) {
				// exactly once, whether the close was local, remote, or a failure of the medium
			}
		});

		transport.deliverFromPeer("""
			{"jsonrpc":"2.0","id":1,"method":"user.get","params":[42]}""".getBytes(UTF_8));

		assertEquals(List.of("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"id\":42,\"name\":\"user-42\"}}"),
			transport.sentText());
	}

	// ---------------------------------------------------------------------------------------------------
	// The guard that makes "copied verbatim" a build gate rather than a promise (FR-103).
	// ---------------------------------------------------------------------------------------------------

	/**
	 * Every {@code java} block of the README appears, line for line, in one of the files it is copied from.
	 * <p>
	 * Indentation is normalised away on both sides — the README dedents its blocks and renders a tab as four
	 * spaces, deliberately — so this compares the code and not the layout. Everything else must match: a block
	 * that drifts from the source, or source that moves out from under a block, fails here by name.
	 */
	@Test
	public void everyReadmeBlockIsCopiedFromCompilingCode() {
		String readme = read(moduleRoot().resolve("README.md"));
		List<String> sources = new ArrayList<>();
		for (String path : List.of(
			"src/test/java/io/activej/jsonrpc/ReadmeExampleTest.java",
			"src/test/java/io/activej/jsonrpc/service/fixtures/UserApi.java",
			"src/test/java/io/activej/jsonrpc/service/InMemoryTransportConformanceTest.java")
		) {
			sources.add(normalize(read(moduleRoot().resolve(path))));
		}

		Matcher blocks = Pattern.compile("```java\\n(.*?)```", Pattern.DOTALL).matcher(readme);
		int found = 0;
		while (blocks.find()) {
			found++;
			String block = normalize(blocks.group(1));
			boolean copied = false;
			for (String source : sources) {
				if (source.contains(block)) {
					copied = true;
					break;
				}
			}
			assertTrue("README java block #" + found + " is not copied from any of the files it claims to be " +
					   "copied from — the README has drifted, or the code moved out from under it:\n" +
					   blocks.group(1), copied);
		}
		assertTrue("the README must carry java blocks; finding none means this guard scanned the wrong file",
			found > 0);
	}

	/** Leading indentation and blank lines carry no meaning across the README/source boundary; the rest does. */
	private static String normalize(String text) {
		StringBuilder sb = new StringBuilder();
		for (String line : text.split("\n")) {
			String stripped = line.strip();
			if (stripped.isEmpty()) continue;
			sb.append(stripped).append('\n');
		}
		return sb.toString();
	}

	private static Path moduleRoot() {
		Path local = Path.of("README.md");
		// running from the reactor root rather than the module basedir
		return Files.isRegularFile(local) ? Path.of("") : Path.of("extra", "cloud-jsonrpc");
	}

	private static String read(Path path) {
		try {
			return Files.readString(path, UTF_8);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private static void fail(String message) {
		throw new AssertionError(message);
	}
}
