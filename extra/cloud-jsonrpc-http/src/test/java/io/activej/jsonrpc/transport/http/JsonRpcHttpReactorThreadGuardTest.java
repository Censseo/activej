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

package io.activej.jsonrpc.transport.http;

import io.activej.http.HttpRequest;
import io.activej.http.StubHttpClient;
import io.activej.jsonrpc.service.JsonRpcDispatcher;
import io.activej.jsonrpc.transport.JsonRpcTransport;
import io.activej.jsonrpc.transport.http.fixtures.TestApi;
import io.activej.jsonrpc.transport.http.fixtures.TestApiImpl;
import io.activej.test.EventloopThread;
import io.activej.test.rules.ByteBufRule;
import org.jetbrains.annotations.Nullable;
import org.junit.After;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static io.activej.test.EventloopThread.await;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.fail;

/**
 * FR-025a / FR-011 / FR-031 — every public entry point of the transport pair opens with
 * {@code checkInReactorThread(this)} and must refuse a call from any thread other than the one
 * that owns the reactor: {@link IllegalStateException}, before anything is read (WI-1).
 * <ul>
 *     <li>{@link JsonRpcServlet#serve(HttpRequest)} (T024);</li>
 *     <li>the transport's {@link JsonRpcHttpClientTransport#send(byte[])},
 *     {@link JsonRpcHttpClientTransport#setListener(JsonRpcTransport.Listener)} and
 *     {@link JsonRpcHttpClientTransport#closeEx(Exception)} (review-1 MEDIUM-2) — a foreign-thread
 *     call must throw before the guard's first statement is passed, whatever the call.</li>
 * </ul>
 * <p>
 * The reactor lives on its own dedicated daemon thread ({@link EventloopThread}), never the JUnit
 * thread — so simply calling these methods from a test method body already is the cross-thread
 * call this checks for. {@code EventloopRule} <b>cannot</b> reproduce the violation:
 * {@code Reactor.inReactorThread()} is trivially true whenever the loop has never actively run,
 * so a direct call from the JUnit thread would pass the guard there. This is
 * {@code extra/cloud-jsonrpc}'s {@code JsonRpcReactorThreadGuardTest} lesson, inherited rather
 * than re-learned.
 * <p>
 * The one behavioural pin beyond the guards: FR-038 mandates only the refusal of a <b>missing</b>
 * listener — "called once" is a usage rule, not an enforced invariant — so a second
 * {@code setListener} is permitted and <b>replaces</b> the first: subsequent documents reach the
 * new listener only. That is the semantics the transport's javadoc documents, pinned through a
 * real exchange over {@link StubHttpClient}.
 * <p>
 * Deliberate rule set: {@link ByteBufRule} only. The class drives a reactor through
 * {@code EventloopThread}, not {@code EventloopRule}; declaring the rule would install a second,
 * never-running loop on the JUnit thread and confuse the very property under test. No
 * {@code io.activej.promise.TestUtils.await}/{@code assertCompleteFn} is used, so
 * {@code ActivePromisesRule} would track nothing — the one blocking wait (the replacement test)
 * is {@link EventloopThread#await(CompletableFuture, String)} over a {@code CompletableFuture},
 * which the rule does not track. Construction inside {@code loop.submit(...)} is deliberate:
 * {@code builder(...)} / {@code create(...)} are unguarded construction-time entry points (like
 * {@code JsonRpcDispatcher.builder}), so building off-thread would not fail and the test would
 * prove nothing.
 */
public final class JsonRpcHttpReactorThreadGuardTest {
	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	private static final byte[] ADD_DOCUMENT = utf8(
		"{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"test.add\",\"params\":[2,3]}");

	private EventloopThread loop;
	private JsonRpcServlet servlet;
	private JsonRpcHttpClientTransport transport;

	@Before
	public void setUp() {
		loop = EventloopThread.create("jsonrpc-http-guard-test");
		loop.submit(() -> {
			JsonRpcDispatcher dispatcher = JsonRpcDispatcher.builder(loop.eventloop())
				.withService(TestApi.class, new TestApiImpl())
				.build();
			servlet = JsonRpcServlet.create(loop.eventloop(), dispatcher);
			// the stub client is in-process: no socket, but the full request → servlet → response
			// chain, all on the loop thread — exactly the transport's wiring, minus the network
			transport = JsonRpcHttpClientTransport.create(loop.eventloop(),
				StubHttpClient.of(servlet), "http://localhost/");
		});
	}

	@After
	public void tearDown() {
		loop.close();
	}

	@Test
	public void serveRefusesACallFromAThreadOtherThanTheReactors() {
		// a bodyless request — nothing pooled, nothing to leak on any path
		HttpRequest request = HttpRequest.post("http://localhost/").build();

		try {
			servlet.serve(request);
		} catch (Throwable caught) {
			assertSame("checkInReactorThread must reject a foreign thread with IllegalStateException, not " +
					   caught.getClass(),
				IllegalStateException.class, caught.getClass());
			return;
		}
		fail("expected a call from the JUnit thread (never the reactor thread here) to throw, got nothing");
	}

	@Test
	public void sendRefusesACallFromAThreadOtherThanTheReactors() {
		try {
			transport.send(ADD_DOCUMENT);
		} catch (Throwable caught) {
			assertSame("checkInReactorThread must reject a foreign thread with IllegalStateException, not " +
					   caught.getClass(),
				IllegalStateException.class, caught.getClass());
			return;
		}
		fail("expected a call from the JUnit thread (never the reactor thread here) to throw, got nothing");
	}

	@Test
	public void setListenerRefusesACallFromAThreadOtherThanTheReactors() {
		try {
			transport.setListener(listener(new ArrayList<>(), new ArrayList<>()));
		} catch (Throwable caught) {
			assertSame("checkInReactorThread must reject a foreign thread with IllegalStateException, not " +
					   caught.getClass(),
				IllegalStateException.class, caught.getClass());
			return;
		}
		fail("expected a call from the JUnit thread (never the reactor thread here) to throw, got nothing");
	}

	@Test
	public void closeExRefusesACallFromAThreadOtherThanTheReactors() {
		try {
			transport.closeEx(new Exception("foreign-thread close"));
		} catch (Throwable caught) {
			assertSame("checkInReactorThread must reject a foreign thread with IllegalStateException, not " +
					   caught.getClass(),
				IllegalStateException.class, caught.getClass());
			return;
		}
		fail("expected a call from the JUnit thread (never the reactor thread here) to throw, got nothing");
	}

	/**
	 * FR-038 pins only the missing-listener refusal; a second {@code setListener} is <b>not</b>
	 * refused — "called once" is documented usage, not an enforced invariant (review-1 MEDIUM-2).
	 * The intended semantics is a silent replace, and it is what the transport's javadoc says: the
	 * listener is "retained for the lifetime of this transport", so the last one registered wins.
	 * A future change that made the second call refuse or ignore would ship green without this
	 * pin — a delivery through the <b>second</b> listener (and none through the first) is the
	 * observable contract.
	 */
	@Test
	public void aSecondSetListenerReplacesTheFirst() {
		List<byte[]> replaced = new ArrayList<>();
		List<byte[]> second = new ArrayList<>();

		CompletableFuture<Void> send = loop.submit(() -> {
			transport.setListener(listener(replaced, new ArrayList<>()));
			transport.setListener(listener(second, new ArrayList<>()));
			return transport.send(ADD_DOCUMENT);
		}).toCompletableFuture();
		await(send, "send");

		// the lists are loop-confined; read them through the loop like every other reactor state
		loop.submit(() -> {
			assertEquals("the second listener receives the document", 1, second.size());
			assertEquals("the replaced listener receives nothing", 0, replaced.size());
		});
	}

	private static JsonRpcTransport.Listener listener(List<byte[]> delivered, List<Exception> closed) {
		return new JsonRpcTransport.Listener() {
			@Override
			public void onDocument(byte[] document) {
				delivered.add(document);
			}

			@Override
			public void onClosed(@Nullable Exception e) {
				closed.add(e);
			}
		};
	}

	private static byte[] utf8(String document) {
		return document.getBytes(UTF_8);
	}
}
