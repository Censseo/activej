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

package io.activej.jsonrpc.transport.tcp.fixtures;

import io.activej.async.exception.AsyncCloseException;
import io.activej.common.MemSize;
import io.activej.jsonrpc.service.JsonRpcDispatcher;
import io.activej.jsonrpc.transport.JsonRpcTransport;
import io.activej.jsonrpc.transport.tcp.JsonRpcTcpTransport;
import io.activej.net.SimpleServer;
import io.activej.net.socket.tcp.ITcpSocket;
import io.activej.promise.Promise;
import io.activej.reactor.nio.NioReactor;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.util.function.Consumer;

/**
 * The per-exchange acceptor of the two conformance subjects (research D8): one {@code acceptOnce}
 * {@link SimpleServer} bound to port {@code 0} and asked where the kernel put it (ADR-028;
 * {@code getFreePort()} is refused module-wide, FR-078), plus the <b>close-after-answer</b> exchange
 * policy the subjects apply to whichever end answers.
 *
 * <h2>Why an acceptor at all, rather than {@code JsonRpcTcpServer}</h2>
 * {@code JsonRpcTcpServer} keeps its connections open, which is what a deployed server must do and
 * exactly what the conformance harness cannot survive: {@code io.activej.promise.TestUtils.await} runs
 * the eventloop to <b>quiescence</b> and {@code Eventloop.isAlive()} counts selector keys, so a live
 * accept socket <i>or</i> a lingering connection blocks the return (research R3). This fixture is the
 * harness's own scaffolding — <b>subject policy, never production behaviour</b>: the accept socket dies
 * after one connection ({@code withAcceptOnce()}), and the answering side closes the connection once
 * the answer has been written, or immediately when there is no answer to write, because the
 * notification vectors need the close for quiescence too.
 *
 * <h2>The two entry points</h2>
 * <ul>
 *     <li>{@link #answering} — the <b>forward</b> subject: the accepted socket is wrapped in a
 *     {@link JsonRpcTcpTransport} at the given transport tier and answers every inbound document
 *     through the harness's dispatcher. The harness dials in and plays the client.</li>
 *     <li>{@link #listening} — the <b>reverse</b> subject: the accepted socket is handed over raw, so
 *     the test can expose the <i>server-side</i> transport to the harness and apply
 *     {@link #closeAfterAnswer} on the <i>client</i> end instead.</li>
 * </ul>
 * Both bind port {@code 0} and both are {@code acceptOnce}, so the two directions differ only in which
 * end answers.
 */
public final class ConformanceAcceptor implements AutoCloseable {
	private final SimpleServer server;

	private ConformanceAcceptor(SimpleServer server) {
		this.server = server;
	}

	/**
	 * An acceptor that wraps its one connection in a {@link JsonRpcTcpTransport} with
	 * {@code maxMessageSize} as its transport tier and answers each inbound document through
	 * {@code peer}, closing after the answer (FR-070/FR-071).
	 * <p>
	 * ⚠ {@code maxMessageSize} is the <b>receiving</b> side's tier and is the whole reason the forward
	 * subject's {@code skippedVectors()} can be empty: with the two tiers equal — the default — the
	 * transport tier wins and the envelope's {@code -32001} answer is unreachable, so a subject that
	 * must replay {@code envelope-too-large} passes a tier strictly above
	 * {@code JsonRpcLimits.MAX_BODY_SIZE} (contract tcp-framing.md §2).
	 */
	public static ConformanceAcceptor answering(NioReactor reactor, JsonRpcDispatcher peer, MemSize maxMessageSize) {
		return listening(reactor, socket -> {
			JsonRpcTcpTransport transport = JsonRpcTcpTransport.builder(reactor, socket)
				.withMaxMessageSize(maxMessageSize)
				.build();
			transport.setListener(closeAfterAnswer(transport, peer));
		});
	}

	/**
	 * An {@code acceptOnce} acceptor on port {@code 0} handing its one accepted socket to
	 * {@code onAccept} raw — the reverse direction's shape, where the harness holds the server end and
	 * the answering side is the client.
	 */
	public static ConformanceAcceptor listening(NioReactor reactor, Consumer<ITcpSocket> onAccept) {
		SimpleServer server = SimpleServer.builder(reactor, onAccept)
			.withListenPort(0)                                 // FR-078: :0, then asked where it landed
			.withAcceptOnce()                                  // D8: the accept socket must not outlive one exchange
			.build();
		try {
			server.listen();                                   // reactor thread = JUnit thread here
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		return new ConformanceAcceptor(server);
	}

	/** Where the kernel put the listening socket (ADR-028). */
	public InetSocketAddress boundAddress() {
		return server.getBoundAddresses().get(0);
	}

	/**
	 * The close-after-answer exchange policy (research D8), as a listener over {@code transport}: each
	 * inbound document is dispatched through {@code peer} — which is <b>total</b>, so there is no
	 * failure branch to write (FR-038a) — the answer is written if there is one, and the connection is
	 * closed once that write completes. A vector expecting no response produces no write and closes
	 * straight away.
	 * <p>
	 * The close is what lets {@code TestUtils.await}'s quiescence loop return; the terminating LF has
	 * already been written by {@code send} before the close, so the answer is fully framed on the wire
	 * before the channel dies.
	 */
	public static JsonRpcTransport.Listener closeAfterAnswer(JsonRpcTransport transport, JsonRpcDispatcher peer) {
		return new JsonRpcTransport.Listener() {
			@Override
			public void onDocument(byte[] document) {
				peer.dispatch(document).whenResult(response -> {
					Promise<Void> write = response.length > 0 ? transport.send(response) : Promise.complete();
					write.whenComplete(() -> transport.closeEx(new AsyncCloseException("exchange complete")));
				});
			}

			@Override
			public void onClosed(@Nullable Exception e) {
				// the harness owns the other end and closes it per exchange; nothing to do here
			}
		};
	}

	/** Closes the accept socket if it is somehow still open — {@code acceptOnce} has usually done it. */
	@Override
	public void close() {
		server.close();
	}
}
