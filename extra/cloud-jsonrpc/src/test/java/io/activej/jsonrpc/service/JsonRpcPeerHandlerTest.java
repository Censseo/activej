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
import io.activej.jsonrpc.JsonRpcError;
import io.activej.jsonrpc.JsonRpcId;
import io.activej.jsonrpc.JsonRpcOutput;
import io.activej.jsonrpc.JsonRpcPayload;
import io.activej.jsonrpc.JsonRpcResponse;
import io.activej.jsonrpc.service.fixtures.InMemoryTransport;
import io.activej.jsonrpc.service.fixtures.User;
import io.activej.jsonrpc.service.fixtures.UserApi;
import io.activej.jsonrpc.service.fixtures.UserApiImpl;
import io.activej.promise.Promise;
import io.activej.reactor.Reactor;
import io.activej.test.rules.EventloopRule;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The server&rarr;client direction: an inbound document that is <b>not</b> an answer to a pending call
 * (FR-076, FR-077).
 * <p>
 * The transport's peer answers nothing at all here, so every document in {@code sentText()} is something the
 * client itself decided to send — which is exactly what is under test.
 */
public class JsonRpcPeerHandlerTest {
	@ClassRule
	public static final EventloopRule eventloopRule = new EventloopRule();

	private UserApiImpl implementation;
	private JsonRpcDispatcher dispatcher;
	private InMemoryTransport transport;

	@Before
	public void setUp() {
		implementation = new UserApiImpl();
		dispatcher = JsonRpcDispatcher.builder(Reactor.getCurrentReactor())
			.withService(UserApi.class, implementation)
			.build();
		// a peer that never answers: only the client's own outbound decisions land in sentText()
		transport = InMemoryTransport.create(document -> Promise.of(new byte[0]));
	}

	// ---------------------------------------------------------------------------------------------------
	// The default handler.
	// ---------------------------------------------------------------------------------------------------

	@Test
	public void anInboundRequestIsAnswered32601ByDefault() {
		JsonRpcClient client = JsonRpcClient.builder(Reactor.getCurrentReactor(), transport).build();

		transport.deliverFromPeer(utf8("{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"user.get\",\"params\":[1]}"));

		assertEquals(
			List.of("{\"jsonrpc\":\"2.0\",\"id\":7,\"error\":{\"code\":-32601,\"message\":\"Method not found\"}}"),
			transport.sentText());
		assertEquals("an inbound request creates no correlation entry", 0, client.inFlightCount());
	}

	@Test
	public void anInboundNotificationIsIgnoredByDefault() {
		JsonRpcClient.builder(Reactor.getCurrentReactor(), transport).build();

		transport.deliverFromPeer(utf8("{\"jsonrpc\":\"2.0\",\"method\":\"user.touch\",\"params\":[1]}"));

		assertEquals("§4.1 forbids answering a notification", List.of(), transport.sentText());
	}

	@Test
	public void theDefaultHandlerIsMethodNotFound() {
		JsonRpcClient explicit = JsonRpcClient.builder(Reactor.getCurrentReactor(), transport)
			.withPeerHandler(JsonRpcPeerHandler.methodNotFound())
			.build();

		transport.deliverFromPeer(utf8("{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"anything\"}"));

		assertEquals(
			List.of("{\"jsonrpc\":\"2.0\",\"id\":7,\"error\":{\"code\":-32601,\"message\":\"Method not found\"}}"),
			transport.sentText());
		assertEquals(0, explicit.inFlightCount());
	}

	// ---------------------------------------------------------------------------------------------------
	// A dispatcher as the handler — feature 06's whole attachment surface (FR-076).
	// ---------------------------------------------------------------------------------------------------

	@Test
	public void aDispatcherHandlesAnInboundRequest() {
		JsonRpcClient.builder(Reactor.getCurrentReactor(), transport)
			.withPeerHandler(dispatcher::dispatch)
			.build();

		transport.deliverFromPeer(utf8("{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"user.get\",\"params\":[1]}"));

		assertEquals(List.of("{\"jsonrpc\":\"2.0\",\"id\":7,\"result\":{\"id\":1,\"name\":\"user-1\"}}"),
			transport.sentText());
		assertEquals(List.of("getUser(1)"), implementation.invocations());
	}

	@Test
	public void aDispatcherHandlesAnInboundNotificationWithoutAnswering() {
		JsonRpcClient.builder(Reactor.getCurrentReactor(), transport)
			.withPeerHandler(dispatcher::dispatch)
			.build();

		transport.deliverFromPeer(utf8("{\"jsonrpc\":\"2.0\",\"method\":\"user.touch\",\"params\":[1]}"));

		assertEquals("a notification produces no response document at all", List.of(), transport.sentText());
		assertEquals(List.of("touch(1)"), implementation.invocations());
	}

	// ---------------------------------------------------------------------------------------------------
	// Batches (FR-077).
	// ---------------------------------------------------------------------------------------------------

	@Test
	public void anInboundBatchIsHandledElementByElementAndAnsweredAsOneBatch() {
		JsonRpcClient.builder(Reactor.getCurrentReactor(), transport)
			.withPeerHandler(dispatcher::dispatch)
			.build();

		transport.deliverFromPeer(utf8("[" +
									   "{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"user.get\",\"params\":[1]}," +
									   "{\"jsonrpc\":\"2.0\",\"method\":\"user.touch\",\"params\":[2]}," +
									   "{\"jsonrpc\":\"2.0\",\"id\":8,\"method\":\"user.get\",\"params\":[3]}]"));

		assertEquals(List.of("[" +
							 "{\"jsonrpc\":\"2.0\",\"id\":7,\"result\":{\"id\":1,\"name\":\"user-1\"}}," +
							 "{\"jsonrpc\":\"2.0\",\"id\":8,\"result\":{\"id\":3,\"name\":\"user-3\"}}]"),
			transport.sentText());
		assertEquals(List.of("getUser(1)", "touch(2)", "getUser(3)"), implementation.invocations());
	}

	@Test
	public void aBatchOfOnlyNotificationsProducesNoDocument() {
		JsonRpcClient.builder(Reactor.getCurrentReactor(), transport)
			.withPeerHandler(dispatcher::dispatch)
			.build();

		transport.deliverFromPeer(utf8("[{\"jsonrpc\":\"2.0\",\"method\":\"user.touch\",\"params\":[1]}," +
									   "{\"jsonrpc\":\"2.0\",\"method\":\"user.touch\",\"params\":[2]}]"));

		assertEquals("a batch that produced nothing is zero bytes, never []", List.of(), transport.sentText());
		assertEquals(List.of("touch(1)", "touch(2)"), implementation.invocations());
	}

	@Test
	public void aBatchMixingAnAnswerAndARequestCorrelatesOneAndRoutesTheOther() {
		JsonRpcClient client = JsonRpcClient.builder(Reactor.getCurrentReactor(), transport)
			.withPeerHandler(dispatcher::dispatch)
			.build();
		Promise<User> pending = client.proxy(UserApi.class).getUser(42);
		assertEquals(1, client.inFlightCount());

		transport.deliverFromPeer(utf8("[" +
									   "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"id\":42,\"name\":\"user-42\"}}," +
									   "{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"user.get\",\"params\":[1]}]"));

		assertEquals(new User(42, "user-42"), pending.getResult());
		assertEquals(0, client.inFlightCount());
		assertTrue(transport.sentText().toString(),
			transport.sentText().contains(
				"[{\"jsonrpc\":\"2.0\",\"id\":7,\"result\":{\"id\":1,\"name\":\"user-1\"}}]"));
	}

	/**
	 * A peer handler's output goes through the same encode step {@code send()} always used — if that output
	 * carries a {@link JsonRpcError#data()} whose own codec throws while being written, the failure must be
	 * reported to the client's failure handler rather than escape {@code send()}: {@code handleElement}'s own
	 * contract is "never completes exceptionally", since an inbound element has no caller to fail instead.
	 */
	@Test
	public void aPeerHandlerOutputWithAThrowingErrorDataCodecIsReportedRatherThanThrown() {
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

		List<Exception> reported = new ArrayList<>();
		JsonRpcClient.builder(Reactor.getCurrentReactor(), transport)
			.withPeerHandler(incoming -> Promise.of(
				JsonRpcOutput.single(JsonRpcResponse.ofError(new JsonRpcId.Num(7), badData))))
			.withFailureHandler(reported::add)
			.build();

		transport.deliverFromPeer(utf8("{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"anything\"}"));

		assertEquals("the broken encode must not escape into the reactor", 1, reported.size());
		assertEquals("nothing must reach the transport when encoding the answer failed",
			List.of(), transport.sentText());
	}

	private static byte[] utf8(String s) {
		return s.getBytes(UTF_8);
	}
}
