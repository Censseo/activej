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
import io.activej.jsonrpc.service.AbstractTransportConformanceTest;
import io.activej.jsonrpc.service.JsonRpcDispatcher;
import io.activej.jsonrpc.transport.JsonRpcTransport;
import io.activej.jsonrpc.transport.tcp.fixtures.ConformanceAcceptor;
import io.activej.jsonrpc.transport.tcp.fixtures.ConnectingTransport;
import io.activej.jsonrpc.transport.tcp.fixtures.HoldingTransport;
import io.activej.reactor.Reactor;
import io.activej.reactor.nio.NioReactor;
import org.jetbrains.annotations.Nullable;
import org.junit.After;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Feature 012's conformance harness instantiated over a <b>real framed-TCP connection</b>, the
 * <b>client as caller</b> (T022, US5, FR-070…FR-073, FR-077/FR-078, SC-002): every one of the 30
 * vectors replays end to end through {@link JsonRpcTcpTransport} against a real
 * {@code acceptOnce} acceptor on port {@code 0} that answers through the harness's own dispatcher,
 * with a real dialled connection per exchange. The harness's dispatcher, service interface and
 * comparison rules are <b>not</b> overridden and its sources are untouched (FR-075) — the vectors are
 * replayed, never copied.
 * <p>
 * <b>Subject lifecycle (research D8 — quiescence by construction).</b> {@code TestUtils.await} — which
 * the harness drives every exchange with — runs the eventloop to <b>quiescence</b>, and
 * {@code Eventloop.isAlive()} counts selector keys: a live accept socket <i>or</i> connection blocks
 * the return (research R3). Every acceptor is therefore {@code withAcceptOnce()} and the answering
 * side <b>closes the connection after processing each inbound document</b> — answering it <i>or
 * not</i>, because the notification vectors need the close for quiescence too. That close-after-answer
 * is <b>subject-level policy</b> (test code, in {@link ConformanceAcceptor}), never
 * {@link JsonRpcTcpServer}'s behaviour: a deployed server keeps its connections, which is precisely
 * what the harness cannot survive.
 * <p>
 * <b>The lazy connect (D8).</b> {@code createTransport} is called synchronously, before the loop has
 * run, so the dial is issued and the returned {@link ConnectingTransport} defers
 * {@code setListener}/{@code send} until the socket resolves — which happens inside the harness's
 * {@code await(send)}. One real connection per exchange, 30 of them per replay, which a transport of
 * this shape does not notice.
 * <p>
 * <b>FR-071 — the raised transport tier, and why {@code skippedVectors()} is empty.</b> The
 * {@code envelope-too-large} vector's request is {@code JsonRpcLimits.MAX_BODY_SIZE + 1} bytes, and
 * the two-tier rule (contract tcp-framing.md §2) is that with the tiers equal the transport tier wins:
 * the framing decoder would refuse the line mid-accumulation and the connection would die with a
 * framing violation instead of the vector's {@code -32001} answer. The side that <b>receives</b> the
 * document therefore sets its tier strictly above the envelope tier — here the acceptor, at
 * {@link #RAISED_TIER} — so the document reaches the decoder and the envelope answers. Nothing is
 * skipped, and {@link #skippedVectors()} returning empty is the assertion that says so.
 * <p>
 * <b>FR-072 — {@link #awaitDelivery()}.</b> Under this design {@code await(send)} has already run the
 * loop to quiescence (the answering side closed the connection), so the override is a defensive second
 * drive — a no-op when idle, and the load-bearing pump if a future change ever completed {@code send}'s
 * promise before the response was processed. The quiescence invariant of D8 guarantees it never blocks.
 * <p>
 * <b>FR-073 — {@link #createReorderableTransport(JsonRpcDispatcher)}.</b> The reordered-correlation
 * test <b>runs</b> rather than {@code assumeTrue}-skipping, over the in-memory {@link HoldingTransport}
 * double (D9): the harness asserts {@code heldCount() == 3} synchronously, before the loop has run, and
 * no socket-backed transport can satisfy that. Real socket-level reordering is asserted separately, by
 * {@code JsonRpcTcpRoundTripTest}'s concurrent calls.
 */
public final class JsonRpcTcpConformanceTest extends AbstractTransportConformanceTest {
	// the harness's @ClassRule EventloopRule + ByteBufRule + ActivePromisesRule are inherited

	/**
	 * The receiving side's transport tier, strictly above the 1 mb {@link JsonRpcLimits#MAX_BODY_SIZE}
	 * envelope tier so {@code envelope-too-large} reaches the decoder (FR-071). 2 mb matches the
	 * WebSocket subject's precedent.
	 */
	private static final MemSize RAISED_TIER = MemSize.megabytes(2);

	private final List<ConformanceAcceptor> acceptors = new ArrayList<>();

	@Override
	protected JsonRpcTransport createTransport(JsonRpcDispatcher peer) {
		NioReactor reactor = Reactor.getCurrentReactor();
		// the acceptor RECEIVES every vector's request, envelope-too-large included, hence the raised tier
		ConformanceAcceptor acceptor = ConformanceAcceptor.answering(reactor, peer, RAISED_TIER);
		acceptors.add(acceptor);
		// lazy connect: resolves inside the harness's await(send) loop run (D8)
		return new ConnectingTransport(JsonRpcTcpTransport.connect(reactor, acceptor.boundAddress()));
	}

	@After
	public void closeAcceptors() {                                 // the harness closes only the transport
		for (ConformanceAcceptor acceptor : acceptors) acceptor.close();
		acceptors.clear();
		((Eventloop) Reactor.getCurrentReactor()).run();           // process the close tasks
	}

	@Override
	protected void awaitDelivery() {
		// D8: await(send) already ran the loop to quiescence (close-after-answer closed the connection);
		// this is the FR-072-mandated second drive — a no-op when idle. Never blocks.
		((Eventloop) Reactor.getCurrentReactor()).run();
	}

	@Override
	protected Set<String> skippedVectors() {
		// FR-071: EMPTY. The acceptor's transport tier is raised to 2 mb, strictly above the 1 mb
		// JsonRpcLimits.MAX_BODY_SIZE envelope tier, so envelope-too-large's 1,048,577-byte request is
		// framed, reaches the decoder and answers -32001 instead of closing the connection as a framing
		// violation. Every one of the 30 vectors replays over a real socket (SC-002).
		return Set.of();
	}

	@Override
	protected @Nullable ReorderableTransport createReorderableTransport(JsonRpcDispatcher peer) {
		return new ReorderableTcpDouble(peer);
	}

	/**
	 * The harness's {@code ReorderableTransport} is a {@code protected} nested interface, nameable only
	 * from a subclass — so the shared {@link HoldingTransport} fixture carries the behaviour and this
	 * one-line subclass declares it (FR-073).
	 */
	private static final class ReorderableTcpDouble extends HoldingTransport implements ReorderableTransport {
		private ReorderableTcpDouble(JsonRpcDispatcher peer) {
			super(peer);
		}
	}
}
