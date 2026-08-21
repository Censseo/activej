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

package io.activej.jsonrpc.transport.tcp;

import io.activej.async.function.AsyncRunnable;
import io.activej.bytebuf.ByteBuf;
import io.activej.jsonrpc.service.JsonRpcDispatcher;
import io.activej.jsonrpc.transport.JsonRpcTransport;
import io.activej.jsonrpc.transport.tcp.fixtures.TestApi;
import io.activej.jsonrpc.transport.tcp.fixtures.TestApiImpl;
import io.activej.net.SimpleServer;
import io.activej.net.socket.tcp.ITcpSocket;
import io.activej.net.socket.tcp.TcpSocket;
import io.activej.promise.Promise;
import io.activej.promise.Promises;
import io.activej.promise.SettablePromise;
import io.activej.reactor.Reactor;
import io.activej.reactor.nio.NioReactor;
import io.activej.test.rules.ActivePromisesRule;
import io.activej.test.rules.ByteBufRule;
import io.activej.test.rules.EventloopRule;
import org.jetbrains.annotations.Nullable;
import org.junit.ClassRule;
import org.junit.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.BiFunction;

import static io.activej.promise.TestUtils.await;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertEquals;

/**
 * Fragmentation (T007, FR-090, SC-005): a document that arrives in pieces is still one document.
 * <p>
 * This is the property the source feature warned about most loudly, and the one the build harness
 * <b>cannot</b> prove: Surefire's {@code AsyncTcpSocketNio.debugReadOffset=1} names a class that no
 * longer exists, and the property {@code TcpSocket} actually reads
 * ({@code TcpSocket.debugReadOffset}) shifts buffer <i>offsets</i> rather than truncating reads
 * (research R10). So the proof is deliberate rather than ambient: two tests write a real request over
 * a real socket in deliberately small pieces and require the dispatcher behind the transport to
 * answer it correctly every time.
 * <p>
 * <b>What is being tested is the decoder's restartability</b>, not the kernel's segmentation: each
 * piece is written only after the previous write has completed and — between frames — only after the
 * previous answer has come back, which is the strongest determinism a real socket allows. Nothing
 * here depends on how the pieces actually land; if they arrive coalesced the test still holds, and if
 * they arrive split it exercises exactly the resume path {@code OfByteTerminated} has to get right.
 * <p>
 * No production code is expected to change for this test to pass — a failure here is the framing bug,
 * not a missing feature.
 */
public final class JsonRpcTcpFragmentationTest {
	@ClassRule
	public static final EventloopRule eventloopRule = new EventloopRule();

	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	@ClassRule
	public static final ActivePromisesRule activePromisesRule = new ActivePromisesRule();

	private static final String REQUEST = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"test.add\",\"params\":[2,3]}";
	private static final String EXPECTED_ANSWER = "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"sum\":5}}";

	@Test
	public void testDocumentWrittenOneByteAtATimeIsAnsweredCorrectly() {
		// FR-090: the extreme case — every byte its own write, each issued only once the previous one
		// has completed. The decoder must return null for "need more input" 58 times and then produce
		// the whole document exactly once.
		byte[] frame = (REQUEST + "\n").getBytes(UTF_8);
		List<String> answers = new ArrayList<>();

		withDispatchingServer((clientSocket, answerQueue) -> {
			List<AsyncRunnable> bytes = new ArrayList<>(frame.length);
			for (int i = 0; i < frame.length; i++) {
				int at = i;
				bytes.add(() -> write(clientSocket, frame, at, at + 1));
			}
			return Promises.sequence(bytes)
				.then(answerQueue::next)
				.whenResult(answer -> answers.add(new String(answer, UTF_8)))
				.toVoid();
		});

		assertEquals(List.of(EXPECTED_ANSWER), answers);
	}

	@Test
	public void testDocumentSplitAtEveryInternalBoundaryIsAnsweredCorrectly() {
		// FR-090: every internal split point of the framed message, including the one that separates the
		// last content byte from its terminator — the boundary a decoder that peeks for the terminator
		// in the wrong buffer gets wrong. One connection carries all of them, each round trip completed
		// before the next split is written, so a mis-split would desynchronise the stream visibly rather
		// than silently.
		byte[] frame = (REQUEST + "\n").getBytes(UTF_8);
		List<String> answers = new ArrayList<>();

		withDispatchingServer((clientSocket, answerQueue) -> {
			List<AsyncRunnable> splits = new ArrayList<>(frame.length - 1);
			for (int split = 1; split < frame.length; split++) {
				int at = split;
				splits.add(() -> write(clientSocket, frame, 0, at)
					.then(() -> write(clientSocket, frame, at, frame.length))
					.then(answerQueue::next)
					.whenResult(answer -> answers.add(new String(answer, UTF_8)))
					.toVoid());
			}
			return Promises.sequence(splits);
		});

		assertEquals("one answer per split point", frame.length - 1, answers.size());
		for (String answer : answers) {
			assertEquals(EXPECTED_ANSWER, answer);
		}
	}

	// -------------------------------------------------------------------------------------------
	// Fixture.
	// -------------------------------------------------------------------------------------------

	/**
	 * One real connection whose server side is a {@link JsonRpcTcpTransport} feeding a
	 * {@link JsonRpcDispatcher} — the minimal pairing that makes "answered correctly" an assertion
	 * about the framing rather than about a byte echo.
	 * <p>
	 * The client side is handed to {@code body} as a <b>raw socket</b>, because these tests write
	 * deliberately malformed <i>chunks</i> that no {@code send} would ever produce; its answers are read
	 * back through a client-side transport, which is the only part of the client that needs framing.
	 */
	private static void withDispatchingServer(BiFunction<ITcpSocket, AnswerQueue, Promise<Void>> body) {
		NioReactor reactor = Reactor.getCurrentReactor();
		JsonRpcDispatcher dispatcher = JsonRpcDispatcher.builder(reactor)
			.withService(TestApi.class, new TestApiImpl())
			.build();

		SettablePromise<ITcpSocket> accepted = new SettablePromise<>();
		SimpleServer server = SimpleServer.builder(reactor, accepted::set)
			.withListenPort(0)
			.withAcceptOnce()
			.build();
		try {
			server.listen();
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}

		await(TcpSocket.connect(reactor, server.getBoundAddresses().get(0))
			.then(clientSocket -> accepted.then(serverSocket -> {
				JsonRpcTcpTransport serverTransport = JsonRpcTcpTransport.of(reactor, serverSocket);
				serverTransport.setListener(new JsonRpcTransport.Listener() {
					@Override
					public void onDocument(byte[] document) {
						// the dispatcher is total: no failure branch exists to write here
						dispatcher.dispatch(document).whenResult(answer -> {
							if (answer.length != 0) serverTransport.send(answer);
						});
					}

					@Override
					public void onClosed(@Nullable Exception e) {}
				});

				AnswerQueue answers = new AnswerQueue();
				JsonRpcTcpTransport clientTransport = JsonRpcTcpTransport.of(reactor, clientSocket);
				clientTransport.setListener(new JsonRpcTransport.Listener() {
					@Override
					public void onDocument(byte[] document) {
						answers.accept(document);
					}

					@Override
					public void onClosed(@Nullable Exception e) {}
				});

				return body.apply(clientSocket, answers)
					.whenComplete(($, e) -> {
						clientTransport.close();
						serverTransport.close();
					});
			})));
	}

	/** Writes {@code frame[from, to)} without a terminator of its own — the pieces carry their own. */
	private static Promise<Void> write(ITcpSocket socket, byte[] frame, int from, int to) {
		// a wrapped (non-pooled) ByteBuf has no refs, so the socket's recycle is a no-op and the shared
		// frame array is never cleared or pooled — the sanctioned way to write a constant
		return socket.write(ByteBuf.wrap(frame, from, to));
	}

	/**
	 * The answers the client transport delivered, awaited one at a time. A queue rather than a single
	 * slot because an answer may arrive before the test asks for it, and a dropped answer would look
	 * like a framing failure.
	 */
	private static final class AnswerQueue {
		private final Deque<byte[]> ready = new ArrayDeque<>();
		private @Nullable SettablePromise<byte[]> waiting;

		void accept(byte[] document) {
			SettablePromise<byte[]> waiting = this.waiting;
			if (waiting != null) {
				this.waiting = null;
				waiting.set(document);
			} else {
				ready.add(document);
			}
		}

		Promise<byte[]> next() {
			if (!ready.isEmpty()) return Promise.of(ready.poll());
			SettablePromise<byte[]> promise = new SettablePromise<>();
			waiting = promise;
			return promise;
		}
	}
}
