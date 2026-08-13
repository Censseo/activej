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

package io.activej.jsonrpc.service.fixtures;

import io.activej.jsonrpc.transport.JsonRpcTransport;
import io.activej.promise.Promise;
import io.activej.promise.SettablePromise;
import io.activej.test.ExpectedException;
import io.activej.test.rules.EventloopRule;
import org.jetbrains.annotations.Nullable;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Pins the behavioural contract of {@link InMemoryTransport} itself.
 * <p>
 * The double is the vehicle for every acceptance scenario of this feature and the first subject of the
 * conformance harness, so its own behaviour has to be nailed down before anything is asserted <i>through</i>
 * it: an assertion that fails because the double misbehaved is worse than no assertion at all.
 * <p>
 * The properties asserted here are the ones {@link JsonRpcTransport}'s implementor obligations name —
 * delivery, "never a zero-length document", idempotent close with exactly one {@code onClosed} — plus the
 * deterministic reorder mode FR-094 needs.
 */
public class InMemoryTransportTest {
	@ClassRule
	public static final EventloopRule eventloopRule = new EventloopRule();

	private static final byte[] REQUEST_1 = doc("{\"jsonrpc\":\"2.0\",\"method\":\"a\",\"id\":1}");
	private static final byte[] REQUEST_2 = doc("{\"jsonrpc\":\"2.0\",\"method\":\"b\",\"id\":2}");
	private static final byte[] REQUEST_3 = doc("{\"jsonrpc\":\"2.0\",\"method\":\"c\",\"id\":3}");

	private RecordingListener listener;

	@Before
	public void setUp() {
		listener = new RecordingListener();
	}

	// region delivery

	@Test
	public void sendDeliversTheAnswerToTheListener() {
		InMemoryTransport transport = echoing();
		transport.setListener(listener);

		Promise<Void> sent = transport.send(REQUEST_1);

		assertTrue("send's promise completes when the document is written", sent.isResult());
		assertEquals(List.of(asString(REQUEST_1)), listener.documents());
	}

	@Test
	public void thePeerSeesExactlyTheDocumentThatWasSent() {
		List<String> seen = new ArrayList<>();
		InMemoryTransport transport = InMemoryTransport.create(document -> {
			seen.add(asString(document));
			return Promise.of(EMPTY);
		});
		transport.setListener(listener);

		transport.send(REQUEST_1);
		transport.send(REQUEST_2);

		assertEquals(List.of(asString(REQUEST_1), asString(REQUEST_2)), seen);
		assertEquals(List.of(asString(REQUEST_1), asString(REQUEST_2)), transport.sentText());
	}

	@Test
	public void aZeroLengthAnswerIsNeverDelivered() {
		// obligation 3: "no response" is the absence of a call, not an empty one. A dispatcher answers a
		// notification with a zero-length array, and that must not reach a listener
		InMemoryTransport transport = InMemoryTransport.create(document -> Promise.of(EMPTY));
		transport.setListener(listener);

		transport.send(REQUEST_1);

		assertEquals(List.of(), listener.documents());
		assertEquals(0, transport.heldCount());
	}

	@Test
	public void aNullAnswerIsNeverDelivered() {
		InMemoryTransport transport = InMemoryTransport.create(document -> Promise.of(null));
		transport.setListener(listener);

		transport.send(REQUEST_1);

		assertEquals(List.of(), listener.documents());
	}

	@Test
	public void sendCompletesBeforeTheAnswerArrives() {
		// obligation 4: send's promise means WRITTEN, not ANSWERED
		SettablePromise<byte[]> answer = new SettablePromise<>();
		InMemoryTransport transport = InMemoryTransport.create(document -> answer);
		transport.setListener(listener);

		Promise<Void> sent = transport.send(REQUEST_1);

		assertTrue("the write completed even though nothing has answered yet", sent.isResult());
		assertEquals(List.of(), listener.documents());

		answer.set(doc("{\"jsonrpc\":\"2.0\",\"result\":1,\"id\":1}"));
		assertEquals(List.of("{\"jsonrpc\":\"2.0\",\"result\":1,\"id\":1}"), listener.documents());
	}

	@Test
	public void aDocumentPushedByThePeerReachesTheListener() {
		// the SPI is duplex: the far side may speak first (feature 06's server -> client direction)
		InMemoryTransport transport = silent();
		transport.setListener(listener);

		transport.deliverFromPeer(doc("{\"jsonrpc\":\"2.0\",\"method\":\"ping\"}"));

		assertEquals(List.of("{\"jsonrpc\":\"2.0\",\"method\":\"ping\"}"), listener.documents());
	}

	@Test
	public void sendingWithNoListenerFails() {
		InMemoryTransport transport = echoing();
		try {
			transport.send(REQUEST_1);
			fail("a fixture must fail loudly rather than swallow a document nobody is listening for");
		} catch (IllegalStateException e) {
			assertTrue(e.getMessage(), e.getMessage().contains("listener"));
		}
	}

	// endregion

	// region reorder mode

	@Test
	public void holdingSuspendsDeliveryWithoutLosingAnything() {
		InMemoryTransport transport = echoing();
		transport.setListener(listener);
		transport.startHolding();

		transport.send(REQUEST_1);
		transport.send(REQUEST_2);

		assertEquals("nothing is delivered while holding", List.of(), listener.documents());
		assertEquals(2, transport.heldCount());
		assertTrue(transport.isHolding());
	}

	@Test
	public void heldDocumentsAreDeliveredOutOfOrderOnDemand() {
		// FR-094: correlation is by id alone, so the double must be able to answer in an order no client
		// could have predicted — deterministically, not randomly
		InMemoryTransport transport = echoing();
		transport.setListener(listener);
		transport.startHolding();

		transport.send(REQUEST_1);
		transport.send(REQUEST_2);
		transport.send(REQUEST_3);

		transport.releaseInReverseOrder();

		assertEquals(List.of(asString(REQUEST_3), asString(REQUEST_2), asString(REQUEST_1)), listener.documents());
		assertEquals(0, transport.heldCount());
	}

	@Test
	public void aSingleHeldDocumentCanBeReleasedByIndex() {
		InMemoryTransport transport = echoing();
		transport.setListener(listener);
		transport.startHolding();

		transport.send(REQUEST_1);
		transport.send(REQUEST_2);
		transport.send(REQUEST_3);

		transport.release(1);
		assertEquals(List.of(asString(REQUEST_2)), listener.documents());
		assertEquals(2, transport.heldCount());

		transport.releaseInOrder();
		assertEquals(List.of(asString(REQUEST_2), asString(REQUEST_1), asString(REQUEST_3)), listener.documents());
		assertEquals(0, transport.heldCount());
	}

	@Test
	public void deliveryResumesAfterHoldingStops() {
		InMemoryTransport transport = echoing();
		transport.setListener(listener);
		transport.startHolding();
		transport.send(REQUEST_1);

		transport.stopHolding();
		transport.send(REQUEST_2);

		assertFalse(transport.isHolding());
		assertEquals("stopping the hold delivers nothing by itself — release is explicit",
			List.of(asString(REQUEST_2)), listener.documents());
		assertEquals(1, transport.heldCount());

		transport.releaseInOrder();
		assertEquals(List.of(asString(REQUEST_2), asString(REQUEST_1)), listener.documents());
	}

	@Test
	public void releasingAnEmptyQueueIsANoOp() {
		InMemoryTransport transport = echoing();
		transport.setListener(listener);

		transport.releaseInOrder();
		transport.releaseInReverseOrder();

		assertEquals(List.of(), listener.documents());
	}

	@Test
	public void releasingAnIndexOutOfRangeFails() {
		InMemoryTransport transport = echoing();
		transport.setListener(listener);
		transport.startHolding();
		transport.send(REQUEST_1);

		try {
			transport.release(1);
			fail("a fixture must not silently ignore a release nobody can satisfy");
		} catch (IndexOutOfBoundsException e) {
			// expected
		}
	}

	// endregion

	// region closing

	@Test
	public void closeIsIdempotentAndOnClosedFiresExactlyOnce() {
		InMemoryTransport transport = echoing();
		transport.setListener(listener);

		transport.close();
		transport.close();
		transport.closeEx(new ExpectedException("second"));

		assertEquals("onClosed fires exactly once no matter how often close is called", 1, listener.closes());
	}

	@Test
	public void closeExReportsItsCause() {
		ExpectedException cause = new ExpectedException("boom");
		InMemoryTransport transport = echoing();
		transport.setListener(listener);

		transport.closeEx(cause);

		assertEquals(1, listener.closes());
		assertSame(cause, listener.closeCause());
	}

	@Test
	public void aCleanPeerCloseReportsNoCause() {
		InMemoryTransport transport = echoing();
		transport.setListener(listener);

		transport.closeFromPeer(null);

		assertEquals(1, listener.closes());
		assertNull("a clean peer close carries no cause", listener.closeCause());
	}

	@Test
	public void sendAfterCloseFails() {
		ExpectedException cause = new ExpectedException("gone");
		InMemoryTransport transport = echoing();
		transport.setListener(listener);
		transport.closeEx(cause);

		Promise<Void> sent = transport.send(REQUEST_1);

		assertTrue(sent.isException());
		assertSame(cause, sent.getException());
		assertEquals("a closed transport writes nothing", List.of(), transport.sentDocuments());
	}

	@Test
	public void nothingIsDeliveredAfterClose() {
		// obligation 6's companion: onDocument is never called after onClosed
		SettablePromise<byte[]> answer = new SettablePromise<>();
		InMemoryTransport transport = InMemoryTransport.create(document -> answer);
		transport.setListener(listener);
		transport.send(REQUEST_1);
		transport.startHolding();
		transport.close();

		answer.set(REQUEST_1);
		transport.deliverFromPeer(REQUEST_2);
		transport.releaseInOrder();

		assertEquals(List.of(), listener.documents());
		assertEquals(0, transport.heldCount());
	}

	@Test
	public void heldDocumentsAreDiscardedOnClose() {
		InMemoryTransport transport = echoing();
		transport.setListener(listener);
		transport.startHolding();
		transport.send(REQUEST_1);
		assertEquals(1, transport.heldCount());

		transport.close();

		assertEquals("a closed transport delivers nothing it was holding", 0, transport.heldCount());
		assertEquals(List.of(), listener.documents());
	}

	@Test
	public void isClosedReflectsTheState() {
		InMemoryTransport transport = echoing();
		transport.setListener(listener);

		assertFalse(transport.isClosed());
		transport.close();
		assertTrue(transport.isClosed());
	}

	// endregion

	private static final byte[] EMPTY = {};

	private static InMemoryTransport echoing() {
		return InMemoryTransport.create(Promise::of);
	}

	private static InMemoryTransport silent() {
		return InMemoryTransport.create(document -> Promise.of(EMPTY));
	}

	private static byte[] doc(String json) {
		return json.getBytes(StandardCharsets.UTF_8);
	}

	private static String asString(byte[] document) {
		return new String(document, StandardCharsets.UTF_8);
	}

	private static final class RecordingListener implements JsonRpcTransport.Listener {
		private final List<String> documents = new ArrayList<>();
		private int closes;
		private @Nullable Exception closeCause;

		@Override
		public void onDocument(byte[] document) {
			documents.add(asString(document));
		}

		@Override
		public void onClosed(@Nullable Exception e) {
			closes++;
			closeCause = e;
		}

		List<String> documents() {return documents;}

		int closes() {return closes;}

		@Nullable Exception closeCause() {return closeCause;}
	}
}
