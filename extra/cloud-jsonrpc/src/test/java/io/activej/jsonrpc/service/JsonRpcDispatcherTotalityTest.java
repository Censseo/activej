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

package io.activej.jsonrpc.service;

import com.dslplatform.json.JsonReader;
import com.dslplatform.json.JsonWriter;
import io.activej.json.JsonCodec;
import io.activej.jsonrpc.JsonRpcDecoder;
import io.activej.jsonrpc.JsonRpcError;
import io.activej.jsonrpc.JsonRpcException;
import io.activej.jsonrpc.JsonRpcLimits;
import io.activej.jsonrpc.JsonRpcPayload;
import io.activej.jsonrpc.service.fixtures.UserApi;
import io.activej.jsonrpc.service.fixtures.UserApiImpl;
import io.activej.promise.Promise;
import io.activej.reactor.Reactor;
import io.activej.test.rules.EventloopRule;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static io.activej.promise.TestUtils.await;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * FR-038a — {@code dispatch(byte[])} is <b>total</b>: its promise never completes exceptionally, for any
 * input, however hostile. Every failure arrives as an error document (or as nothing at all, for a
 * notification), so a transport author writes {@code dispatch(...).then(this::respond)} with no failure
 * branch.
 * <p>
 * This is asserted by construction rather than by inspection: the corpus below is replayed through the
 * promise, and any exceptional completion fails the test with the offending input named.
 */
public class JsonRpcDispatcherTotalityTest {
	@ClassRule
	public static final EventloopRule eventloopRule = new EventloopRule();

	private JsonRpcDispatcher dispatcher;

	@Before
	public void setUp() {
		dispatcher = JsonRpcDispatcher.builder(Reactor.getCurrentReactor())
			.withService(UserApi.class, new UserApiImpl())
			.build();
	}

	@Test
	public void everyHostileDocumentProducesADocumentRatherThanAFailure() {
		for (Case hostile : corpus()) {
			byte[] response;
			try {
				response = await(dispatcher.dispatch(hostile.document()));
			} catch (Throwable e) {
				throw new AssertionError("dispatch must never fail, but " + hostile.name() + " did", e);
			}
			assertNotNull(hostile.name() + " produced a null document", response);
			if (response.length != 0) {
				String text = new String(response, UTF_8);
				assertTrue(hostile.name() + " produced a non-JSON-RPC document: " + text,
					text.startsWith("{") || text.startsWith("["));
				assertTrue(hostile.name() + " must produce an error document: " + text, text.contains("\"error\""));
			}
		}
	}

	@Test
	public void theStructuredEntryPointIsTotalToo() {
		for (Case hostile : corpus()) {
			try {
				assertNotNull(await(dispatcher.dispatch(JsonRpcDecoder.decode(hostile.document()))));
			} catch (Throwable e) {
				throw new AssertionError("dispatch(JsonRpcInput) must never fail, but " + hostile.name() +
										" did", e);
			}
		}
	}

	@Test
	public void anEmptyTopLevelArrayIsASingle32600RatherThanAnEmptyBatch() {
		String response = dispatchToString("[]");

		assertEquals("{\"jsonrpc\":\"2.0\",\"id\":null,\"error\":{\"code\":-32600,\"message\":\"Invalid Request\"}}",
			response);
	}

	@Test
	public void anOversizeDocumentIs32001AndIsNeverParsed() {
		byte[] oversize = new byte[(int) JsonRpcLimits.MAX_BODY_SIZE.toLong() + 1];
		Arrays.fill(oversize, (byte) ' ');
		oversize[0] = '{';

		String response = new String(await(dispatcher.dispatch(oversize)), UTF_8);

		assertTrue(response, response.contains("-32001"));
	}

	@Test
	public void aBatchOfOnlyNotificationsProducesNothingAtAll() {
		byte[] response = await(dispatcher.dispatch(utf8(
			"[{\"jsonrpc\":\"2.0\",\"method\":\"user.touch\",\"params\":[1]}," +
			"{\"jsonrpc\":\"2.0\",\"method\":\"user.touch\",\"params\":[2]}]")));

		assertEquals("an all-notification batch is zero bytes, never []", 0, response.length);
	}

	/**
	 * FR-047 lets a service fail with a {@link JsonRpcException} carrying arbitrary {@code data}; nothing
	 * stops that {@code data}'s own codec from throwing while being written. That failure surfaces one layer
	 * further out than a throwing <b>result</b> codec (which {@code respond()} already guards) — inside
	 * {@code JsonRpcEncoder.encode}, called from {@code dispatch(byte[])}'s final {@code .map(...)} — and must
	 * not break totality any more than the result-side case does.
	 */
	@Test
	public void aThrowingErrorDataCodecStillProducesADocumentRatherThanAFailedPromise() {
		JsonCodec<String> throwingCodec = new JsonCodec<>() {
			@Override
			public void write(JsonWriter writer, String value) {
				throw new IllegalStateException("this codec always refuses to write");
			}

			@Override
			public String read(JsonReader<?> reader) {
				throw new UnsupportedOperationException();
			}
		};
		JsonRpcError badData = new JsonRpcError(1001, "bad data", JsonRpcPayload.encoded(throwingCodec, "x"));

		JsonRpcDispatcher broken = JsonRpcDispatcher.builder(Reactor.getCurrentReactor())
			.withService(BadDataApi.class, () -> Promise.ofException(new JsonRpcException(badData)))
			.build();

		byte[] response;
		try {
			response = await(broken.dispatch(
				utf8("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"bad.get\",\"params\":[]}")));
		} catch (Throwable e) {
			throw new AssertionError("dispatch must never fail even when an error's own data codec throws", e);
		}

		String text = new String(response, UTF_8);
		assertEquals("{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":{\"code\":-32603,\"message\":\"Internal error\"}}", text);
	}

	@JsonRpcService("bad")
	private interface BadDataApi {
		@JsonRpcMethod("get")
		Promise<String> get();
	}

	@Test
	public void aMixedBatchAnswersOnlyTheProducingElements() {
		String response = dispatchToString(
			"[{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"user.get\",\"params\":[1]}," +
			"{\"jsonrpc\":\"2.0\",\"method\":\"user.touch\",\"params\":[2]}," +
			"{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"user.nope\"}]");

		assertEquals(
			"[{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"id\":1,\"name\":\"user-1\"}}," +
			"{\"jsonrpc\":\"2.0\",\"id\":3,\"error\":{\"code\":-32601,\"message\":\"Method not found\"}}]",
			response);
	}

	// ---------------------------------------------------------------------------------------------------
	// The corpus.
	// ---------------------------------------------------------------------------------------------------

	private record Case(String name, byte[] document) {}

	private static List<Case> corpus() {
		List<Case> corpus = new ArrayList<>();
		corpus.add(new Case("zero-length", new byte[0]));
		corpus.add(new Case("empty top-level array", utf8("[]")));
		corpus.add(new Case("truncated object", utf8("{\"jsonrpc\":\"2.0\",\"id\":1,")));
		corpus.add(new Case("truncated array", utf8("[{\"jsonrpc\":\"2.0\"")));
		corpus.add(new Case("wrong version", utf8("{\"jsonrpc\":\"1.0\",\"id\":1,\"method\":\"user.get\"}")));
		corpus.add(new Case("no version", utf8("{\"id\":1,\"method\":\"user.get\"}")));
		corpus.add(new Case("bare literal", utf8("42")));
		corpus.add(new Case("bare string", utf8("\"user.get\"")));
		corpus.add(new Case("json null", utf8("null")));
		corpus.add(new Case("array of scalars", utf8("[1,2,3]")));
		corpus.add(new Case("nested arrays", utf8("[[{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"user.get\"}]]")));
		corpus.add(new Case("empty object", utf8("{}")));
		corpus.add(new Case("duplicate members",
			utf8("{\"jsonrpc\":\"2.0\",\"id\":1,\"id\":2,\"method\":\"user.get\",\"params\":[1]}")));
		corpus.add(new Case("params of the wrong shape",
			utf8("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"user.get\",\"params\":true}")));
		corpus.add(new Case("params of the wrong type",
			utf8("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"user.get\",\"params\":[{}]}")));
		corpus.add(new Case("empty method name", utf8("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"\"}")));
		corpus.add(new Case("fractional id",
			utf8("{\"jsonrpc\":\"2.0\",\"id\":1.5,\"method\":\"user.get\",\"params\":[1]}")));
		corpus.add(new Case("invalid utf-8", new byte[]{'{', (byte) 0xC3, '"', '}'}));
		corpus.add(new Case("lone continuation byte", new byte[]{(byte) 0x80, (byte) 0x80}));
		corpus.add(new Case("deeply nested params", deeplyNested()));
		corpus.add(new Case("oversize batch", oversizeBatch()));
		return corpus;
	}

	/** Past {@code JsonRpcLimits.MAX_JSON_DEPTH}, which the decoder refuses before the parser recurses. */
	private static byte[] deeplyNested() {
		int depth = JsonRpcLimits.MAX_JSON_DEPTH + 8;
		StringBuilder sb = new StringBuilder("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"user.get\",\"params\":[");
		sb.append("[".repeat(depth)).append("]".repeat(depth)).append("]}");
		return utf8(sb.toString());
	}

	/** Past {@code JsonRpcLimits.MAX_BATCH_SIZE}. */
	private static byte[] oversizeBatch() {
		StringBuilder sb = new StringBuilder("[");
		for (int i = 0; i <= JsonRpcLimits.MAX_BATCH_SIZE; i++) {
			if (i != 0) sb.append(',');
			sb.append("{\"jsonrpc\":\"2.0\",\"method\":\"user.touch\",\"params\":[").append(i).append("]}");
		}
		return utf8(sb.append(']').toString());
	}

	private String dispatchToString(String document) {
		return new String(await(dispatcher.dispatch(utf8(document))), UTF_8);
	}

	private static byte[] utf8(String s) {
		return s.getBytes(UTF_8);
	}
}
