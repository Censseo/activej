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

import io.activej.jsonrpc.ConformanceVectors;
import io.activej.jsonrpc.ConformanceVectors.Vector;
import io.activej.jsonrpc.service.fixtures.FailingApiImpl;
import io.activej.jsonrpc.transport.JsonRpcTransport;
import io.activej.promise.Promise;
import io.activej.reactor.Reactor;
import io.activej.test.rules.ActivePromisesRule;
import io.activej.test.rules.ByteBufRule;
import io.activej.test.rules.EventloopRule;
import org.jetbrains.annotations.Nullable;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static io.activej.jsonrpc.ConformanceJson.assertJsonEquals;
import static io.activej.jsonrpc.ConformanceJson.parseJson;
import static io.activej.jsonrpc.service.fixtures.FailingApiImpl.APPLICATION_ERROR_JSON;
import static io.activej.jsonrpc.service.fixtures.FailingApiImpl.SECRET;
import static io.activej.promise.TestUtils.await;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeTrue;

/**
 * The reverse-direction conformance harness (research D10, FR-074…FR-077): the same 30 vectors of
 * feature 01, the same {@code ConformanceJson} comparison rules and the same non-vector assertions,
 * replayed with the <b>server as caller</b>. A transport that carries the suite in only one direction
 * is not the bidirectional transport the feature promises (SC-001/002/003).
 *
 * <pre>{@code
 * public class MyBidirectionalConformanceTest extends AbstractBidirectionalTransportConformanceTest {
 *     @Override
 *     protected JsonRpcTransport createServerTransport(JsonRpcDispatcher clientPeer) {
 *         // start a server, connect a real client whose peer handler is clientPeer, and return
 *         // the server-side transport — the harness then plays the server side of the exchange
 *         ...
 *     }
 * }
 * }</pre>
 *
 * <h2>What the subject supplies</h2>
 * <table border="1">
 *     <caption>the extension points</caption>
 *     <tr><th>Member</th><th>Obligation</th></tr>
 *     <tr><td>{@link #createServerTransport(JsonRpcDispatcher)}</td>
 *         <td><b>required</b> — the <b>server-side</b> transport of a connection whose client side
 *         answers inbound documents through {@code clientPeer} (the harness's dispatcher, wired as
 *         the client's peer handler — the server→client seam of FR-076). The harness sends the
 *         vector's request <i>out through the returned transport</i>, as the server would, and reads
 *         {@code clientPeer}'s answer at the listener it registers.</td></tr>
 *     <tr><td>{@link #skippedVectors()}</td>
 *         <td>optional — vectors this transport cannot carry, <b>named individually</b>, checked
 *         against the loaded set so a stale entry fails rather than hides a vector (FR-092b).</td></tr>
 *     <tr><td>{@link #awaitDelivery()}</td>
 *         <td>optional — for a transport that still has work outstanding once the reactor is idle.</td></tr>
 *     <tr><td>{@link #createReorderableTransport(JsonRpcDispatcher)}</td>
 *         <td>optional — a double that can hold and reorder inbound documents, with the roles swapped
 *         (FR-076): the harness's <b>server</b> is the caller, {@code clientPeer} is the answering
 *         client dispatcher.</td></tr>
 * </table>
 * The dispatcher, the service interface behind it and the comparison rules are the harness's, exactly
 * as in the forward harness: they are deliberately not overridable.
 *
 * <h2>How the reversal works</h2>
 * The forward harness's "harness holds the client end, the subject supplies a transport joined to a
 * peer" contract is baked into {@code exchange()} and feature 013's subclass (FR-075), so this is a
 * new harness rather than a generalisation. The roles flip: the subject wires a client whose peer
 * handler is the harness's dispatcher ({@code clientPeer}) and hands back the <i>server-side</i>
 * transport; the harness then plays the server — vector request out through the server transport,
 * answer captured at its listener. The same vector set, the same rules, the reversed non-vector
 * assertions ({@code -32602}, application {@code data}, {@code -32603} non-disclosure) and the reorder
 * test with roles swapped (D9: the same in-memory holding double).
 *
 * <h2>The fixtures are shared, not duplicated</h2>
 * The service interface, its implementation, {@code Data}, the codec factory and the dispatcher
 * construction live in {@link ConformanceFixtures}; {@code AbstractTransportConformanceTest} keeps its
 * own private copies and must remain byte-identical (SC-007).
 */
public abstract class AbstractBidirectionalTransportConformanceTest {
	@ClassRule
	public static final EventloopRule eventloopRule = new EventloopRule();

	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	@ClassRule
	public static final ActivePromisesRule activePromisesRule = new ActivePromisesRule();

	/** The vectors that must be present for this suite to mean what its name says (FR-093). */
	private static final Set<String> REQUIRED_COVERAGE = Set.of(
		"positional-params-subtract",        // a positional call
		"named-params-subtract",             // a named call
		"notification-update",               // a notification producing nothing
		"method-not-found",                  // an unknown method producing -32601
		"batch-mixed",                       // a batch with mixed outcomes
		"batch-all-notifications");          // a batch of only notifications producing nothing

	/** Every notification failure the client dispatcher reported, so that none of them passes unnoticed (FR-077). */
	private List<String> notificationFailures;

	@Before
	public void setUpConformance() {
		notificationFailures = new ArrayList<>();
	}

	// ---------------------------------------------------------------------------------------------------
	// The extension points.
	// ---------------------------------------------------------------------------------------------------

	/**
	 * Creates the <b>server-side</b> transport of a connection whose client side answers every inbound
	 * document through {@code clientPeer} — whatever "a connection whose client dispatches through
	 * {@code clientPeer}" means for this subject: an in-memory hand-off, a socket pair, a client
	 * {@code JsonRpcClient} wired with {@code withPeerHandler(clientPeer)} pointing at a server this
	 * method starts.
	 * <p>
	 * The harness holds the returned transport's <b>server</b> end and plays the <i>server as
	 * caller</i>: it calls {@link JsonRpcTransport#send} with a vector's request document and expects
	 * {@code clientPeer}'s answer to arrive at the listener the harness registers immediately
	 * afterwards. The transport is closed after every exchange.
	 */
	protected abstract JsonRpcTransport createServerTransport(JsonRpcDispatcher clientPeer);

	/**
	 * Vectors this transport cannot carry, <b>by name</b>. Empty by default, and every name is checked
	 * against the loaded vector set — a stale skip is a failure, never a silent omission (FR-092b).
	 * A skip is a statement about the <i>transport</i> and belongs in a comment next to the name.
	 */
	protected Set<String> skippedVectors() {
		return Set.of();
	}

	/**
	 * Waits until this transport has delivered everything it is going to for the document just sent.
	 * The default does nothing, which is correct for every transport that has finished its work by the
	 * time the reactor next goes idle — {@code io.activej.promise.TestUtils.await} on the {@code send}
	 * promise has already run the eventloop to quiescence by the time this is called. A transport that
	 * waits on something the eventloop does not own overrides this, exactly as feature 013's subject
	 * does (FR-072).
	 */
	protected void awaitDelivery() {
		// nothing: awaiting the send promise has already run the eventloop to quiescence
	}

	/**
	 * A double of this transport that can hold inbound documents and release them in an order the test
	 * chooses, or {@code null} when this subject cannot be told to reorder — a real socket cannot.
	 * When it is {@code null}, {@link #responsesAreCorrelatedByIdAloneWhenTheyArriveReordered()} is
	 * skipped as a JUnit assumption, which reports it as skipped rather than as passed.
	 */
	protected @Nullable ReorderableTransport createReorderableTransport(JsonRpcDispatcher clientPeer) {
		return null;
	}

	/** A transport whose inbound delivery order the test controls (FR-076, roles swapped). */
	protected interface ReorderableTransport {
		JsonRpcTransport transport();

		/** Inbound documents accumulate instead of reaching the listener. */
		void startHolding();

		int heldCount();

		/** Delivers everything held, <b>last held first</b>. */
		void releaseInReverseOrder();
	}

	// ---------------------------------------------------------------------------------------------------
	// The replay (FR-092, FR-092a, FR-092b).
	// ---------------------------------------------------------------------------------------------------

	@Test
	public void theSkipSetNamesOnlyVectorsThatExist() {
		Set<String> names = new HashSet<>();
		for (Vector vector : ConformanceVectors.loadAll()) names.add(vector.name());

		for (String skipped : skippedVectors()) {
			assertTrue("skippedVectors() names '" + skipped + "', which is not a conformance vector. A stale " +
					   "skip hides the vector it was renamed from, which is exactly what FR-092b forbids",
				names.contains(skipped));
		}
		assertTrue("the vectors must cover at least FR-093's minimum: " + REQUIRED_COVERAGE,
			names.containsAll(REQUIRED_COVERAGE));
		for (String required : REQUIRED_COVERAGE) {
			assertFalse(required + " is part of FR-093's minimum coverage and may not be skipped",
				skippedVectors().contains(required));
		}
	}

	@Test
	public void everyVectorReplaysEndToEndThroughTheTransport() {
		List<Vector> vectors = ConformanceVectors.loadAll();
		assertFalse("the vector set must not be empty", vectors.isEmpty());
		Set<String> skipped = skippedVectors();

		List<String> failures = new ArrayList<>();
		List<String> replayed = new ArrayList<>();

		for (Vector vector : vectors) {
			if (skipped.contains(vector.name())) continue;
			try {
				replay(vector);
				replayed.add(vector.name());
			} catch (AssertionError | RuntimeException e) {
				failures.add(vector.name() + " — " + vector.description() + "\n\t\t" + e.getMessage());
			}
		}

		if (!failures.isEmpty()) {
			fail(failures.size() + " of " + (failures.size() + replayed.size()) + " vectors failed through " +
				 getClass().getSimpleName() + ":\n\t" + String.join("\n\t", failures));
		}
		assertEquals("every vector not explicitly skipped must have been replayed",
			vectors.size() - skipped.size(), replayed.size());
		assertTrue("no notification may have failed while replaying: " + notificationFailures,
			notificationFailures.isEmpty());
	}

	/** One vector, end to end: the server's request goes out through the transport and the answer comes back in. */
	private void replay(Vector vector) {
		List<byte[]> inbound = exchange(vector.request().getBytes(UTF_8));

		if (vector.expectsNoResponse()) {
			// rule 3: no document AT ALL, which is neither "null" nor "[]" — both of those are documents
			assertEquals("expected no response document at all, got " + render(inbound), 0, inbound.size());
			return;
		}

		assertEquals("expected exactly one response document, got " + render(inbound), 1, inbound.size());
		byte[] response = inbound.get(0);
		assertTrue("expected a response document, got zero bytes — which is how 'no response' is spelled",
			response.length > 0);
		String actual = new String(response, UTF_8);

		// rule 1: raw bytes only where the vector demands them
		if (vector.exactBytes()) {
			assertEquals(vector.response(), actual);
			return;
		}
		assertJsonEquals(parseJson(vector.response()), parseJson(actual));
	}

	// ---------------------------------------------------------------------------------------------------
	// FR-093's two members that no vector carries: -32602, and an application error with data.
	// Reversed: these travel server→client, answered by the harness's clientPeer dispatcher.
	// ---------------------------------------------------------------------------------------------------

	@Test
	public void invalidParamsAre32602ThroughTheTransport() {
		// no vector covers -32602: it is the dispatcher's to raise, and feature 01 had no dispatcher
		List<byte[]> inbound = exchange(utf8(
			"{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"subtract\",\"params\":[\"x\",\"y\"]}"));

		assertEquals(render(inbound), 1, inbound.size());
		assertJsonEquals(
			parseJson("{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":{\"code\":-32602,\"message\":\"Invalid params\"}}"),
			parseJson(new String(inbound.get(0), UTF_8)));
	}

	@Test
	public void anApplicationErrorRoundTripsItsCodeMessageAndData() {
		List<byte[]> inbound = exchange(utf8(
			"{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"fail.failedWithJsonRpc\"}"));

		assertEquals(render(inbound), 1, inbound.size());
		String response = new String(inbound.get(0), UTF_8);
		assertJsonEquals(
			parseJson("{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":" + APPLICATION_ERROR_JSON + '}'),
			parseJson(response));
		assertFalse("no transport may put the local exception's message on the wire", response.contains(SECRET));
	}

	@Test
	public void anInternalFailureIs32603WithNothingDerivedFromTheException() {
		List<byte[]> inbound = exchange(utf8("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"fail.thrown\"}"));

		assertEquals(render(inbound), 1, inbound.size());
		String response = new String(inbound.get(0), UTF_8);
		assertJsonEquals(
			parseJson("{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":{\"code\":-32603,\"message\":\"Internal error\"}}"),
			parseJson(response));
		assertFalse(response, response.contains(SECRET));
		assertFalse("no data member may be derived from an exception", response.contains("\"data\""));
	}

	// ---------------------------------------------------------------------------------------------------
	// FR-076 — correlation is by id alone, roles swapped: the harness is the server caller, the
	// subject's reorderable double answers through the harness's clientPeer dispatcher.
	// ---------------------------------------------------------------------------------------------------

	@Test
	public void responsesAreCorrelatedByIdAloneWhenTheyArriveReordered() {
		JsonRpcDispatcher clientPeer = dispatcher();
		ReorderableTransport reorderable = createReorderableTransport(clientPeer);
		// reported as skipped, not as passed: a subject that cannot reorder has not asserted FR-076
		assumeTrue("this transport cannot be told to reorder its inbound documents",
			reorderable != null);

		JsonRpcClient client = JsonRpcClient.builder(Reactor.getCurrentReactor(), reorderable.transport())
			.withCodecFactory(ConformanceFixtures.codecFactory())
			.build();
		ConformanceFixtures.ConformanceApi api = client.proxy(ConformanceFixtures.ConformanceApi.class);

		reorderable.startHolding();
		Promise<Integer> first = api.subtract(42, 23);      // 19
		Promise<Integer> second = api.subtract(23, 42);     // -19
		Promise<Integer> third = api.subtract(100, 1);      // 99

		assertEquals("three answers are waiting to be delivered", 3, reorderable.heldCount());
		assertEquals(3, client.inFlightCount());
		assertFalse(first.isComplete());
		assertFalse(second.isComplete());
		assertFalse(third.isComplete());

		reorderable.releaseInReverseOrder();

		// nothing about the order they arrived in reaches the caller: the id did all the work
		assertEquals(Integer.valueOf(19), await(first));
		assertEquals(Integer.valueOf(-19), await(second));
		assertEquals(Integer.valueOf(99), await(third));
		assertEquals("every entry left the correlation table", 0, client.inFlightCount());

		client.close();
	}

	// ---------------------------------------------------------------------------------------------------
	// The exchange.
	// ---------------------------------------------------------------------------------------------------

	/**
	 * One document out — through the subject's <b>server-side</b> transport, the server playing the
	 * caller — and everything the transport delivered back in. A fresh dispatcher and a fresh
	 * transport, so no vector inherits another's state.
	 */
	private List<byte[]> exchange(byte[] document) {
		List<byte[]> inbound = new ArrayList<>();
		JsonRpcTransport transport = createServerTransport(dispatcher());
		transport.setListener(new JsonRpcTransport.Listener() {
			@Override
			public void onDocument(byte[] delivered) {
				assertNotNull("a transport never delivers a null document", delivered);
				assertTrue("obligation 3: a transport never delivers a zero-length document — 'no response' " +
						   "is the absence of a call, not an empty one", delivered.length > 0);
				inbound.add(delivered);
			}

			@Override
			public void onClosed(@Nullable Exception e) {
				// every exchange closes its own transport, so the close is this harness's own doing
			}
		});

		try {
			await(transport.send(document));
			awaitDelivery();
		} finally {
			transport.close();
		}
		return inbound;
	}

	// ---------------------------------------------------------------------------------------------------
	// The peer every subject answers with: the vectors' service, and nothing more.
	// ---------------------------------------------------------------------------------------------------

	/**
	 * The {@code clientPeer}: the dispatcher the subject wires as the client side's peer handler, so
	 * the vectors are answered by exactly the methods §7's examples name — and by {@link
	 * ConformanceFixtures.FailingApi FailingApi}'s failure shapes for the non-vector tests.
	 */
	private JsonRpcDispatcher dispatcher() {
		return ConformanceFixtures.dispatcher(Reactor.getCurrentReactor(), notificationFailures);
	}

	// ---------------------------------------------------------------------------------------------------
	// Local helpers. Rules 1 and 2 of FR-092a live in ConformanceJson, next to the loader of the same
	// files: they are the vector FORMAT's rules.
	// ---------------------------------------------------------------------------------------------------

	private static byte[] utf8(String document) {
		return document.getBytes(UTF_8);
	}

	/** What the transport delivered, for a failure message — the whole point of which is to be readable. */
	private static String render(List<byte[]> documents) {
		List<String> text = new ArrayList<>(documents.size());
		for (byte[] document : documents) text.add('<' + new String(document, UTF_8) + '>');
		return documents.isEmpty() ? "nothing at all" : String.join(", ", text);
	}
}
