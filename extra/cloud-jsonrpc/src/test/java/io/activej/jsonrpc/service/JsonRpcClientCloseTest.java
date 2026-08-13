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

import io.activej.async.exception.AsyncCloseException;
import io.activej.jsonrpc.service.fixtures.InMemoryTransport;
import io.activej.jsonrpc.service.fixtures.User;
import io.activej.jsonrpc.service.fixtures.UserApi;
import io.activej.jsonrpc.service.fixtures.UserApiImpl;
import io.activej.promise.Promise;
import io.activej.reactor.Reactor;
import io.activej.test.ExpectedException;
import io.activej.test.rules.EventloopRule;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * Closing, from either origin, through the one removal path (FR-078, FR-078a, FR-078b).
 * <p>
 * Every test holds its inbound documents first, so the calls under test are genuinely in flight rather than
 * answered by the synchronous dispatcher before the close is reached.
 */
public class JsonRpcClientCloseTest {
	@ClassRule
	public static final EventloopRule eventloopRule = new EventloopRule();

	private InMemoryTransport transport;
	private JsonRpcClient client;
	private UserApi api;
	private List<Exception> failures;

	@Before
	public void setUp() {
		JsonRpcDispatcher dispatcher = JsonRpcDispatcher.builder(Reactor.getCurrentReactor())
			.withService(UserApi.class, new UserApiImpl())
			.build();
		transport = InMemoryTransport.create(dispatcher::dispatch);
		failures = new ArrayList<>();
		client = JsonRpcClient.builder(Reactor.getCurrentReactor(), transport)
			.withFailureHandler(failures::add)
			.build();
		api = client.proxy(UserApi.class);
		transport.startHolding();
	}

	@Test
	public void localCloseCompletesEveryPendingCallWithAsyncCloseException() {
		Promise<User> first = api.getUser(1);
		Promise<User> second = api.getUser(2);
		assertEquals(2, client.inFlightCount());

		client.close();

		assertTrue(first.getException() instanceof AsyncCloseException);
		assertTrue(second.getException() instanceof AsyncCloseException);
		assertEquals(0, client.inFlightCount());
	}

	@Test
	public void localCloseClosesTheTransport() {
		client.close();

		assertTrue("the client owns the transport's lifetime once it is built (FR-078)", transport.isClosed());
	}

	@Test
	public void closeIsIdempotent() {
		Promise<User> pending = api.getUser(1);

		client.close();
		client.close();
		client.closeEx(new ExpectedException("a second cause must not reach anything"));

		assertTrue(pending.getException() instanceof AsyncCloseException);
		assertEquals(0, client.inFlightCount());
	}

	@Test
	public void closeExCompletesPendingCallsWithTheGivenCause() {
		ExpectedException cause = new ExpectedException("shutting down");
		Promise<User> pending = api.getUser(1);

		client.closeEx(cause);

		assertSame(cause, pending.getException());
	}

	@Test
	public void aPeerCloseCompletesPendingCallsWithTheTransportsCause() {
		ExpectedException cause = new ExpectedException("the connection dropped");
		Promise<User> first = api.getUser(1);
		Promise<User> second = api.getUser(2);

		transport.closeFromPeer(cause);

		assertSame("FR-078a: the transport's cause, not a substitute", cause, first.getException());
		assertSame(cause, second.getException());
		assertEquals(0, client.inFlightCount());
	}

	@Test
	public void aCleanPeerCloseCompletesPendingCallsWithAsyncCloseException() {
		Promise<User> pending = api.getUser(1);

		transport.closeFromPeer(null);

		assertTrue("FR-078a: with AsyncCloseException when the peer supplied no cause",
			pending.getException() instanceof AsyncCloseException);
		assertEquals(0, client.inFlightCount());
	}

	@Test
	public void aCallAfterLocalCloseFailsImmediatelySendingNothing() {
		client.close();
		int sentBeforeTheCall = transport.sentDocuments().size();

		Promise<User> afterClose = api.getUser(1);

		assertTrue(afterClose.isException());
		assertTrue(afterClose.getException() instanceof AsyncCloseException);
		assertEquals("FR-078b: nothing goes on the wire after a close",
			sentBeforeTheCall, transport.sentDocuments().size());
		assertEquals("FR-078b: and nothing is recorded", 0, client.inFlightCount());
	}

	@Test
	public void aCallAfterPeerCloseFailsImmediatelySendingNothing() {
		ExpectedException cause = new ExpectedException("the connection dropped");
		transport.closeFromPeer(cause);
		int sentBeforeTheCall = transport.sentDocuments().size();

		Promise<User> afterClose = api.getUser(1);

		assertTrue(afterClose.isException());
		assertSame(cause, afterClose.getException());
		assertEquals(sentBeforeTheCall, transport.sentDocuments().size());
		assertEquals(0, client.inFlightCount());
	}

	@Test
	public void aNotificationAfterCloseFailsWithoutSendingAnything() {
		client.close();
		int sentBeforeTheCall = transport.sentDocuments().size();

		api.touch(1);

		assertEquals(sentBeforeTheCall, transport.sentDocuments().size());
		assertEquals("a void notification routes its failure to the failure handler (FR-071)", 1, failures.size());
		assertTrue(failures.get(0) instanceof AsyncCloseException);
	}
}
