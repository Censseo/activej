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
import io.activej.jsonrpc.JsonRpcErrors;
import io.activej.jsonrpc.JsonRpcException;
import io.activej.jsonrpc.JsonRpcPayload;
import io.activej.jsonrpc.service.fixtures.InMemoryTransport;
import io.activej.jsonrpc.service.fixtures.User;
import io.activej.jsonrpc.service.fixtures.UserApi;
import io.activej.jsonrpc.service.fixtures.UserApiImpl;
import io.activej.promise.Promise;
import io.activej.reactor.Reactor;
import io.activej.test.rules.ActivePromisesRule;
import io.activej.test.rules.ByteBufRule;
import io.activej.test.rules.EventloopRule;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static io.activej.promise.TestUtils.await;
import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Adversarial round 1, domain B — hostile <b>implementations</b> and hostile <b>inspectors</b> against
 * {@link JsonRpcDispatcher} (plan rows B1…B10). The oracle is the feature contract (FR-034, FR-038a,
 * FR-040, FR-046a, FR-047, FR-048, FR-050, FR-072), never "what the code does today".
 * <p>
 * Scenarios already proven by the feature's own suite are referenced rather than duplicated:
 * <ul>
 *     <li><b>B3</b> — a {@code JsonRpcException}'s {@code data} whose codec throws while encoding:
 *     {@code JsonRpcDispatcherTotalityTest.aThrowingErrorDataCodecStillProducesADocumentRatherThanAFailedPromise}
 *     (the {@code encodeOutput} guard, -32603, never a failed promise).</li>
 *     <li><b>B4</b> — a result codec refusing {@code null}: {@code JsonRpcErrorMappingTest.aCodecRefusingANullResultIsTheOrdinaryInternalError}.</li>
 *     <li><b>B5</b> inter-interface collision naming both interfaces:
 *     {@code JsonRpcCrossServiceTest.aWireNameClaimedByTwoInterfacesFailsAtBuildNamingBoth}; intra-interface:
 *     {@code JsonRpcServiceContractTest.rule4_twoMethodsResolvingToTheSameWireNameIsAViolation}.</li>
 *     <li><b>B8</b> construction-side rejection of reserved codes:
 *     {@code JsonRpcErrorsTest.ofRejectsEveryCodeInTheReservedRange} (includes -32603 and -32000) and
 *     {@code ofAcceptsTheCodesJustOutsideTheReservedRange} (includes -31999).</li>
 * </ul>
 */
public class JsonRpcDispatcherAdversarialTest {
	@ClassRule
	public static final EventloopRule eventloopRule = new EventloopRule();
	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();
	@ClassRule
	public static final ActivePromisesRule activePromisesRule = new ActivePromisesRule();

	private HostileApiImpl implementation;
	private JsonRpcDispatcher dispatcher;

	@Before
	public void setUp() {
		implementation = new HostileApiImpl();
		dispatcher = JsonRpcDispatcher.builder(Reactor.getCurrentReactor())
			.withService(HostileApi.class, implementation)
			.withFailureHandler((descriptor, e) -> failures.add(descriptor.wireName() + ": " + e))
			.build();
	}

	private final List<String> failures = new ArrayList<>();

	// ---------------------------------------------------------------------------------------------------
	// B1 — an implementation that BLOCKS the reactor (Thread.sleep). The freeze is the implementation's
	// own fault (WI-5: blocking work never belongs on the reactor thread; the dispatcher has no offload
	// seam and must not invent one). The contract under test: nothing leaks afterwards, the dispatch that
	// survived the freeze still answers correctly, and the latency is sampled. Timing-tolerant: only a
	// generous lower bound is asserted, never an upper one.
	// ---------------------------------------------------------------------------------------------------

	@Test
	public void aBlockingImplementationFreezesTheReactorButLeaksNothingAndSamplesLatency() {
		JsonRpcDispatcher.JmxInspector inspector = new JsonRpcDispatcher.JmxInspector();
		JsonRpcDispatcher blocking = JsonRpcDispatcher.builder(Reactor.getCurrentReactor())
			.withService(HostileApi.class, implementation)
			.withInspector(inspector)
			.build();

		long start = System.nanoTime();
		String response = dispatch(blocking, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"hostile.slow\"}");
		long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

		// the freeze really happened: the whole reactor was stuck for the sleep's duration
		assertTrue("the dispatch must have been frozen by the sleep, took only " + elapsedMillis + "ms",
			elapsedMillis >= 250);
		assertEquals("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":\"after-sleep\"}", response);

		// latency sampled: requestHandlingTime carries one sample of at least the sleep
		JsonRpcMethodStats slow = inspector.getMethodStats().get("hostile.slow");
		assertNotNull(slow);
		assertEquals(1, slow.getRequestHandlingTime().getCount());
		assertTrue("latency sample must reflect the freeze: " + slow.getRequestHandlingTime().getLastValue(),
			slow.getRequestHandlingTime().getLastValue() >= 200);

		// the dispatcher is alive and correct after the freeze
		assertEquals("{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":\"fast\"}",
			dispatch(blocking, "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"hostile.fast\"}"));
		// ByteBufRule is green: nothing leaked on the frozen path either
	}

	// ---------------------------------------------------------------------------------------------------
	// B2 — an implementation that returns null where a Promise was declared (request path proven by
	// JsonRpcErrorMappingTest.aNullWhereAPromiseWasDeclaredIsAFailedInvocationRatherThanAPropagatedNpe);
	// the two unproven shapes are added here: a request method that throws new Error(...), and a
	// notification declared Promise<Void> that returns null. Oracle: -32603 without data for the Error;
	// the notification-failure handler receives the wrapped failure for the null.
	// ---------------------------------------------------------------------------------------------------

	@Test
	public void aRequestThatThrowsAnErrorIs32603WithoutData() {
		String response = dispatch(dispatcher, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"hostile.throwError\"}");

		assertEquals("{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":{\"code\":-32603,\"message\":\"Internal error\"}}",
			response);
		assertFalse("nothing of the Error may reach the wire (FR-048)", response.contains(HostileApiImpl.SECRET));
		assertFalse("a -32603 carries no data member", response.contains("\"data\""));
		assertEquals(List.of("throwError()"), implementation.invocations());
	}

	@Test
	public void aNotificationThatReturnsNullInsteadOfAPromiseReachesTheFailureHandlerWrapped() {
		byte[] response = await(dispatcher.dispatch(
			"{\"jsonrpc\":\"2.0\",\"method\":\"hostile.nullNotify\",\"params\":{\"value\":\"x\"}}".getBytes(UTF_8)));

		assertEquals("a notification is answered with nothing at all, failure included", 0, response.length);
		assertEquals(1, failures.size());
		assertTrue(failures.get(0), failures.get(0).startsWith("hostile.nullNotify: "));
		assertTrue("the failure handler must receive the wrapped null-return failure, not a silent drop",
			failures.get(0).contains("returned null instead of a Promise"));
	}

	// ---------------------------------------------------------------------------------------------------
	// B5 — both collision kinds in one build attempt: exactly ONE exception escapes (FR-072: one violation
	// list, never N copies). Contract validation runs per service inside doBuild and precedes the
	// cross-service scan, so with both kinds present the first broken contract's list is the one reported —
	// the deterministic single failure, never two.
	// ---------------------------------------------------------------------------------------------------

	@Test
	public void bothCollisionKindsFailStartupWithExactlyOneException() {
		try {
			JsonRpcDispatcher.builder(Reactor.getCurrentReactor())
				.withService(UserApi.class, new UserApiImpl())
				.withService(ShadowingApi.class, new ShadowingApiImpl())
				.withService(DuplicateWireApi.class, new DuplicateWireApiImpl())
				.build();
			fail("a dispatcher with an intra-interface collision must not build");
		} catch (JsonRpcContractException e) {
			// exactly one exception with exactly one violation list — the per-worker duplication is the
			// launcher's concern (T027/T047); at the dispatcher level this is the whole of FR-072
			assertEquals(1, e.violations().size());
			assertTrue(e.getMessage(), e.getMessage().contains("dup.get"));
			assertTrue("the broken interface must be named: " + e.getMessage(),
				e.getMessage().contains(DuplicateWireApi.class.getName()));
		}
	}

	// ---------------------------------------------------------------------------------------------------
	// B6 — a hostile inspector that SPINS before throwing. A truly unbounded loop would freeze the reactor
	// forever — the same implementer-fault class as B1's Thread.sleep (WI-5) — so the test uses a bounded
	// spin and documents that boundary: totality and document identity must survive a callback that burned
	// real CPU before throwing, from every callback including AssertionError.
	// ---------------------------------------------------------------------------------------------------

	@Test
	public void aSpinningHostileInspectorCannotBreakTotalityOrChangeDocuments() {
		JsonRpcDispatcher control = JsonRpcDispatcher.builder(Reactor.getCurrentReactor())
			.withService(HostileApi.class, new HostileApiImpl())
			.withService(UserApi.class, new UserApiImpl())
			.build();
		JsonRpcDispatcher hostile = JsonRpcDispatcher.builder(Reactor.getCurrentReactor())
			.withService(HostileApi.class, new HostileApiImpl())
			.withService(UserApi.class, new UserApiImpl())
			.withInspector(new SpinningHostileInspector())
			.build();

		String[] documents = {
			"{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"hostile.fast\"}",
			"{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"hostile.throwError\"}",
			"{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"user.get\",\"params\":[1,2]}",
			"{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"no.such\"}",
			"{\"jsonrpc\":\"2.0\",\"method\":\"user.touch\",\"params\":[1]}",
			"{\"jsonrpc\":\"2.0\","
		};

		for (String document : documents) {
			byte[] expected = await(control.dispatch(document.getBytes(UTF_8)));
			byte[] actual = await(hostile.dispatch(document.getBytes(UTF_8)));
			assertNotNull(actual);
			assertEquals("document diverged under the spinning inspector: " + document,
				new String(expected, UTF_8), new String(actual, UTF_8));
		}
	}

	// ---------------------------------------------------------------------------------------------------
	// B7 — inspector retention. A conforming Inspector may store every onMethodNotFound name it receives,
	// and the contract forbids it (FR-034: aggregate-only, never retained). The dispatcher's side of that
	// boundary is what is assertable: it must not FACILITATE retention — no per-name storage of its own,
	// no dedup cache that would quietly bound a retaining implementation's memory for it.
	// ---------------------------------------------------------------------------------------------------

	@Test
	public void theDispatcherKeepsNoPerNameStorageBeyondTheRegisteredRows() {
		RetainingInspector recording = new RetainingInspector();
		JsonRpcDispatcher wired = JsonRpcDispatcher.builder(Reactor.getCurrentReactor())
			.withService(UserApi.class, new UserApiImpl())
			.withInspector(recording)
			.build();
		JsonRpcDispatcher.JmxInspector jmx = new JsonRpcDispatcher.JmxInspector();
		JsonRpcDispatcher instrumented = JsonRpcDispatcher.builder(Reactor.getCurrentReactor())
			.withService(UserApi.class, new UserApiImpl())
			.withInspector(jmx)
			.build();

		for (int i = 0; i < 200; i++) {
			await(wired.dispatch(
				("{\"jsonrpc\":\"2.0\",\"id\":" + i + ",\"method\":\"no.such." + i + "\"}").getBytes(UTF_8)));
			await(instrumented.dispatch(
				("{\"jsonrpc\":\"2.0\",\"id\":" + i + ",\"method\":\"no.such." + i + "\"}").getBytes(UTF_8)));
		}

		// the dispatcher itself holds nothing for any of the 200 names
		assertEquals(Set.of("user.get", "user.touch"), wired.wireNames());
		assertEquals(wired.wireNames(), jmx.getMethodStats().keySet());
		assertEquals(200, jmx.getMethodNotFound().getTotalCount());

		// the seam forwards every name verbatim — no internal dedup cache exists that would silently
		// bound the memory of a retaining implementation. Retention is the implementer's fault, and the
		// dispatcher neither prevents it nor helps it (FR-034)
		assertEquals(200, recording.receivedNames.size());
		assertEquals("no.such.199", recording.receivedNames.get(199));
	}

	// ---------------------------------------------------------------------------------------------------
	// B8 — an application code just OUTSIDE the reserved range: -31999 is a legal application code, must
	// travel verbatim on the wire (FR-047) and must land in the single otherErrors aggregate (FR-033a) —
	// never a new errorsByCode entry. The construction-side rejection of -32603/-32000 is covered by
	// JsonRpcErrorsTest (see class Javadoc).
	// ---------------------------------------------------------------------------------------------------

	@Test
	public void aCodeJustOutsideTheReservedRangeTravelsVerbatimAndCountsAsOtherErrors() {
		JsonRpcDispatcher.JmxInspector inspector = new JsonRpcDispatcher.JmxInspector();
		JsonRpcDispatcher wired = JsonRpcDispatcher.builder(Reactor.getCurrentReactor())
			.withService(HostileApi.class, implementation)
			.withInspector(inspector)
			.build();

		String response = dispatch(wired,
			"{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"hostile.boundary\"}");
		assertEquals("{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":{\"code\":-31999," +
					 "\"message\":\"application boundary\",\"data\":{\"retryAfter\":60}}}", response);

		JsonRpcMethodStats boundary = inspector.getMethodStats().get("hostile.boundary");
		assertNotNull(boundary);
		assertEquals(1, boundary.getOtherErrors().getTotalCount());
		assertEquals("the closed named set, unchanged: " + boundary.getErrorsByCode().keySet(),
			9, boundary.getErrorsByCode().size());
		assertFalse(boundary.getErrorsByCode().containsKey(-31999));
	}

	// ---------------------------------------------------------------------------------------------------
	// B9 — 1,000,000 distinct unregistered names. Zero new rows, methodNotFound correct, bounded duration
	// (no strict time assertion — WI-17; the elapsed time is printed for the record). The dispatch promise
	// of a synchronous miss completes inline, so the loop discards the promises; every 100k iterations the
	// completeness of the returned promise is asserted so that silently-deferred work cannot fake a pass.
	// ---------------------------------------------------------------------------------------------------

	@Test
	public void oneMillionDistinctUnregisteredNamesAddNoRows() {
		JsonRpcDispatcher.JmxInspector inspector = new JsonRpcDispatcher.JmxInspector();
		JsonRpcDispatcher wired = JsonRpcDispatcher.builder(Reactor.getCurrentReactor())
			.withService(UserApi.class, new UserApiImpl())
			.withInspector(inspector)
			.build();
		int registered = wired.wireNames().size();

		long start = System.nanoTime();
		for (int i = 0; i < 1_000_000; i++) {
			Promise<byte[]> promise = wired.dispatch(
				("{\"jsonrpc\":\"2.0\",\"id\":" + i + ",\"method\":\"no.such." + i + "\"}").getBytes(UTF_8));
			if (i % 100_000 == 0) {
				assertTrue("a synchronous miss must complete inline, iteration " + i, promise.isComplete());
			}
		}
		long elapsedMillis = (System.nanoTime() - start) / 1_000_000;
		System.out.println("[B9] 1,000,000 distinct unregistered names dispatched in " + elapsedMillis + "ms");

		// zero new rows — exactly the registered set
		assertEquals(wired.wireNames(), inspector.getMethodStats().keySet());
		assertEquals(registered, inspector.getMethodStats().size());
		assertEquals(1_000_000, inspector.getMethodNotFound().getTotalCount());
		assertEquals(1_000_000, inspector.getTotalRequests().getTotalCount());
		assertEquals(1_000_000, inspector.getTotalErrors().getTotalCount());
	}

	// ---------------------------------------------------------------------------------------------------
	// B10 — one dispatcher used concurrently by TWO transports, and as the peerHandler of a client on a
	// third: responses are correct by correlation on every transport, and the client's correlation table
	// ends empty. (The reordered-delivery half of the correlation contract is FR-094 and lives in
	// AbstractTransportConformanceTest.)
	// ---------------------------------------------------------------------------------------------------

	@Test
	public void twoTransportsInterleavedAndAPeerHandlerClientCorrelateCorrectly() {
		UserApiImpl userImplementation = new UserApiImpl();
		JsonRpcDispatcher shared = JsonRpcDispatcher.builder(Reactor.getCurrentReactor())
			.withService(UserApi.class, userImplementation)
			.build();

		InMemoryTransport first = InMemoryTransport.create(shared::dispatch);
		InMemoryTransport second = InMemoryTransport.create(shared::dispatch);
		List<String> fromFirst = new ArrayList<>();
		List<String> fromSecond = new ArrayList<>();
		first.setListener(documents(fromFirst));
		second.setListener(documents(fromSecond));

		// interleaved traffic on both transports
		await(first.send(utf8("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"user.get\",\"params\":[1]}")));
		await(second.send(utf8("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"user.get\",\"params\":[2]}")));
		await(first.send(utf8("{\"jsonrpc\":\"2.0\",\"method\":\"user.touch\",\"params\":[3]}")));
		await(second.send(utf8("{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"user.get\",\"params\":[4]}")));

		// each answer is correlated by its id, on the transport that asked
		assertEquals(List.of("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"id\":1,\"name\":\"user-1\"}}"),
			fromFirst);
		assertEquals(2, fromSecond.size());
		assertEquals("{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"id\":2,\"name\":\"user-2\"}}",
			fromSecond.get(0));
		assertEquals("{\"jsonrpc\":\"2.0\",\"id\":4,\"result\":{\"id\":4,\"name\":\"user-4\"}}",
			fromSecond.get(1));

		// a client on a third transport, answering the shared dispatcher's own calls
		InMemoryTransport clientTransport = InMemoryTransport.create(shared::dispatch);
		JsonRpcClient client = JsonRpcClient.builder(Reactor.getCurrentReactor(), clientTransport)
			.withPeerHandler(shared)
			.build();
		UserApi api = client.proxy(UserApi.class);

		// hold the answer so the in-flight state is observable — an in-memory transport delivers
		// synchronously, and with no hold the entry would already be gone
		clientTransport.startHolding();
		Promise<User> pending = api.getUser(42);
		assertEquals("the answer is held, so the call stays in flight", 1, client.inFlightCount());
		assertFalse(pending.isComplete());
		clientTransport.releaseInOrder();
		clientTransport.stopHolding();
		assertEquals(new User(42, "user-42"), await(pending));
		assertEquals("the answer removed its correlation entry", 0, client.inFlightCount());

		// the dispatcher pushes its own request to the client; the client answers it through the peer
		// handler (the dispatcher) while its correlation table stays empty — an inbound request creates
		// no entry (FR-076)
		clientTransport.deliverFromPeer(utf8("{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"user.get\",\"params\":[7]}"));
		assertTrue(clientTransport.sentText().toString(),
			clientTransport.sentText().contains(
				"{\"jsonrpc\":\"2.0\",\"id\":7,\"result\":{\"id\":7,\"name\":\"user-7\"}}"));
		assertEquals(0, client.inFlightCount());
		assertEquals(List.of("getUser(1)", "getUser(2)", "touch(3)", "getUser(4)", "getUser(42)", "getUser(7)"),
			userImplementation.invocations());
	}

	// ---------------------------------------------------------------------------------------------------
	// Fixtures — every hostile shape that the shared fixtures do not already carry.
	// ---------------------------------------------------------------------------------------------------

	@JsonRpcService("hostile")
	public interface HostileApi {
		/** Blocks the reactor for 300ms, then answers. */
		@JsonRpcMethod("slow")
		Promise<String> slow();

		@JsonRpcMethod("fast")
		Promise<String> fast();

		/** Throws an {@link Error} synchronously — not an {@link Exception}. */
		@JsonRpcMethod("throwError")
		Promise<String> throwError();

		/** Declared {@code Promise<Void>} but returns {@code null} — the notification route to FR-046. */
		@JsonRpcNotification("nullNotify")
		Promise<Void> nullNotify(@JsonRpcParam("value") String value);

		/** Fails with an application code just outside the reserved range, {@code data} included. */
		@JsonRpcMethod("boundary")
		Promise<String> boundary() throws JsonRpcException;
	}

	public static final class HostileApiImpl implements HostileApi {
		/** The message of the thrown {@link Error} — must never reach the wire (FR-048). */
		public static final String SECRET = "hostile Error payload hunter2";

		private final List<String> invocations = new ArrayList<>();

		@Override
		public Promise<String> slow() {
			invocations.add("slow()");
			try {
				Thread.sleep(300);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new IllegalStateException("interrupted", e);
			}
			return Promise.of("after-sleep");
		}

		@Override
		public Promise<String> fast() {
			invocations.add("fast()");
			return Promise.of("fast");
		}

		@Override
		public Promise<String> throwError() {
			invocations.add("throwError()");
			throw new Error(SECRET);
		}

		@Override
		public Promise<Void> nullNotify(String value) {
			invocations.add("nullNotify(" + value + ')');
			return null;
		}

		@Override
		public Promise<String> boundary() throws JsonRpcException {
			invocations.add("boundary()");
			byte[] data = "{\"retryAfter\":60}".getBytes(US_ASCII);
			throw new JsonRpcException(JsonRpcErrors.of(-31999, "application boundary",
				JsonRpcPayload.raw(data, 0, data.length)));
		}

		public List<String> invocations() {
			return invocations;
		}
	}

	/** The inter-interface half of B5: a second interface claiming {@code user.get}. */
	@JsonRpcService("user")
	public interface ShadowingApi {
		@JsonRpcMethod("get")
		Promise<String> get(@JsonRpcParam("id") long id);
	}

	public static final class ShadowingApiImpl implements ShadowingApi {
		@Override
		public Promise<String> get(long id) {
			return Promise.of("shadow-" + id);
		}
	}

	/** The intra-interface half of B5: two methods resolving to one wire name. */
	@JsonRpcService("dup")
	public interface DuplicateWireApi {
		@JsonRpcMethod("get")
		Promise<String> getOne(@JsonRpcParam("id") long id);

		@JsonRpcMethod("get")
		Promise<String> getAnother(@JsonRpcParam("id") long id);
	}

	public static final class DuplicateWireApiImpl implements DuplicateWireApi {
		@Override
		public Promise<String> getOne(long id) {
			return Promise.of("one-" + id);
		}

		@Override
		public Promise<String> getAnother(long id) {
			return Promise.of("another-" + id);
		}
	}

	/**
	 * The B7 documentation subject: an inspector that RETAINS every {@code onMethodNotFound} name — legal
	 * at the type level, forbidden by the contract (FR-034). The dispatcher must neither prevent nor
	 * facilitate it.
	 */
	private static final class RetainingInspector extends AbstractInspector<JsonRpcDispatcher.Inspector>
		implements JsonRpcDispatcher.Inspector {
		private final List<String> receivedNames = new ArrayList<>();

		@Override
		public void onRequest(JsonRpcMethodDescriptor descriptor) {}

		@Override
		public void onResponse(JsonRpcMethodDescriptor descriptor, long durationMillis) {}

		@Override
		public void onError(JsonRpcMethodDescriptor descriptor, int errorCode, long durationMillis) {}

		@Override
		public void onMethodNotFound(String requestedName) {
			receivedNames.add(requestedName); // the retention the contract forbids — this is the implementer's fault
		}

		@Override
		public void onMalformed() {}
	}

	/** The B6 subject: spins real CPU before throwing from every callback, {@code AssertionError} included. */
	private static final class SpinningHostileInspector extends AbstractInspector<JsonRpcDispatcher.Inspector>
		implements JsonRpcDispatcher.Inspector {
		@Override
		public void onRequest(JsonRpcMethodDescriptor descriptor) {spinAndThrow();}

		@Override
		public void onResponse(JsonRpcMethodDescriptor descriptor, long durationMillis) {spinAndThrow();}

		@Override
		public void onError(JsonRpcMethodDescriptor descriptor, int errorCode, long durationMillis) {spinAndThrow();}

		@Override
		public void onMethodNotFound(String requestedName) {spinAndThrow();}

		@Override
		public void onMalformed() {spinAndThrow();}

		private static void spinAndThrow() {
			long until = System.nanoTime() + 20_000_000; // ~20ms of pure CPU — bounded, see B6's Javadoc
			while (System.nanoTime() < until) {
				// spin
			}
			throw new AssertionError("hostile after spinning");
		}
	}

	// ---------------------------------------------------------------------------------------------------
	// Helpers.
	// ---------------------------------------------------------------------------------------------------

	private static String dispatch(JsonRpcDispatcher dispatcher, String document) {
		byte[] response = await(dispatcher.dispatch(document.getBytes(UTF_8)));
		assertNotNull(response);
		return new String(response, UTF_8);
	}

	private static InMemoryTransport.Listener documents(List<String> sink) {
		return new InMemoryTransport.Listener() {
			@Override
			public void onDocument(byte[] document) {
				sink.add(new String(document, UTF_8));
			}

			@Override
			public void onClosed(Exception e) {}
		};
	}

	private static byte[] utf8(String s) {
		return s.getBytes(UTF_8);
	}
}
