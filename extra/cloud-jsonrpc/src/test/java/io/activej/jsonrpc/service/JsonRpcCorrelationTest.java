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
import io.activej.async.exception.AsyncCloseException;
import io.activej.common.exception.MalformedDataException;
import io.activej.json.JsonCodec;
import io.activej.json.JsonCodecFactory;
import io.activej.jsonrpc.JsonRpcException;
import io.activej.jsonrpc.service.fixtures.User;
import io.activej.jsonrpc.service.fixtures.UserApi;
import io.activej.jsonrpc.transport.JsonRpcTransport;
import io.activej.promise.Promise;
import io.activej.reactor.Reactor;
import io.activej.test.ExpectedException;
import io.activej.test.rules.EventloopRule;
import org.jetbrains.annotations.Nullable;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * The correlation table of {@link JsonRpcClient}, asserted through the only surface that publishes it —
 * {@link JsonRpcClient#inFlightCount()} — plus the documents the transport received (FR-065…FR-071).
 *
 * <h2>Why a transport of its own rather than the in-memory double</h2>
 * {@code InMemoryTransport} joins a client to a peer that answers; three of the six removal triggers here
 * have no peer at all. A {@link ProgrammableTransport} can fail a {@code send}, deliver a document the peer
 * never sent, and deliver one <b>after</b> a close — the last of which the in-memory double deliberately
 * refuses to do, since a real transport does not deliver after {@code onClosed} (obligation 6). The client's
 * own guard is what is under test there, so the double must not stand in front of it.
 */
public class JsonRpcCorrelationTest {
	@ClassRule
	public static final EventloopRule eventloopRule = new EventloopRule();

	private ProgrammableTransport transport;
	private JsonRpcClient client;
	private UserApi api;
	private List<Exception> failures;

	@Before
	public void setUp() {
		transport = new ProgrammableTransport();
		failures = new ArrayList<>();
		client = JsonRpcClient.builder(Reactor.getCurrentReactor(), transport)
			.withCodecFactory(codecFactory())
			.withFailureHandler(failures::add)
			.build();
		api = client.proxy(UserApi.class);
	}

	// ---------------------------------------------------------------------------------------------------
	// T037 — identifiers and the table's key.
	// ---------------------------------------------------------------------------------------------------

	@Test
	public void identifiersAreNumbersFromAMonotonicCounterStartingAtOne() {
		api.getUser(1);
		api.getUser(2);
		api.getUser(3);

		assertEquals(
			List.of(
				"{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"user.get\",\"params\":[1]}",
				"{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"user.get\",\"params\":[2]}",
				"{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"user.get\",\"params\":[3]}"),
			transport.sentText());
		assertEquals(3, client.inFlightCount());
	}

	@Test
	public void aStringIdAndANumericIdAreDistinctTableKeys() {
		// FR-066: the table is keyed by JsonRpcId, not by long — Str("1") is not Num(1)
		Promise<User> promise = api.getUser(42);

		transport.deliver("{\"jsonrpc\":\"2.0\",\"id\":\"1\",\"result\":{\"id\":1,\"name\":\"x\"}}");

		assertFalse("a string id must not answer a numeric one", promise.isComplete());
		assertEquals(1, client.inFlightCount());
		assertEquals(List.of(), failures);
	}

	@Test
	public void aNotificationCreatesNoEntry() {
		api.touch(42);

		assertEquals("a notification bypasses the table entirely (FR-071)", 0, client.inFlightCount());
		assertEquals(List.of("{\"jsonrpc\":\"2.0\",\"method\":\"user.touch\",\"params\":[42]}"),
			transport.sentText());
	}

	@Test
	public void inFlightCountTracksExactly() {
		assertEquals(0, client.inFlightCount());
		Promise<User> first = api.getUser(1);
		assertEquals(1, client.inFlightCount());
		api.getUser(2);
		assertEquals(2, client.inFlightCount());

		transport.deliver("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"id\":1,\"name\":\"one\"}}");

		assertEquals(1, client.inFlightCount());
		assertEquals(new User(1, "one"), first.getResult());
	}

	// ---------------------------------------------------------------------------------------------------
	// T038 — the single removal path: six triggers, each leaving the table empty.
	// ---------------------------------------------------------------------------------------------------

	@Test
	public void trigger1_aSuccessfulResponseRemovesTheEntry() {
		Promise<User> promise = api.getUser(42);

		transport.deliver("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"id\":42,\"name\":\"user-42\"}}");

		assertEquals(new User(42, "user-42"), promise.getResult());
		assertEquals(0, client.inFlightCount());
	}

	@Test
	public void trigger2_aRemoteErrorRemovesTheEntry() {
		Promise<User> promise = api.getUser(42);

		transport.deliver("{\"jsonrpc\":\"2.0\",\"id\":1," +
						  "\"error\":{\"code\":-32601,\"message\":\"Method not found\"}}");

		assertTrue(promise.isException());
		assertTrue(promise.getException() instanceof JsonRpcException);
		assertEquals(-32601, ((JsonRpcException) promise.getException()).getError().code());
		assertEquals(0, client.inFlightCount());
	}

	@Test
	public void trigger3_aLocalEncodeFailureLeavesNoEntryAndSendsNothing() {
		// FR-074: the arguments never render, so nothing is handed to the transport and nothing is recorded
		EncodeApi encodeApi = client.proxy(EncodeApi.class);

		Promise<String> promise = encodeApi.boom(new Unencodable());

		assertTrue(promise.isException());
		assertTrue(promise.getException().toString(), promise.getException() instanceof MalformedDataException);
		assertEquals(List.of(), transport.sentText());
		assertEquals(0, client.inFlightCount());
	}

	@Test
	public void trigger4_aTransportSendFailureRemovesTheEntry() {
		ExpectedException failure = new ExpectedException("the medium is gone");
		transport.failSendsWith(failure);

		Promise<User> promise = api.getUser(42);

		assertTrue(promise.isException());
		assertSame("the transport's own exception reaches the caller", failure, promise.getException());
		assertEquals("the document was attempted", 1, transport.sentText().size());
		assertEquals(0, client.inFlightCount());
	}

	@Test
	public void trigger5_localCloseRemovesEveryEntry() {
		Promise<User> first = api.getUser(1);
		Promise<User> second = api.getUser(2);

		client.close();

		assertTrue(first.getException() instanceof AsyncCloseException);
		assertTrue(second.getException() instanceof AsyncCloseException);
		assertEquals(0, client.inFlightCount());
	}

	@Test
	public void trigger6_peerCloseRemovesEveryEntry() {
		ExpectedException cause = new ExpectedException("the peer went away");
		Promise<User> first = api.getUser(1);
		Promise<User> second = api.getUser(2);

		transport.peerClosed(cause);

		assertSame(cause, first.getException());
		assertSame(cause, second.getException());
		assertEquals(0, client.inFlightCount());
	}

	@Test
	public void anEntryIsRemovedExactlyOnce() {
		Promise<User> promise = api.getUser(42);
		String response = "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"id\":42,\"name\":\"user-42\"}}";

		transport.deliver(response);
		// a second copy of the same response must not reach a promise that is already completed — which it
		// cannot, because the entry it would need is gone. A SettablePromise completed twice is an
		// AssertionError, so this test fails loudly if the removal path is not the only one
		transport.deliver(response);

		assertEquals(new User(42, "user-42"), promise.getResult());
		assertEquals(0, client.inFlightCount());
		assertEquals(List.of(), failures);
	}

	// ---------------------------------------------------------------------------------------------------
	// T039 — orphan handling (FR-070).
	// ---------------------------------------------------------------------------------------------------

	@Test
	public void aResponseWithAnUnknownIdIsIgnoredSilently() {
		transport.deliver("{\"jsonrpc\":\"2.0\",\"id\":99,\"result\":7}");

		assertEquals("an orphan response creates nothing", 0, client.inFlightCount());
		assertEquals("an orphan response is not a failure", List.of(), failures);
		assertEquals("an orphan response is not answered", List.of(), transport.sentText());
	}

	@Test
	public void aResponseArrivingAfterCloseIsIgnoredIdentically() {
		api.getUser(42);
		client.close();

		transport.deliver("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"id\":42,\"name\":\"user-42\"}}");

		assertEquals(0, client.inFlightCount());
		assertEquals(List.of(), failures);
	}

	// ---------------------------------------------------------------------------------------------------
	// T040 — removal precedes decoding (FR-069).
	// ---------------------------------------------------------------------------------------------------

	@Test
	public void anUndecodableResultStillEmptiesTheTable() {
		Promise<User> promise = api.getUser(42);

		transport.deliver("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":\"not-a-user\"}");

		assertTrue(promise.isException());
		assertTrue(promise.getException().toString(), promise.getException() instanceof MalformedDataException);
		assertEquals("the entry was taken before the value was constructed", 0, client.inFlightCount());
	}

	@Test
	public void aResponseCarryingNeitherResultNorErrorIs32004() {
		// FR-073: the envelope decoder classifies it; the client completes the call with that error
		Promise<User> promise = api.getUser(42);

		transport.deliver("{\"jsonrpc\":\"2.0\",\"id\":1}");

		assertTrue(promise.isException());
		assertTrue(promise.getException() instanceof JsonRpcException);
		assertEquals(-32004, ((JsonRpcException) promise.getException()).getError().code());
		assertEquals(0, client.inFlightCount());
	}

	// ---------------------------------------------------------------------------------------------------
	// Fixtures.
	// ---------------------------------------------------------------------------------------------------

	/** A value no codec can write — the local encode failure of FR-074, with no other moving part. */
	public static final class Unencodable {}

	@JsonRpcService("encode")
	public interface EncodeApi {
		@JsonRpcMethod("boom")
		Promise<String> boom(@JsonRpcParam("value") Unencodable value);
	}

	private static JsonCodecFactory codecFactory() {
		return JsonCodecFactory.defaultInstance().rebuild()
			.with(Unencodable.class, ctx -> new JsonCodec<Unencodable>() {
				@Override
				public void write(JsonWriter writer, Unencodable value) {
					throw new IllegalStateException("this value cannot be written");
				}

				@Override
				public Unencodable read(JsonReader<?> reader) {
					return new Unencodable();
				}
			})
			.build();
	}

	/**
	 * A transport whose every behaviour the test chooses: what {@code send} answers, what arrives, and when.
	 * Unlike a real transport it will deliver a document after a close, which is exactly what
	 * {@link #aResponseArrivingAfterCloseIsIgnoredIdentically} needs to observe.
	 */
	private static final class ProgrammableTransport implements JsonRpcTransport {
		private final List<byte[]> sent = new ArrayList<>();
		private @Nullable Listener listener;
		private @Nullable Exception sendFailure;
		private boolean closed;

		@Override
		public Promise<Void> send(byte[] document) {
			sent.add(document);
			return sendFailure == null ? Promise.complete() : Promise.ofException(sendFailure);
		}

		@Override
		public void setListener(Listener listener) {
			this.listener = listener;
		}

		@Override
		public void closeEx(Exception e) {
			closed = true;
		}

		void failSendsWith(Exception e) {
			sendFailure = e;
		}

		void deliver(String document) {
			//noinspection DataFlowIssue - the client registers its listener at build()
			listener.onDocument(document.getBytes(UTF_8));
		}

		void peerClosed(@Nullable Exception e) {
			//noinspection DataFlowIssue - as above
			listener.onClosed(e);
		}

		List<String> sentText() {
			return sent.stream().map(document -> new String(document, UTF_8)).toList();
		}

		@SuppressWarnings("unused")
		boolean isClosed() {
			return closed;
		}
	}
}
