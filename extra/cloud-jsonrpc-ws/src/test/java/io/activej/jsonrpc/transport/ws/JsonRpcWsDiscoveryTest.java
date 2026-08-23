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

package io.activej.jsonrpc.transport.ws;

import io.activej.async.exception.AsyncCloseException;
import io.activej.common.exception.MalformedDataException;
import io.activej.common.ref.Ref;
import io.activej.jsonrpc.JsonRpcDecoder;
import io.activej.jsonrpc.JsonRpcId;
import io.activej.jsonrpc.JsonRpcInput;
import io.activej.jsonrpc.JsonRpcResponse;
import io.activej.jsonrpc.schema.OpenRpcDocument;
import io.activej.jsonrpc.schema.OpenRpcInfo;
import io.activej.jsonrpc.schema.OpenRpcMethod;
import io.activej.jsonrpc.service.JsonRpcDispatcher;
import io.activej.jsonrpc.transport.JsonRpcTransport;
import io.activej.jsonrpc.transport.ws.fixtures.TestApi;
import io.activej.jsonrpc.transport.ws.fixtures.TestApiImpl;
import io.activej.jsonrpc.transport.ws.fixtures.WsPair;
import io.activej.promise.Promise;
import io.activej.promise.SettablePromise;
import io.activej.reactor.Reactor;
import io.activej.reactor.nio.NioReactor;
import io.activej.test.ExpectedException;
import io.activej.test.rules.ActivePromisesRule;
import io.activej.test.rules.ByteBufRule;
import io.activej.test.rules.EventloopRule;
import org.jetbrains.annotations.Nullable;
import org.junit.ClassRule;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static io.activej.promise.TestUtils.await;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.CoreMatchers.instanceOf;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Feature 018 (T022, US2, FR-043): {@code rpc.discover} reaches a caller over a <b>real WebSocket
 * connection</b>, with <b>no transport-side code change of any kind</b> — this module's
 * {@code src/main} is byte-for-byte what shipped with feature 015.
 * <p>
 * <b>What this test is for, and what it is not for.</b> The envelope-level semantics of discovery —
 * off by default, {@code -32601} when disabled, a notification producing nothing, a batch element
 * producing its own response element, non-empty {@code params} answering {@code -32602} — are
 * established once, over the in-memory transport, by {@code JsonRpcDispatcherDiscoveryTest} in
 * {@code activej-jsonrpc}. Repeating them here would assert the same dispatcher twice. The open
 * question a transport can answer is narrower and entirely its own: <b>does the document survive the
 * wire?</b> The OpenRPC document is the largest thing this transport has ever been asked to carry in
 * one message — several hundred bytes of deeply nested JSON in a single TEXT frame for even this
 * one-method fixture, growing with every registered method and type, where every other document in
 * this module's tests is a flat few-dozen-byte request — so "the framing carried it intact" is a real
 * claim, not a formality.
 * <p>
 * Hence exactly two assertions, in order of strength:
 * <ol>
 *     <li>the answer <b>decodes</b> to a well-formed OpenRPC document whose {@code info} is the one
 *     the application supplied and whose {@code methods} describe the service actually registered;</li>
 *     <li>the answer's bytes are <b>identical</b> to what the very same dispatcher produces in
 *     process for the very same request — the precise statement of "the transport is a byte-document
 *     pipe and it mangled nothing".</li>
 * </ol>
 * <b>Why no proxy call.</b> {@code rpc.discover} is a <i>dispatcher-owned</i> entry and belongs to no
 * annotated interface (contract rule 10 refuses the whole reserved {@code rpc.} namespace, FR-001),
 * so there is nothing for {@code JsonRpcClient.proxy(...)} to bind. The request is written as a raw
 * document through the transport itself, which is also what an integrator holding nothing but a
 * WebSocket console does.
 * <p>
 * <b>Quiescence (R3).</b> {@code TestUtils.await} runs the loop until the selector holds no keys, so
 * every server here is {@code acceptOnce} (via {@link WsPair}) and the client transport is closed
 * inside the awaited chain; {@link WsPair#closeAll()} is the belt to that braces.
 * <p>
 * <b>Known duplication, deliberate.</b> This class's {@code discoverOverTheWire} shape — {@code INFO},
 * {@code DISCOVER_REQUEST}, a {@code names()} helper, per-test reactor setup — is ~60% identical to
 * {@code JsonRpcTcpDiscoveryTest}'s. The two are kept separate rather than sharing a base class or a
 * {@code fixtures/} helper for the same reason this module's sibling keeps a two-occurrence
 * duplication private (see {@code extra/cloud-jsonrpc-tcp/CLAUDE.md}, "Known duplication, deliberate"):
 * promote only at a third consumer. There is no third transport left to add one.
 */
public final class JsonRpcWsDiscoveryTest {
	@ClassRule
	public static final EventloopRule eventloopRule = new EventloopRule();

	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	@ClassRule
	public static final ActivePromisesRule activePromisesRule = new ActivePromisesRule();

	/**
	 * Application-supplied, never derived (FR-013) — and deliberately unlike anything this module
	 * would produce on its own, so a document echoing a class name or a POM version could not pass.
	 */
	private static final OpenRpcInfo INFO = new OpenRpcInfo("WebSocket Test API", "4.5.6");

	/** The one request of this feature, written as the raw document an integrator would send. */
	private static final byte[] DISCOVER_REQUEST =
		"{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"rpc.discover\"}".getBytes(UTF_8);

	@Test
	public void rpcDiscoverRoundTripsOverARealWebSocketConnection() throws MalformedDataException {
		JsonRpcDispatcher dispatcher = discoveringDispatcher();

		JsonRpcInput decoded = JsonRpcDecoder.decode(discoverOverTheWire(dispatcher));
		assertThat(decoded, instanceOf(JsonRpcResponse.class));
		JsonRpcResponse response = (JsonRpcResponse) decoded;
		assertFalse("rpc.discover answers with a result, never an error", response.isError());
		assertEquals("the answer carries the request's own id", new JsonRpcId.Num(1), response.id());

		// the whole document survived one TEXT message and decodes as OpenRPC
		OpenRpcDocument document = response.result().decode(OpenRpcDocument.CODEC);
		assertEquals("the OpenRPC specification version is present", OpenRpcDocument.OPENRPC_VERSION,
			document.openrpc());
		assertEquals("the info is the one the application supplied", INFO, document.info());
		assertFalse("the document must describe at least one method", document.methods().isEmpty());
		assertTrue("the registered service's wire name must be described: " + names(document),
			names(document).contains("test.add"));
	}

	@Test
	public void theBytesOffTheWireAreExactlyTheBytesTheDispatcherProduced() {
		// the strongest form of "zero transport-side change": the same dispatcher, the same request,
		// once through a real socket and once in process — and the two are the same array. Any framing,
		// re-encoding or transcoding this transport did to the largest document it has ever carried
		// would show up right here
		JsonRpcDispatcher dispatcher = discoveringDispatcher();
		byte[] overTheWire = discoverOverTheWire(dispatcher);
		byte[] inProcess = await(dispatcher.dispatch(DISCOVER_REQUEST));

		assertArrayEquals("the WebSocket transport carried the document unchanged", inProcess, overTheWire);
	}

	// ---------------------------------------------------------------------------------------------------
	// Wiring — a real HttpServer mounting the production JsonRpcWsServlet, and a real client connection.
	// ---------------------------------------------------------------------------------------------------

	/** The one thing this feature adds to the wiring: {@code withDiscovery(...)}. Nothing else changes. */
	private static JsonRpcDispatcher discoveringDispatcher() {
		return JsonRpcDispatcher.builder(reactor())
			.withService(TestApi.class, new TestApiImpl())
			.withDiscovery(INFO)
			.build();
	}

	/**
	 * Sends one {@code rpc.discover} request through a real {@link JsonRpcWsTransport} to a real
	 * {@link JsonRpcWsServlet} and answers with the document that came back. Every socket the helper
	 * opens is closed before it returns.
	 */
	private static byte[] discoverOverTheWire(JsonRpcDispatcher dispatcher) {
		NioReactor reactor = reactor();
		WsPair pair = WsPair.serverUpgrade(reactor, JsonRpcWsServlet.builder(reactor, dispatcher).build());
		Ref<byte[]> answer = new Ref<>();
		List<byte[]> received = new ArrayList<>();

		await(pair.connect().then(webSocket -> {
			JsonRpcWsTransport transport = JsonRpcWsTransport.of(reactor, webSocket);
			SettablePromise<byte[]> firstDocument = new SettablePromise<>();
			transport.setListener(new JsonRpcTransport.Listener() {
				@Override
				public void onDocument(byte[] document) {
					received.add(document);
					if (!firstDocument.isComplete()) firstDocument.set(document);
				}

				@Override
				public void onClosed(@Nullable Exception e) {
					// a close before the answer must fail the chain, never hang TestUtils.await
					if (!firstDocument.isComplete()) {
						firstDocument.setException(e != null ?
							e :
							new AsyncCloseException("the connection closed before rpc.discover was answered"));
					}
				}
			});
			Promise<byte[]> awaited = firstDocument;
			return transport.send(DISCOVER_REQUEST)
				.then(() -> awaited)
				.whenResult(answer::set)
				.whenComplete(($, e) -> transport.closeEx(new ExpectedException("end of test")))
				.toVoid();
		}));

		pair.closeAll();
		assertNotNull("rpc.discover produced no document at all", answer.get());
		assertEquals("rpc.discover is one request and exactly one answer", 1, received.size());
		return answer.get();
	}

	private static List<String> names(OpenRpcDocument document) {
		return document.methods().stream().map(OpenRpcMethod::name).toList();
	}

	private static NioReactor reactor() {
		return (NioReactor) Reactor.getCurrentReactor();
	}
}
