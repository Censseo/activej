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

import io.activej.jsonrpc.service.fixtures.FailingApi;
import io.activej.jsonrpc.service.fixtures.FailingApiImpl;
import io.activej.reactor.Reactor;
import io.activej.test.rules.EventloopRule;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static io.activej.jsonrpc.service.fixtures.FailingApiImpl.SECRET;
import static io.activej.promise.TestUtils.await;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * A failing notification is answered with <b>nothing</b> — JSON-RPC 2.0 §4.1 forbids a response to a
 * notification, and that holds whatever the implementation did (FR-049) — but the failure is not swallowed
 * either: it reaches the handler {@code withFailureHandler(...)} configured (FR-050).
 *
 * <h2>Why the handler is configured rather than defaulted</h2>
 * The production default routes to {@code Reactor.logFatalError(e, descriptor)}, and {@link EventloopRule}
 * installs a <b>rethrowing</b> fatal-error handler. Asserting the default here would therefore assert the
 * rule's behaviour rather than the dispatcher's, and would turn something a server merely logs into a failed
 * test. FR-100 says to install a collecting handler instead, and the seam that exists for exactly this is
 * {@link JsonRpcDispatcher.Builder#withFailureHandler}.
 */
public class JsonRpcNotificationFailureTest {
	@ClassRule
	public static final EventloopRule eventloopRule = new EventloopRule();

	/** One collected failure: everything the handler was told. */
	private record Failure(JsonRpcMethodDescriptor descriptor, Exception exception) {}

	private FailingApiImpl implementation;
	private List<Failure> failures;
	private JsonRpcDispatcher dispatcher;

	@Before
	public void setUp() {
		implementation = new FailingApiImpl();
		failures = new ArrayList<>();
		dispatcher = JsonRpcDispatcher.builder(Reactor.getCurrentReactor())
			.withService(FailingApi.class, implementation)
			.withFailureHandler((descriptor, e) -> failures.add(new Failure(descriptor, e)))
			.build();
	}

	@Test
	public void aFailingNotificationStillProducesAZeroLengthDocument() {
		byte[] response = dispatch(
			"{\"jsonrpc\":\"2.0\",\"method\":\"fail.notify\",\"params\":{\"value\":\"x\"}}");

		assertEquals("§4.1 forbids answering a notification, failure included: not an error document, " +
					 "not [], not {} — nothing at all", 0, response.length);
		assertEquals(List.of("notifyAndFail(x)"), implementation.invocations());
	}

	@Test
	public void theFailureOfAFailingNotificationReachesTheConfiguredHandler() {
		dispatch("{\"jsonrpc\":\"2.0\",\"method\":\"fail.notify\",\"params\":{\"value\":\"x\"}}");

		assertEquals(1, failures.size());
		Failure failure = failures.get(0);
		assertEquals("the handler must be able to identify the wire name (FR-050)",
			"fail.notify", failure.descriptor().wireName());
		assertTrue(failure.descriptor().isNotification());
		assertEquals("the cause reaches the handler intact — it is the wire that learns nothing, not the server",
			SECRET, failure.exception().getMessage());
		assertSame(IllegalStateException.class, failure.exception().getClass());
	}

	@Test
	public void aNotificationThatThrowsSynchronouslyBehavesIdentically() {
		byte[] response = dispatch(
			"{\"jsonrpc\":\"2.0\",\"method\":\"fail.notifyThrow\",\"params\":{\"value\":\"x\"}}");

		assertEquals(0, response.length);
		assertEquals(1, failures.size());
		assertEquals("fail.notifyThrow", failures.get(0).descriptor().wireName());
		assertEquals(SECRET, failures.get(0).exception().getMessage());
	}

	@Test
	public void aNotificationWhoseParamsCannotBeDecodedIsAlsoReportedAndAlsoAnswerless() {
		byte[] response = dispatch(
			"{\"jsonrpc\":\"2.0\",\"method\":\"fail.notify\",\"params\":{\"value\":42}}");

		assertEquals("nothing goes on the wire for a notification, not even -32602", 0, response.length);
		assertEquals("but nothing is swallowed either (FR-050)", 1, failures.size());
		assertEquals("fail.notify", failures.get(0).descriptor().wireName());
		assertEquals("the implementation is never reached when params do not decode",
			List.of(), implementation.invocations());
	}

	@Test
	public void anUnknownNotificationIsDroppedWithoutBeingReported() {
		byte[] response = dispatch("{\"jsonrpc\":\"2.0\",\"method\":\"fail.absent\"}");

		assertEquals(0, response.length);
		assertEquals("an unknown notification is dropped, not reported as a failure", List.of(), failures);
	}

	@Test
	public void aFailingNotificationInsideABatchProducesNoElementForItself() {
		byte[] response = dispatch(
			"[{\"jsonrpc\":\"2.0\",\"method\":\"fail.notify\",\"params\":{\"value\":\"x\"}}," +
			"{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"fail.echo\",\"params\":{\"value\":\"y\"}}]");

		assertEquals("[{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":\"y\"}]", new String(response, UTF_8));
		assertEquals(1, failures.size());
		assertEquals("fail.notify", failures.get(0).descriptor().wireName());
	}

	@Test
	public void aBatchOfOnlyFailingNotificationsProducesNothingAtAll() {
		byte[] response = dispatch(
			"[{\"jsonrpc\":\"2.0\",\"method\":\"fail.notify\",\"params\":{\"value\":\"x\"}}," +
			"{\"jsonrpc\":\"2.0\",\"method\":\"fail.notifyThrow\",\"params\":{\"value\":\"y\"}}]");

		assertEquals("zero bytes, which is neither [] nor a batch of nulls", 0, response.length);
		assertEquals(2, failures.size());
	}

	/**
	 * A handler that itself fails must not turn a notification into a failed dispatch: totality (FR-038a)
	 * outranks the diagnostic, and a transport author was promised a branchless {@code then}.
	 */
	@Test
	public void aFailureHandlerThatThrowsDoesNotBreakDispatchTotality() {
		JsonRpcDispatcher hostile = JsonRpcDispatcher.builder(Reactor.getCurrentReactor())
			.withService(FailingApi.class, implementation)
			.withFailureHandler((descriptor, e) -> {throw new IllegalStateException("the handler is broken");})
			.build();

		byte[] response = await(hostile.dispatch(
			"{\"jsonrpc\":\"2.0\",\"method\":\"fail.notify\",\"params\":{\"value\":\"x\"}}".getBytes(UTF_8)));

		assertEquals(0, response.length);
	}

	private byte[] dispatch(String document) {
		byte[] response = await(dispatcher.dispatch(document.getBytes(UTF_8)));
		assertNotNull(response);
		return response;
	}
}
