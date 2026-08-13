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

import io.activej.jsonrpc.JsonRpcDecoder;
import io.activej.jsonrpc.JsonRpcInput;
import io.activej.jsonrpc.service.fixtures.InMemoryTransport;
import io.activej.jsonrpc.service.fixtures.UserApi;
import io.activej.jsonrpc.service.fixtures.UserApiImpl;
import io.activej.test.EventloopThread;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.fail;

/**
 * ERR-17 — every public method of {@link JsonRpcDispatcher} and {@link JsonRpcClient} (both
 * {@code AbstractReactive}, guarded by {@code checkInReactorThread(this)}) must refuse a call from any
 * thread other than the one that owns the reactor: {@link IllegalStateException}, never a silent
 * cross-thread mutation of correlation/dispatch state (WI-1, CLAUDE.md "Reactor / reactive components").
 * <p>
 * The reactor lives on its own dedicated daemon thread ({@link EventloopThread}), never the JUnit thread —
 * so simply calling a guarded method from a test method body already is the cross-thread call this checks
 * for. {@code eventloopThread} (the field {@code Reactor.inReactorThread()} compares against) is only ever
 * non-{@code null} while the loop is actively running, which {@code EventloopRule}'s single-thread setup
 * cannot reproduce — the loop and the JUnit thread are the same thread there, so the check passes trivially
 * regardless of which "thread" made the call. Hence this class does not use {@code EventloopRule}.
 * <p>
 * One test per {@code checkInReactorThread} call site: {@link JsonRpcDispatcher#dispatch(byte[])},
 * {@link JsonRpcDispatcher#dispatch(JsonRpcInput)}, {@link JsonRpcDispatcher#wireNames()},
 * {@link JsonRpcClient#proxy(Class)}, {@link JsonRpcClient#inFlightCount()},
 * {@link JsonRpcClient#closeEx(Exception)}, and a proxy method invocation itself (the
 * {@code ServiceInvocationHandler.Caller#checkReactorThread} seam inside {@link JsonRpcClient}).
 */
public class JsonRpcReactorThreadGuardTest {
	private EventloopThread loop;
	private JsonRpcDispatcher dispatcher;
	private JsonRpcClient client;
	private UserApi api;

	@Before
	public void setUp() {
		loop = EventloopThread.create("jsonrpc-guard-test");
		loop.submit(() -> {
			dispatcher = JsonRpcDispatcher.builder(loop.eventloop())
				.withService(UserApi.class, new UserApiImpl())
				.build();
			InMemoryTransport transport = InMemoryTransport.create(dispatcher::dispatch);
			client = JsonRpcClient.builder(loop.eventloop(), transport).build();
			api = client.proxy(UserApi.class);
		});
	}

	@After
	public void tearDown() {
		loop.close();
	}

	@Test
	public void dispatchByteArrayRefusesTheJUnitThread() {
		expectIllegalState(() ->
			dispatcher.dispatch("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"user.get\",\"params\":[42]}"
				.getBytes(UTF_8)));
	}

	@Test
	public void dispatchDecodedInputRefusesTheJUnitThread() {
		JsonRpcInput input = JsonRpcDecoder.decode(
			"{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"user.get\",\"params\":[42]}".getBytes(UTF_8));

		expectIllegalState(() -> dispatcher.dispatch(input));
	}

	@Test
	public void wireNamesRefusesTheJUnitThread() {
		expectIllegalState(dispatcher::wireNames);
	}

	@Test
	public void clientProxyRefusesTheJUnitThread() {
		expectIllegalState(() -> client.proxy(UserApi.class));
	}

	@Test
	public void inFlightCountRefusesTheJUnitThread() {
		expectIllegalState(client::inFlightCount);
	}

	@Test
	public void closeExRefusesTheJUnitThread() {
		expectIllegalState(() -> client.closeEx(new Exception("from the wrong thread")));
	}

	@Test
	public void aProxyMethodCallRefusesTheJUnitThread() {
		expectIllegalState(() -> api.getUser(42));
	}

	/** Calls {@code action}, here on the JUnit thread, and requires exactly an {@link IllegalStateException}. */
	private static void expectIllegalState(Runnable action) {
		try {
			action.run();
		} catch (Throwable caught) {
			assertSame("checkInReactorThread must reject a foreign thread with IllegalStateException, not " +
					   caught.getClass(),
				IllegalStateException.class, caught.getClass());
			return;
		}
		fail("expected a call from the JUnit thread (never the reactor thread here) to throw, got nothing");
	}
}
