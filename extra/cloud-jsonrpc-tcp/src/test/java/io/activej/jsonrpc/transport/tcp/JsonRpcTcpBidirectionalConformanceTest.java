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

import io.activej.common.MemSize;
import io.activej.eventloop.Eventloop;
import io.activej.jsonrpc.JsonRpcLimits;
import io.activej.jsonrpc.service.AbstractBidirectionalTransportConformanceTest;
import io.activej.jsonrpc.service.JsonRpcDispatcher;
import io.activej.jsonrpc.transport.JsonRpcTransport;
import io.activej.jsonrpc.transport.tcp.fixtures.ConformanceAcceptor;
import io.activej.jsonrpc.transport.tcp.fixtures.ConnectingTransport;
import io.activej.jsonrpc.transport.tcp.fixtures.HoldingTransport;
import io.activej.net.socket.tcp.ITcpSocket;
import io.activej.promise.SettablePromise;
import io.activej.reactor.Reactor;
import io.activej.reactor.nio.NioReactor;
import org.jetbrains.annotations.Nullable;
import org.junit.After;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The reverse-direction conformance harness instantiated over a <b>real framed-TCP connection</b>, the
 * <b>server as caller</b> (T023, US5, FR-074…FR-078, SC-003): every one of the 30 vectors replays with
 * the server initiating the call and the <b>client's</b> dispatcher — the harness's {@code clientPeer}
 * — answering, over a real {@code acceptOnce} acceptor and a real dialled connection per exchange. The
 * harness's dispatcher, service interface and comparison rules are <b>not</b> overridden and its
 * sources are untouched (FR-075).
 * <p>
 * That this is a replay rather than a second implementation is the point of FR-062:
 * {@link JsonRpcTcpTransport} is <b>one class on both ends</b> — {@code of(...)} wraps the accepted
 * socket, {@code connect(...)} dials — so the reverse direction exercises the same framing, the same
 * serial read loop and the same close latch as the forward one, with the roles swapped.
 *
 * <h2>The subject wiring (research D8/D10)</h2>
 * {@code createServerTransport(clientPeer)} starts an {@code acceptOnce} acceptor on port {@code 0}
 * that hands its accepted socket over <b>raw</b>, dials a real client at a raised transport tier, and
 * — once both ends exist — wires the <i>client</i> end to answer every inbound document through
 * {@code clientPeer} before releasing the <i>server</i> end to the harness. The order matters: the
 * harness's {@code send} must not outrun the client's listener, and unlike the WebSocket subject there
 * is no handshake round trip to order the two ends for us, so both promises are joined explicitly.
 * The client then <b>closes the connection after answering — or not</b> (D8): the notification vectors
 * produce no answer, and the close is what lets {@code TestUtils.await}'s quiescence loop return
 * (research R3). Close-after-answer is subject policy, never production behaviour.
 *
 * <h2>The lazy connect (D8)</h2>
 * {@code createServerTransport} is called synchronously, before the loop has run, so the server-side
 * transport is returned as a {@link ConnectingTransport} that defers {@code setListener}/{@code send}
 * until the connection resolves — which happens inside the harness's {@code await(send)}.
 *
 * <h2>FR-071 — the raised transport tier, and why {@code skippedVectors()} is empty</h2>
 * In this direction the <b>client receives</b> {@code envelope-too-large}'s
 * {@code JsonRpcLimits.MAX_BODY_SIZE + 1} bytes, so it is the client's tier that must sit strictly
 * above the envelope tier ({@link #RAISED_TIER}) for the document to reach the decoder and answer
 * {@code -32001} rather than dying as a framing violation mid-accumulation (contract tcp-framing.md
 * §2). Nothing is skipped in either direction.
 *
 * <h2>FR-076 — the reorder test runs, roles swapped</h2>
 * {@code createReorderableTransport(clientPeer)} returns the same in-memory {@link HoldingTransport}
 * double (D9) over the harness's {@code clientPeer} dispatcher, so the correlation-by-{@code id} test
 * runs rather than {@code assumeTrue}-skipping — with the harness's server as the caller and
 * {@code clientPeer} as the answering client dispatcher.
 */
public final class JsonRpcTcpBidirectionalConformanceTest extends AbstractBidirectionalTransportConformanceTest {
	// the harness's @ClassRule EventloopRule + ByteBufRule + ActivePromisesRule are inherited

	/**
	 * The receiving side's transport tier — the <b>client's</b> in this direction — strictly above the
	 * 1 mb {@link JsonRpcLimits#MAX_BODY_SIZE} envelope tier so {@code envelope-too-large} reaches the
	 * client's decoder (FR-071).
	 */
	private static final MemSize RAISED_TIER = MemSize.megabytes(2);

	private final List<ConformanceAcceptor> acceptors = new ArrayList<>();

	@Override
	protected JsonRpcTransport createServerTransport(JsonRpcDispatcher clientPeer) {
		NioReactor reactor = Reactor.getCurrentReactor();
		SettablePromise<ITcpSocket> accepted = new SettablePromise<>();
		ConformanceAcceptor acceptor = ConformanceAcceptor.listening(reactor, accepted::set);
		acceptors.add(acceptor);

		SettablePromise<JsonRpcTcpTransport> serverTransport = new SettablePromise<>();
		// the client RECEIVES every vector's request here, envelope-too-large included (FR-071)
		JsonRpcTcpTransport.connect(reactor, acceptor.boundAddress(), null, null, RAISED_TIER)
			.whenComplete((clientTransport, e) -> {
				if (e != null) {
					serverTransport.setException(e);              // a failed dial fails the harness's send with its cause
					return;
				}
				// a raw TCP dial resolves without any round trip, so the accept may not have run yet:
				// both ends are joined explicitly rather than assumed ordered by a handshake
				accepted.whenComplete((socket, acceptException) -> {
					if (acceptException != null) {
						serverTransport.setException(acceptException);
						return;
					}
					JsonRpcTcpTransport serverSide = JsonRpcTcpTransport.of(reactor, socket);
					// wire the answering client end FIRST — the harness's send must not outrun its listener
					clientTransport.setListener(
						ConformanceAcceptor.closeAfterAnswer(clientTransport, clientPeer));
					serverTransport.set(serverSide);
				});
			});
		// lazy connect: resolves inside the harness's await(send) loop run (D8)
		return new ConnectingTransport(serverTransport);
	}

	@After
	public void closeAcceptors() {                                 // the harness closes only the transport
		for (ConformanceAcceptor acceptor : acceptors) acceptor.close();
		acceptors.clear();
		((Eventloop) Reactor.getCurrentReactor()).run();           // process the close tasks
	}

	@Override
	protected void awaitDelivery() {
		// D8: await(send) already ran the loop to quiescence (the client closed after answering);
		// this is the FR-072-mandated second drive — a no-op when idle. Never blocks.
		((Eventloop) Reactor.getCurrentReactor()).run();
	}

	@Override
	protected Set<String> skippedVectors() {
		// FR-071: EMPTY. The client's transport tier is raised to 2 mb, strictly above the 1 mb
		// JsonRpcLimits.MAX_BODY_SIZE envelope tier, so envelope-too-large's 1,048,577-byte request
		// reaches the client's decoder and answers -32001. All 30 vectors replay server-to-client
		// over a real socket (SC-003).
		return Set.of();
	}

	@Override
	protected @Nullable ReorderableTransport createReorderableTransport(JsonRpcDispatcher clientPeer) {
		return new ReorderableTcpDouble(clientPeer);
	}

	/**
	 * The harness's {@code ReorderableTransport} is a {@code protected} nested interface, nameable only
	 * from a subclass — so the shared {@link HoldingTransport} fixture carries the behaviour and this
	 * one-line subclass declares it (FR-076).
	 */
	private static final class ReorderableTcpDouble extends HoldingTransport implements ReorderableTransport {
		private ReorderableTcpDouble(JsonRpcDispatcher clientPeer) {
			super(clientPeer);
		}
	}
}
