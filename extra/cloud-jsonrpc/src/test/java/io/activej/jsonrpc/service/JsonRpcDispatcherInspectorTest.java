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

import io.activej.common.inspector.AbstractInspector;
import io.activej.jsonrpc.JsonRpcError;
import io.activej.jsonrpc.JsonRpcErrors;
import io.activej.jsonrpc.service.fixtures.FailingApi;
import io.activej.jsonrpc.service.fixtures.FailingApiImpl;
import io.activej.jsonrpc.service.fixtures.UserApi;
import io.activej.jsonrpc.service.fixtures.UserApiImpl;
import io.activej.promise.Promise;
import io.activej.promise.Promises;
import io.activej.reactor.Reactor;
import io.activej.test.rules.ActivePromisesRule;
import io.activej.test.rules.ByteBufRule;
import io.activej.test.rules.EventloopRule;
import org.jetbrains.annotations.Nullable;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static io.activej.promise.TestUtils.await;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * User story 2 — per-method JMX metrics (FR-030…FR-034, SC-003/SC-004).
 * <p>
 * Everything here runs against a hand-wired dispatcher with an instrumented {@code Inspector}: no JMX
 * server and no launcher. The per-method counters are asserted directly on the {@link
 * JsonRpcDispatcher.JmxInspector}'s stats objects, which is the "Independent test" of US2.
 * <p>
 * The class also carries the no-inspector baseline (FR-031, SC-005): with no inspector installed, every
 * dispatch outcome is byte-identical to the pre-seam dispatcher and {@code getStats()} reads {@code null}.
 */
public class JsonRpcDispatcherInspectorTest {
	@ClassRule
	public static final EventloopRule eventloopRule = new EventloopRule();
	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();
	@ClassRule
	public static final ActivePromisesRule activePromisesRule = new ActivePromisesRule();

	private UserApiImpl userImplementation;
	private FailingApiImpl failingImplementation;
	private JsonRpcDispatcher dispatcher;

	@Before
	public void setUp() {
		userImplementation = new UserApiImpl();
		failingImplementation = new FailingApiImpl();
		dispatcher = JsonRpcDispatcher.builder(Reactor.getCurrentReactor())
			.withService(UserApi.class, userImplementation)
			.withService(FailingApi.class, failingImplementation)
			.build();
	}

	private JsonRpcDispatcher instrumented(JsonRpcDispatcher.Inspector inspector) {
		return JsonRpcDispatcher.builder(Reactor.getCurrentReactor())
			.withService(UserApi.class, userImplementation)
			.withService(FailingApi.class, failingImplementation)
			.withInspector(inspector)
			.build();
	}

	private static String dispatch(JsonRpcDispatcher dispatcher, String document) {
		byte[] response = await(dispatcher.dispatch(document.getBytes(UTF_8)));
		assertNotNull(response);
		return new String(response, UTF_8);
	}

	// ---------------------------------------------------------------------------------------------------
	// T008 — the no-inspector baseline: the default path is unchanged (FR-031, SC-005).
	// ---------------------------------------------------------------------------------------------------

	@Test
	public void noInspectorIsInstalledByDefault() {
		assertNull(dispatcher.getStats());
	}

	@Test
	public void knownMethodOutcomeIsUnchangedWithoutAnInspector() {
		assertEquals("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"id\":42,\"name\":\"user-42\"}}",
			dispatch(dispatcher, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"user.get\",\"params\":[42]}"));
	}

	@Test
	public void unknownMethodOutcomeIsUnchangedWithoutAnInspector() {
		assertEquals("{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":{\"code\":-32601,\"message\":\"Method not found\"}}",
			dispatch(dispatcher, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"no.such\"}"));
	}

	@Test
	public void badParamsOutcomeIsUnchangedWithoutAnInspector() {
		assertEquals("{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":{\"code\":-32602,\"message\":\"Invalid params\"}}",
			dispatch(dispatcher, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"user.get\",\"params\":[1,2]}"));
	}

	@Test
	public void notificationOutcomeIsUnchangedWithoutAnInspector() {
		assertArrayEquals(new byte[0],
			await(dispatcher.dispatch("{\"jsonrpc\":\"2.0\",\"method\":\"user.touch\",\"params\":[1]}".getBytes(UTF_8))));
	}

	@Test
	public void malformedOutcomeIsUnchangedWithoutAnInspector() {
		String response = dispatch(dispatcher, "{\"jsonrpc\":\"2.0\",");
		assertTrue(response.contains("\"code\":-32700"));
	}

	// ---------------------------------------------------------------------------------------------------
	// T009 — per-method isolation (FR-032, SC-003).
	// ---------------------------------------------------------------------------------------------------

	@Test
	public void onlyTheInvokedMethodCountersMove() {
		JsonRpcDispatcher.JmxInspector inspector = new JsonRpcDispatcher.JmxInspector();
		JsonRpcDispatcher dispatcher = instrumented(inspector);

		for (int i = 1; i <= 3; i++) {
			dispatch(dispatcher, "{\"jsonrpc\":\"2.0\",\"id\":" + i + ",\"method\":\"user.get\",\"params\":[" + i + "]}");
		}
		dispatch(dispatcher, "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"fail.thrown\"}");

		assertEquals(4, inspector.getTotalRequests().getTotalCount());
		assertEquals(1, inspector.getTotalErrors().getTotalCount());

		JsonRpcMethodStats userGet = assertMethodRow(inspector, "user.get");
		assertEquals(3, userGet.getSuccessfulRequests().getTotalCount());
		assertEquals(0, userGet.getFailedRequests().getTotalCount());
		assertEquals(3, userGet.getRequestHandlingTime().getCount());

		JsonRpcMethodStats failThrown = assertMethodRow(inspector, "fail.thrown");
		assertEquals(1, failThrown.getFailedRequests().getTotalCount());
		assertEquals(1, failThrown.getErrorsByCode().get(JsonRpcErrors.INTERNAL_ERROR.code()).getTotalCount());

		// a registered but never-called method reports zero counts, and its row exists (fixed at build)
		JsonRpcMethodStats userTouch = assertMethodRow(inspector, "user.touch");
		assertEquals(0, userTouch.getSuccessfulRequests().getTotalCount());
		assertEquals(0, userTouch.getFailedRequests().getTotalCount());
		assertEquals(0, userTouch.getRequestHandlingTime().getCount());

		// the additive seam did not alter the table
		assertEquals(dispatcher.wireNames(), inspector.getMethodStats().keySet());
	}

	// ---------------------------------------------------------------------------------------------------
	// The wiring seam — a composite inspector reached by getStats()'s BaseInspector.lookup must be
	// initialized the same way (doBuild() calls the Inspector interface, not instanceof).
	// ---------------------------------------------------------------------------------------------------

	@Test
	public void aCompositeInspectorIsInitializedThroughTheSeam() {
		JsonRpcDispatcher.JmxInspector jmx = new JsonRpcDispatcher.JmxInspector();
		JsonRpcDispatcher.Inspector composite = new DelegatingInspector(jmx);
		JsonRpcDispatcher dispatcher = JsonRpcDispatcher.builder(Reactor.getCurrentReactor())
			.withService(UserApi.class, userImplementation)
			.withService(FailingApi.class, failingImplementation)
			.withInspector(composite)
			.build();

		assertSame("getStats() resolves the wrapped JmxInspector through BaseInspector.lookup",
			jmx, dispatcher.getStats());
		assertNotNull("the wrapped inspector's rows were pre-populated through the seam, not via instanceof",
			jmx.getMethodStats().get("user.get"));

		dispatch(dispatcher, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"user.get\",\"params\":[1]}");
		assertEquals(1, jmx.getTotalRequests().getTotalCount());
		assertEquals(1, jmx.getMethodStats().get("user.get").getSuccessfulRequests().getTotalCount());
	}

	@Test
	public void aJmxInspectorSharedAcrossTwoDispatchersIsRefused() {
		JsonRpcDispatcher.JmxInspector inspector = new JsonRpcDispatcher.JmxInspector();
		JsonRpcDispatcher first = JsonRpcDispatcher.builder(Reactor.getCurrentReactor())
			.withService(UserApi.class, userImplementation)
			.withInspector(inspector)
			.build();
		assertNotNull(first.getStats().getMethodStats().get("user.get"));

		try {
			JsonRpcDispatcher.builder(Reactor.getCurrentReactor())
				.withService(FailingApi.class, failingImplementation)
				.withInspector(inspector)
				.build();
			fail("re-using an inspector must be refused, not silently wipe the first dispatcher's rows");
		} catch (IllegalStateException e) {
			assertTrue("the refusal must name the sharing misuse, got: " + e.getMessage(),
				e.getMessage().contains("already wired"));
		}

		// the first dispatcher's table is untouched by the refused re-init
		assertEquals(first.wireNames(), inspector.getMethodStats().keySet());
		assertNotNull(inspector.getMethodStats().get("user.get"));
	}

	@Test
	public void errorsByCodeIteratesInNamedCodeDeclarationOrder() {
		JsonRpcDispatcher.JmxInspector inspector = new JsonRpcDispatcher.JmxInspector();
		JsonRpcDispatcher dispatcher = instrumented(inspector);

		JsonRpcMethodStats userGet = assertMethodRow(inspector, "user.get");
		List<Integer> keys = new ArrayList<>(userGet.getErrorsByCode().keySet());
		assertEquals("the errorsByCode keys are exactly the nine named codes, in declaration order",
			JsonRpcErrors.named().stream().map(JsonRpcError::code).toList(), keys);
	}

	// ---------------------------------------------------------------------------------------------------
	// T010 — the cardinality bound (FR-034, SC-004): the feature's single most important test.
	// ---------------------------------------------------------------------------------------------------

	@Test
	public void tenThousandDistinctUnregisteredNamesAddNoRows() {
		JsonRpcDispatcher.JmxInspector inspector = new JsonRpcDispatcher.JmxInspector();
		JsonRpcDispatcher dispatcher = instrumented(inspector);
		int registered = dispatcher.wireNames().size();

		for (int i = 0; i < 10_000; i++) {
			await(dispatcher.dispatch(
				("{\"jsonrpc\":\"2.0\",\"id\":" + i + ",\"method\":\"no.such." + i + "\"}").getBytes(UTF_8)));
		}

		// zero new rows — exactly the registered set
		assertEquals(dispatcher.wireNames(), inspector.getMethodStats().keySet());
		assertEquals(registered, inspector.getMethodStats().size());
		assertEquals(10_000, inspector.getMethodNotFound().getTotalCount());
		assertEquals(10_000, inspector.getTotalRequests().getTotalCount());
		assertEquals(10_000, inspector.getTotalErrors().getTotalCount());
	}

	@Test
	public void aRegisteredNameStillCountsAgainstItsRow() {
		JsonRpcDispatcher.JmxInspector inspector = new JsonRpcDispatcher.JmxInspector();
		JsonRpcDispatcher dispatcher = instrumented(inspector);

		dispatch(dispatcher, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"user.get\",\"params\":[1]}");

		assertEquals(dispatcher.wireNames(), inspector.getMethodStats().keySet());
		assertEquals(1, inspector.getMethodStats().get("user.get").getSuccessfulRequests().getTotalCount());
	}

	// ---------------------------------------------------------------------------------------------------
	// T011 — the error-code breakdown (FR-033, FR-033a).
	// ---------------------------------------------------------------------------------------------------

	@Test
	public void theThreeNamedBucketsAreDistinct() {
		JsonRpcDispatcher.JmxInspector inspector = new JsonRpcDispatcher.JmxInspector();
		JsonRpcDispatcher dispatcher = instrumented(inspector);

		// -32601: no descriptor exists — the aggregate counter, and no row
		dispatch(dispatcher, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"no.such\"}");
		// -32602: params fail to decode — the implementation is not invoked
		dispatch(dispatcher, "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"user.get\",\"params\":[1,2]}");
		// -32603: a plain exception
		dispatch(dispatcher, "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"fail.thrown\"}");

		assertEquals(1, inspector.getMethodNotFound().getTotalCount());
		assertEquals(dispatcher.wireNames(), inspector.getMethodStats().keySet());
		assertFalse(inspector.getMethodStats().containsKey("no.such"));

		JsonRpcMethodStats userGet = assertMethodRow(inspector, "user.get");
		assertEquals(1, userGet.getErrorsByCode().get(JsonRpcErrors.INVALID_PARAMS.code()).getTotalCount());
		assertEquals(0, userGet.getErrorsByCode().get(JsonRpcErrors.INTERNAL_ERROR.code()).getTotalCount());
		assertEquals(1, userGet.getFailedRequests().getTotalCount());

		JsonRpcMethodStats failThrown = assertMethodRow(inspector, "fail.thrown");
		assertEquals(1, failThrown.getErrorsByCode().get(JsonRpcErrors.INTERNAL_ERROR.code()).getTotalCount());

		// the params-decode failure never invoked the implementation
		assertEquals(List.of(), userImplementation.invocations());
		assertEquals(List.of("thrown()"), failingImplementation.invocations());
	}

	@Test
	public void anApplicationCodeOutsideTheNamedSetLandsInOtherErrors() {
		JsonRpcDispatcher.JmxInspector inspector = new JsonRpcDispatcher.JmxInspector();
		JsonRpcDispatcher dispatcher = instrumented(inspector);

		// code 429 is outside the nine named JsonRpcErrors codes
		dispatch(dispatcher, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"fail.failedWithJsonRpc\"}");

		JsonRpcMethodStats failedWithJsonRpc = assertMethodRow(inspector, "fail.failedWithJsonRpc");
		assertEquals(1, failedWithJsonRpc.getOtherErrors().getTotalCount());
		// no entry created for 429 — the map stays the closed named set
		assertEquals(9, failedWithJsonRpc.getErrorsByCode().size());
		assertFalse(failedWithJsonRpc.getErrorsByCode().containsKey(429));
	}

	// ---------------------------------------------------------------------------------------------------
	// T012 — latency accounting (FR-032): a real sample for a delayed method, no samples for a never-called one.
	// ---------------------------------------------------------------------------------------------------

	@JsonRpcService("slow")
	public interface SlowApi {
		@JsonRpcMethod("delayed")
		Promise<String> delayed();

		@JsonRpcMethod("fast")
		Promise<String> fast();
	}

	public static final class SlowApiImpl implements SlowApi {
		private final Reactor reactor;

		public SlowApiImpl(Reactor reactor) {this.reactor = reactor;}

		@Override
		public Promise<String> delayed() {
			return Promises.delay(reactor, 50, "delayed");
		}

		@Override
		public Promise<String> fast() {
			return Promise.of("fast");
		}
	}

	@Test
	public void aDelayedMethodProducesASampleAndANeverCalledMethodHasNone() {
		JsonRpcDispatcher.JmxInspector inspector = new JsonRpcDispatcher.JmxInspector();
		JsonRpcDispatcher dispatcher = JsonRpcDispatcher.builder(Reactor.getCurrentReactor())
			.withService(SlowApi.class, new SlowApiImpl(Reactor.getCurrentReactor()))
			.withService(UserApi.class, userImplementation)
			.withInspector(inspector)
			.build();

		dispatch(dispatcher, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"slow.delayed\"}");
		dispatch(dispatcher, "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"slow.fast\"}");

		JsonRpcMethodStats delayed = assertMethodRow(inspector, "slow.delayed");
		assertEquals(1, delayed.getRequestHandlingTime().getCount());
		// a measurable delay produced a sample (millis are truncated, so the floor is below the nominal 50)
		assertTrue(delayed.getRequestHandlingTime().getLastValue() >= 40);

		// the synchronous path records a sample too, with a non-negative duration (research U6)
		JsonRpcMethodStats fast = assertMethodRow(inspector, "slow.fast");
		assertEquals(1, fast.getRequestHandlingTime().getCount());
		assertTrue(fast.getRequestHandlingTime().getLastValue() >= 0);

		// a registered but never-called method has NO samples, not a zero-valued sample
		JsonRpcMethodStats touch = assertMethodRow(inspector, "user.touch");
		assertEquals(0, touch.getRequestHandlingTime().getCount());
		assertEquals("", touch.getRequestHandlingTime().toString());
	}

	// ---------------------------------------------------------------------------------------------------
	// T013 — notification accounting (FR-040): a failing notification counts against its method while the
	// failure-handler behaviour stays unchanged.
	// ---------------------------------------------------------------------------------------------------

	private record Failure(JsonRpcMethodDescriptor descriptor, Exception exception) {}

	@Test
	public void aFailingNotificationCountsAgainstItsMethod() {
		List<Failure> failures = new ArrayList<>();
		JsonRpcDispatcher.JmxInspector inspector = new JsonRpcDispatcher.JmxInspector();
		JsonRpcDispatcher dispatcher = JsonRpcDispatcher.builder(Reactor.getCurrentReactor())
			.withService(FailingApi.class, failingImplementation)
			.withFailureHandler((descriptor, e) -> failures.add(new Failure(descriptor, e)))
			.withInspector(inspector)
			.build();

		assertArrayEquals(new byte[0],
			await(dispatcher.dispatch("{\"jsonrpc\":\"2.0\",\"method\":\"fail.notify\",\"params\":{\"value\":\"x\"}}".getBytes(UTF_8))));

		// the failure-handler half is unchanged
		assertEquals(1, failures.size());
		assertEquals("fail.notify", failures.get(0).descriptor().wireName());
		assertTrue(failures.get(0).descriptor().isNotification());
		assertTrue(failures.get(0).exception() instanceof IllegalStateException);

		// the accounting half
		JsonRpcMethodStats notify = assertMethodRow(inspector, "fail.notify");
		assertEquals(1, notify.getFailedRequests().getTotalCount());
		assertEquals(0, notify.getSuccessfulRequests().getTotalCount());
		assertEquals(1, notify.getRequestHandlingTime().getCount());
		assertEquals(1, notify.getErrorsByCode().get(JsonRpcErrors.INTERNAL_ERROR.code()).getTotalCount());
		assertEquals(1, inspector.getTotalRequests().getTotalCount());
		assertEquals(1, inspector.getTotalErrors().getTotalCount());
	}

	@Test
	public void aSuccessfulNotificationCountsAgainstItsMethod() {
		List<Failure> failures = new ArrayList<>();
		JsonRpcDispatcher.JmxInspector inspector = new JsonRpcDispatcher.JmxInspector();
		JsonRpcDispatcher dispatcher = JsonRpcDispatcher.builder(Reactor.getCurrentReactor())
			.withService(UserApi.class, userImplementation)
			.withFailureHandler((descriptor, e) -> failures.add(new Failure(descriptor, e)))
			.withInspector(inspector)
			.build();

		assertArrayEquals(new byte[0],
			await(dispatcher.dispatch("{\"jsonrpc\":\"2.0\",\"method\":\"user.touch\",\"params\":[7]}".getBytes(UTF_8))));

		JsonRpcMethodStats touch = assertMethodRow(inspector, "user.touch");
		assertEquals(1, touch.getSuccessfulRequests().getTotalCount());
		assertEquals(1, inspector.getTotalRequests().getTotalCount());
		assertEquals(0, failures.size());
	}

	// ---------------------------------------------------------------------------------------------------
	// T014 — totality under a hostile inspector (FR-040, ADR-033): a throwing inspector changes nothing.
	// ---------------------------------------------------------------------------------------------------

	@Test
	public void aHostileInspectorCannotBreakTotalityOrChangeDocuments() {
		JsonRpcDispatcher control = dispatcher;
		JsonRpcDispatcher hostile = JsonRpcDispatcher.builder(Reactor.getCurrentReactor())
			.withService(UserApi.class, new UserApiImpl())
			.withService(FailingApi.class, new FailingApiImpl())
			.withInspector(new HostileInspector())
			.build();

		String[] documents = {
			"{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"user.get\",\"params\":[1]}",
			"{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"fail.thrown\"}",
			"{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"user.get\",\"params\":[1,2]}",
			"{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"no.such\"}",
			"{\"jsonrpc\":\"2.0\",\"method\":\"user.touch\",\"params\":[1]}",
			"{\"jsonrpc\":\"2.0\",",
			"[{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"user.get\",\"params\":[5]}," +
				"{\"jsonrpc\":\"2.0\",\"id\":6,\"method\":\"no.such\"}," +
				"{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"fail.thrown\"}]"
		};

		for (String document : documents) {
			assertArrayEquals("document diverged: " + document,
				await(control.dispatch(document.getBytes(UTF_8))),
				await(hostile.dispatch(document.getBytes(UTF_8))));
		}
	}

	// ---------------------------------------------------------------------------------------------------
	// Helpers.
	// ---------------------------------------------------------------------------------------------------

	/** An inspector that throws from every callback — the totality proof for a hostile implementation. */
	private static final class HostileInspector
		extends AbstractInspector<JsonRpcDispatcher.Inspector>
		implements JsonRpcDispatcher.Inspector {
		@Override
		public void onRequest(JsonRpcMethodDescriptor descriptor) {throw new IllegalStateException("hostile");}

		@Override
		public void onResponse(JsonRpcMethodDescriptor descriptor, long durationMillis) {
			throw new IllegalStateException("hostile");
		}

		@Override
		public void onError(JsonRpcMethodDescriptor descriptor, int errorCode, long durationMillis) {
			throw new AssertionError("hostile");
		}

		@Override
		public void onMethodNotFound(String requestedName) {throw new IllegalStateException("hostile");}

		@Override
		public void onMalformed() {throw new IllegalStateException("hostile");}
	}

	/**
	 * A composite inspector: every callback and {@link #lookup} forward to a delegate — the "logging +
	 * tracing + JMX" shape the seam exists for. A delegating inspector MUST forward
	 * {@link #initialize(Set)} too; {@code doBuild()} reaches the delegate through this forwarding.
	 */
	private static final class DelegatingInspector
		extends AbstractInspector<JsonRpcDispatcher.Inspector>
		implements JsonRpcDispatcher.Inspector {
		private final JsonRpcDispatcher.Inspector delegate;

		private DelegatingInspector(JsonRpcDispatcher.Inspector delegate) {this.delegate = delegate;}

		@Override
		public <T extends JsonRpcDispatcher.Inspector> @Nullable T lookup(Class<T> type) {
			return delegate.lookup(type);
		}

		@Override
		public void initialize(Set<String> wireNames) {
			delegate.initialize(wireNames);
		}

		@Override
		public void onRequest(JsonRpcMethodDescriptor descriptor) {
			delegate.onRequest(descriptor);
		}

		@Override
		public void onResponse(JsonRpcMethodDescriptor descriptor, long durationMillis) {
			delegate.onResponse(descriptor, durationMillis);
		}

		@Override
		public void onError(JsonRpcMethodDescriptor descriptor, int errorCode, long durationMillis) {
			delegate.onError(descriptor, errorCode, durationMillis);
		}

		@Override
		public void onMethodNotFound(String requestedName) {
			delegate.onMethodNotFound(requestedName);
		}

		@Override
		public void onMalformed() {
			delegate.onMalformed();
		}
	}

	private static JsonRpcMethodStats assertMethodRow(JsonRpcDispatcher.JmxInspector inspector, String wireName) {
		JsonRpcMethodStats stats = inspector.getMethodStats().get(wireName);
		assertNotNull("no row for registered method " + wireName + " — rows: " + inspector.getMethodStats().keySet(), stats);
		return stats;
	}
}
