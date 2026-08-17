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

import io.activej.inject.Key;
import io.activej.jmx.DynamicMBeanFactory;
import io.activej.jmx.JmxBeanSettings;
import io.activej.jmx.JmxRegistry;
import io.activej.json.JsonCodecFactory;
import io.activej.jsonrpc.JsonRpcError;
import io.activej.jsonrpc.JsonRpcErrors;
import io.activej.jsonrpc.JsonRpcException;
import io.activej.jsonrpc.JsonRpcLimits;
import io.activej.jsonrpc.JsonRpcPayload;
import io.activej.jsonrpc.service.fixtures.UserApi;
import io.activej.jsonrpc.service.fixtures.UserApiImpl;
import io.activej.promise.Promise;
import io.activej.reactor.Reactor;
import io.activej.test.EventloopThread;
import io.activej.test.rules.ActivePromisesRule;
import io.activej.test.rules.ByteBufRule;
import io.activej.test.rules.EventloopRule;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;

import javax.management.Attribute;
import javax.management.MBeanAttributeInfo;
import javax.management.MBeanServer;
import javax.management.MBeanServerFactory;
import javax.management.ObjectName;
import javax.management.openmbean.CompositeData;
import javax.management.openmbean.TabularData;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static io.activej.promise.TestUtils.await;
import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Adversarial round 1, domain C — the JMX surface of {@link JsonRpcDispatcher} against a <b>real</b>
 * {@link MBeanServer} (plan rows C1…C7, plus G3's allocation note). The oracle is
 * {@code contracts/jmx-attributes.md} and Spec §Security Considerations, never "what the code does today".
 * <p>
 * C7 (exact attribute-name stability, {@code _totalCount} twins included) is already proven by
 * {@code JsonRpcJmxAttributesTest.attributeNamesAreExactlyTheContractSurface} — the strict set-equality
 * there means any rename breaks the build; no duplicate test is written.
 * <p>
 * <b>Threading note for C4/C5:</b> the {@link DynamicMBeanFactory} reads the bean <i>on the calling
 * thread</i> (the dispatcher's {@code getStats()} is deliberately not reactor-guarded, and JMX reads are
 * the "JMX thread" it speaks of), so the reader threads below genuinely race the reactor thread. The
 * oracle calls this out as safe: the statistics table is an immutable map fixed at {@code build()}
 * (FR-034) and the {@code EventStats} counters are benign-race. C5 has no refresh cycle, so its final
 * totals are exact; C4 runs the refresh cycle concurrently and therefore asserts exactness only on the
 * counters the cycle never touches — a refreshed bucket can be lost or double-counted when
 * {@code EventStats.refresh()} races {@code recordEvent()} (platform property, anticipated by the plan's
 * "benign-race" wording), which is exactly why the oracle forbids asserting intermediate values (WI-17).
 * <p>
 * <b>Finding F-1 (C6):</b> {@code MBeanServer.setAttribute} on this bean's read-only attributes does not
 * fail fast — the platform routes it to the {@code DynamicMBean}, whose reactor-hop + {@code latch.await()}
 * used to self-deadlock when the dispatcher's reactor was the calling thread. {@code ReactorJmxBeanAdapter}
 * now runs a same-thread command inline instead of hopping, so the refusal ({@code SetterException} →
 * {@code MBeanException}) fires from the bean's own thread exactly as from any other; C6 tests both shapes.
 */
public class JsonRpcJmxAdversarialTest {
	@ClassRule
	public static final EventloopRule eventloopRule = new EventloopRule();
	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();
	@ClassRule
	public static final ActivePromisesRule activePromisesRule = new ActivePromisesRule();

	/** The contract's attribute surface, §2 plus the platform's {@code extraSubAttributes} twins. */
	private static final Set<String> CONTRACT_ATTRIBUTES = Set.of(
		"methodStats", "totalRequests", "totalErrors", "methodNotFound", "malformedDocuments",
		"registeredMethods", "maxBatchSize", "maxJsonDepth",
		"totalRequests_totalCount", "totalErrors_totalCount", "methodNotFound_totalCount",
		"malformedDocuments_totalCount");

	// C1's marker battery — each is a string that must never appear in any attribute rendering
	private static final String PAYLOAD_MARKER = "payload-marker-xyz";
	private static final String ID_MARKER = "id-marker-xyz";
	private static final String UNKNOWN_METHOD_MARKER = "no.such.marker-xyz";
	private static final String TOP_CAUSE_MARKER = "top-cause-marker-xyz";
	private static final String MID_CAUSE_MARKER = "mid-cause-marker-xyz";
	private static final String ROOT_CAUSE_MARKER = "root-cause-marker-xyz";
	private static final String APPLICATION_MESSAGE_MARKER = "application-message-marker-xyz";
	private static final String ERROR_DATA_MARKER = "error-data-marker-xyz";

	private MBeanServer mbs;
	private JmxRegistry jmxRegistry;
	private JsonRpcDispatcher dispatcher;
	private JsonRpcDispatcher.JmxInspector inspector;

	@Before
	public void setUp() {
		mbs = MBeanServerFactory.newMBeanServer();
		jmxRegistry = JmxRegistry.create(mbs, DynamicMBeanFactory.create());
		inspector = new JsonRpcDispatcher.JmxInspector();
		dispatcher = JsonRpcDispatcher.builder(Reactor.getCurrentReactor())
			.withService(UserApi.class, new UserApiImpl())
			.withService(LeakApi.class, new LeakApiImpl())
			.withInspector(inspector)
			.build();
		// registration is per-test: the C2 variants need the unqualified ObjectName free
	}

	private void register(JsonRpcDispatcher dispatcher) {
		jmxRegistry.registerSingleton(Key.of(JsonRpcDispatcher.class), dispatcher, JmxBeanSettings.create());
	}

	private ObjectName dispatcherName() throws Exception {
		Set<ObjectName> names = mbs.queryNames(new ObjectName("io.activej.jsonrpc.service:type=JsonRpcDispatcher"), null);
		assertEquals(1, names.size());
		return names.iterator().next();
	}

	private void dispatch(String document) {
		await(dispatcher.dispatch(document.getBytes(UTF_8)));
	}

	// ---------------------------------------------------------------------------------------------------
	// C1 — the disclosure battery, extended with the exception CAUSE CHAIN and error.data content. The
	// existing battery (payload, id, unknown method name, exception message, application error message)
	// lives in JsonRpcJmxAttributesTest.noAttributeCarriesPayloadIdsWireNamesOrExceptionText; this is the
	// same recursive read of ALL attributes against the markers the peer WOULD see on the wire.
	// ---------------------------------------------------------------------------------------------------

	@Test
	public void noAttributeCarriesCauseChainOrErrorDataMarkers() throws Exception {
		register(dispatcher);
		ObjectName name = dispatcherName();

		// traffic that carries every marker on the wire — the peer sees all of these
		dispatch("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"user.get\",\"params\":[42]}");
		dispatch("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"leak.echo\",\"params\":{\"value\":\"" + PAYLOAD_MARKER + "\"}}");
		dispatch("{\"jsonrpc\":\"2.0\",\"id\":\"" + ID_MARKER + "\",\"method\":\"" + UNKNOWN_METHOD_MARKER + "\"}");
		dispatch("{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"leak.causeChain\"}");
		dispatch("{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"leak.dataCarrier\"}");

		List<String> renderings = new ArrayList<>();
		for (String attributeName : attributeNames(mbs, name)) {
			Object value = mbs.getAttribute(name, attributeName);
			collectRenderings(value, renderings);
		}
		String all = String.join("\n", renderings);

		// payload value, id, unmatched wire name, exception message at EVERY level of the cause chain,
		// application error message, and the error.data content — none may appear
		assertFalse("payload marker leaked: " + all, all.contains(PAYLOAD_MARKER));
		assertFalse("id leaked: " + all, all.contains(ID_MARKER));
		assertFalse("unmatched wire method name leaked: " + all, all.contains(UNKNOWN_METHOD_MARKER));
		assertFalse("top cause message leaked: " + all, all.contains(TOP_CAUSE_MARKER));
		assertFalse("mid cause message leaked: " + all, all.contains(MID_CAUSE_MARKER));
		assertFalse("root cause message leaked: " + all, all.contains(ROOT_CAUSE_MARKER));
		assertFalse("application error message leaked: " + all, all.contains(APPLICATION_MESSAGE_MARKER));
		assertFalse("error.data content leaked: " + all, all.contains(ERROR_DATA_MARKER));
		assertFalse("exception class name leaked: " + all, all.contains("IllegalStateException"));

		// the only method-name strings present are the REGISTERED wire names
		TabularData methodStats = (TabularData) mbs.getAttribute(name, "methodStats");
		Set<String> rowKeys = new HashSet<>();
		for (Object rowKey : methodStats.keySet()) {
			rowKeys.add(((List<?>) rowKey).get(0).toString());
		}
		assertEquals(dispatcher.wireNames(), rowKeys);

		// out-of-list application codes never appear as errorsByCode keys (FR-033a): 1003 from
		// dataCarrier and -31999-class codes land in otherErrors
		TabularData errorsByCode = (TabularData) ((CompositeData) methodStats.get(new Object[]{"leak.dataCarrier"}))
			.get("errorsByCode");
		assertFalse("application code must not appear as an errorsByCode key: " + errorsByCode.keySet(),
			errorsByCode.keySet().stream().anyMatch(k -> ((List<?>) k).get(0).toString().equals("1003")));
		assertEquals(9, errorsByCode.size());
	}

	// ---------------------------------------------------------------------------------------------------
	// C2 — registration variants. With no inspector (or a non-JmxInspector one), getStats() is null and
	// the whole surface reads as ABSENT — never an exception. Double registration of the same bean is a
	// logged ObjectName collision, never a startup crash.
	// ---------------------------------------------------------------------------------------------------

	@Test
	public void aDispatcherWithoutAnInspectorReadsAsAbsentAndNeverThrows() throws Exception {
		JsonRpcDispatcher bare = JsonRpcDispatcher.builder(Reactor.getCurrentReactor())
			.withService(UserApi.class, new UserApiImpl())
			.build();
		assertNull("contract §6: getStats() is null with no inspector installed", bare.getStats());

		register(bare);
		ObjectName name = dispatcherName();

		for (String attribute : CONTRACT_ATTRIBUTES) {
			Object value;
			try {
				value = mbs.getAttribute(name, attribute);
			} catch (Exception e) {
				throw new AssertionError("reading '" + attribute + "' from an uninspected dispatcher must " +
										 "not throw", e);
			}
			assertTrue("'" + attribute + "' must read as absent, was: " + value, value == null);
		}
	}

	@Test
	public void aNonJmxInspectorReadsAsAbsentExactlyLikeNoInspector() throws Exception {
		JsonRpcDispatcher plain = JsonRpcDispatcher.builder(Reactor.getCurrentReactor())
			.withService(UserApi.class, new UserApiImpl())
			.withInspector(new PlainInspector())
			.build();
		assertNull("a non-JmxInspector resolves to no stats (BaseInspector.lookup)", plain.getStats());

		register(plain);
		ObjectName name = dispatcherName();

		for (String attribute : CONTRACT_ATTRIBUTES) {
			Object value;
			try {
				value = mbs.getAttribute(name, attribute);
			} catch (Exception e) {
				throw new AssertionError("reading '" + attribute + "' must not throw", e);
			}
			assertTrue("'" + attribute + "' must read as absent, was: " + value, value == null);
		}
	}

	@Test
	public void aDoubleRegistrationIsALoggedCollisionNotAStartupCrash() throws Exception {
		register(dispatcher);
		// the second registration hits InstanceAlreadyExistsException inside JmxRegistry, which logs and
		// returns — startup continues (boot-jmx's documented registration-failure behaviour)
		register(dispatcher);

		Set<ObjectName> names =
			mbs.queryNames(new ObjectName("io.activej.jsonrpc.service:type=JsonRpcDispatcher"), null);
		assertEquals("exactly one MBean stays registered", 1, names.size());

		// and the surviving registration still serves reads
		dispatch("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"user.get\",\"params\":[42]}");
		assertEquals(1L, mbs.getAttribute(names.iterator().next(), "totalRequests_totalCount"));
	}

	// ---------------------------------------------------------------------------------------------------
	// C3 — a JmxInspector used WITHOUT a dispatcher (initialize never called) fails loudly on the two
	// callbacks that consult the table, naming the wire name (C008) — never a silent NPE. The aggregate
	// callbacks never touch the table and must not throw.
	// ---------------------------------------------------------------------------------------------------

	@Test
	public void aNeverInitializedJmxInspectorFailsLoudlyNamingTheWireName() {
		JsonRpcDispatcher.JmxInspector orphan = new JsonRpcDispatcher.JmxInspector();
		JsonRpcMethodDescriptor descriptor =
			JsonRpcServiceContract.of(UserApi.class, JsonCodecFactory.defaultInstance()).byWireName("user.get");
		assertNotNull(descriptor);

		try {
			orphan.onResponse(descriptor, 5);
			fail("a never-initialized inspector must fail loudly, not silently");
		} catch (IllegalStateException e) {
			assertTrue("the refusal must name the wire name, got: " + e.getMessage(),
				e.getMessage().contains("user.get"));
			assertTrue("the refusal must point at initialize, got: " + e.getMessage(),
				e.getMessage().contains("initialize"));
		}

		try {
			orphan.onError(descriptor, JsonRpcErrors.INTERNAL_ERROR.code(), 5);
			fail("a never-initialized inspector must fail loudly, not silently");
		} catch (IllegalStateException e) {
			assertTrue(e.getMessage(), e.getMessage().contains("user.get"));
		}

		// the aggregate callbacks never look the table up — they must keep working
		orphan.onRequest(descriptor);
		orphan.onMethodNotFound("no.such");
		orphan.onMalformed();
	}

	// ---------------------------------------------------------------------------------------------------
	// C4 — aggregation mid-race: two workers dispatching continuously while reader threads (and a
	// simulated 1s refresh cycle) read every counter. Oracle: no exception during the race, final values
	// stable after quiescence, NEVER an intermediate value asserted (WI-17).
	// ---------------------------------------------------------------------------------------------------

	/**
	 * C4 — aggregation mid-race: two workers dispatching continuously while reader threads and the 1s
	 * refresh cycle read every counter. The oracle forbids asserting any value read <i>during</i> the race
	 * (WI-17): {@code EventStats} is documented benign-race, and its {@code refresh()} moving the pending
	 * bucket into {@code totalCount} can lose or double-count a bucket when it races {@code recordEvent()}
	 * on the reactor thread — the sums an operator reads mid-cycle are therefore not exact, and the test
	 * must not pretend they are. What IS asserted: no exception during the race, the non-refreshed
	 * aggregate counters are exact after quiescence (the SC-008-style anchor), and every value is stable
	 * once a full refresh cycle has passed.
	 */
	@Test
	public void aggregationMidRaceNeverThrowsAndIsStableAfterQuiescence() throws Exception {
		int perWorker = 100_000;
		EventloopThread firstWorker = EventloopThread.create("adversarial-worker-1");
		EventloopThread secondWorker = EventloopThread.create("adversarial-worker-2");
		try {
			UserApiImpl firstImpl = new UserApiImpl();
			UserApiImpl secondImpl = new UserApiImpl();
			JsonRpcDispatcher d1 = firstWorker.submit(() -> workerDispatcher(firstWorker.eventloop(), firstImpl));
			JsonRpcDispatcher d2 = secondWorker.submit(() -> workerDispatcher(secondWorker.eventloop(), secondImpl));
			JsonRpcDispatcher.JmxInspector i1 = d1.getStats();
			JsonRpcDispatcher.JmxInspector i2 = d2.getStats();

			// readers + the 1s refresh cycle start BEFORE the dispatch hammers
			AtomicInteger readFailures = new AtomicInteger();
			AtomicBoolean stop = new AtomicBoolean(false);
			List<Thread> readers = new ArrayList<>();
			readers.add(reader("c4-reader-a", i1, i2, stop, readFailures));
			readers.add(reader("c4-reader-b", i1, i2, stop, readFailures));
			readers.add(refreshSimulator("c4-refresh", i1, i2, stop, readFailures));

			// both workers hammer concurrently (each on its own loop thread); the paced hammer keeps the
			// race alive for the readers
			Thread hammerFirst = new Thread(() -> firstWorker.submit(() -> hammer(d1, perWorker)));
			Thread hammerSecond = new Thread(() -> secondWorker.submit(() -> hammer(d2, perWorker)));
			hammerFirst.start();
			hammerSecond.start();
			hammerFirst.join();
			hammerSecond.join();

			Thread.sleep(1_500);
			stop.set(true);
			for (Thread reader : readers) reader.join();

			assertEquals("no read may fail during the race", 0, readFailures.get());

			// the workload really ran — the impl-level anchor, untouched by any refresh race
			assertEquals(perWorker, firstImpl.invocations().size());
			assertEquals(perWorker, secondImpl.invocations().size());

			// the aggregate counters are never refreshed by the cycle, so they are exact after quiescence
			assertEquals(perWorker, i1.getTotalRequests().getTotalCount());
			assertEquals(perWorker, i2.getTotalRequests().getTotalCount());

			// every value — refreshed rows included — is STABLE once the race is over and a full cycle
			// has passed: two reads of the same counter do not move
			long stableA = i1.getTotalRequests().getTotalCount();
			long stableB = i1.getMethodStats().get("user.get").getSuccessfulRequests().getTotalCount();
			Thread.sleep(1_100);
			assertEquals("aggregate must be stable after a full refresh cycle", stableA,
				i1.getTotalRequests().getTotalCount());
			assertEquals("refreshed row must be stable after a full refresh cycle", stableB,
				i1.getMethodStats().get("user.get").getSuccessfulRequests().getTotalCount());
		} finally {
			firstWorker.close();
			secondWorker.close();
		}
	}

	// ---------------------------------------------------------------------------------------------------
	// C5 — four reader threads against the REAL MBeanServer while a worker dispatches: no corruption, no
	// exception (immutable map + benign-race EventStats — the oracle's exact wording).
	// ---------------------------------------------------------------------------------------------------

	@Test
	public void fourReaderThreadsAgainstTheRealMBeanServerCorruptNothing() throws Exception {
		int dispatched = 100_000;
		EventloopThread worker = EventloopThread.create("adversarial-c5-worker");
		try {
			UserApiImpl implementation = new UserApiImpl();
			JsonRpcDispatcher d = worker.submit(() -> workerDispatcher(worker.eventloop(), implementation));
			JsonRpcDispatcher.JmxInspector i = d.getStats();
			MBeanServer server = MBeanServerFactory.newMBeanServer();
			JmxRegistry registry = JmxRegistry.create(server, DynamicMBeanFactory.create());
			registry.registerSingleton(Key.of(JsonRpcDispatcher.class), d, JmxBeanSettings.create());
			ObjectName name = server.queryNames(
				new ObjectName("io.activej.jsonrpc.service:type=JsonRpcDispatcher"), null).iterator().next();

			List<String> attributeNames = new ArrayList<>(attributeNames(server, name));
			assertTrue("the contract surface must be present: " + attributeNames,
				attributeNames.containsAll(CONTRACT_ATTRIBUTES));

			AtomicInteger readFailures = new AtomicInteger();
			AtomicBoolean stop = new AtomicBoolean(false);
			List<Thread> readers = new ArrayList<>();
			for (int t = 0; t < 4; t++) {
				readers.add(mbeanReader("c5-reader-" + t, server, name, attributeNames, stop, readFailures));
			}

			worker.submit(() -> hammer(d, dispatched));

			Thread.sleep(1_500);
			stop.set(true);
			for (Thread reader : readers) reader.join();

			assertEquals("no MBean read may fail during dispatch", 0, readFailures.get());
			assertEquals(dispatched, i.getTotalRequests().getTotalCount());
			assertEquals((long) dispatched, server.getAttribute(name, "totalRequests_totalCount"));
			TabularData methodStats = (TabularData) server.getAttribute(name, "methodStats");
			assertEquals("the row set is the closed registered set, unchanged by the race",
				i.getMethodStats().size(), methodStats.size());
		} finally {
			worker.close();
		}
	}

	// ---------------------------------------------------------------------------------------------------
	// C6 — read-only attributes: setAttribute on maxBatchSize / maxJsonDepth is refused, and never
	// mutates the effective JsonRpcLimits (DI-5).
	// ---------------------------------------------------------------------------------------------------

	/**
	 * C6 — read-only attributes. The refusal must come from the machinery, and {@code JsonRpcLimits} must
	 * never mutate. The dispatcher's reactor must be ALIVE on its own thread for the refusal to work: the
	 * platform routes {@code setAttribute} straight to the {@code DynamicMBean}, whose
	 * {@code ReactorJmxBeanAdapter} executes the setter on the bean's reactor and then {@code latch.await()}s.
	 * Finding F-1 was the same-thread shape — the calling thread being the reactor itself — which deadlocked:
	 * the hop never ran and the await never completed. The adapter now runs a same-thread command inline
	 * (the thread the hop would execute on), so that shape refuses exactly like the cross-thread one; both
	 * are exercised below, the same-thread one through {@code EventloopThread.submit}, whose return at all
	 * is the no-hang proof.
	 */
	@Test
	public void setAttributeOnReadOnlyLimitsIsRefusedAndNeverMutates() throws Exception {
		EventloopThread worker = EventloopThread.create("adversarial-c6-worker");
		try {
			JsonRpcDispatcher live = worker.submit(() -> workerDispatcher(worker.eventloop(), new UserApiImpl()));
			MBeanServer server = MBeanServerFactory.newMBeanServer();
			JmxRegistry registry = JmxRegistry.create(server, DynamicMBeanFactory.create());
			registry.registerSingleton(Key.of(JsonRpcDispatcher.class), live, JmxBeanSettings.create());
			ObjectName name = server.queryNames(
				new ObjectName("io.activej.jsonrpc.service:type=JsonRpcDispatcher"), null).iterator().next();

			// from another thread: refused, never mutates
			assertSetRefused(server, name, "maxBatchSize", 42);
			assertEquals("the observed bound is unchanged",
				JsonRpcLimits.MAX_BATCH_SIZE, server.getAttribute(name, "maxBatchSize"));
			assertEquals("the process-wide limit itself is untouched (DI-5)", 100, JsonRpcLimits.MAX_BATCH_SIZE);

			assertSetRefused(server, name, "maxJsonDepth", 4);
			assertEquals(JsonRpcLimits.MAX_JSON_DEPTH, server.getAttribute(name, "maxJsonDepth"));
			assertEquals(64, JsonRpcLimits.MAX_JSON_DEPTH);

			// from the bean's own reactor thread: refused the same way, never mutates, no deadlock
			// (F-1 fix — the submit returning at all proves the set did not hang the reactor)
			worker.submit(() -> {
				assertSetRefused(server, name, "maxBatchSize", 42);
				assertSetRefused(server, name, "maxJsonDepth", 4);
			});
			assertEquals("the observed bound is unchanged after a same-thread set",
				JsonRpcLimits.MAX_BATCH_SIZE, server.getAttribute(name, "maxBatchSize"));
			assertEquals("the process-wide limit itself is untouched (DI-5)", 100, JsonRpcLimits.MAX_BATCH_SIZE);
			assertEquals(64, JsonRpcLimits.MAX_JSON_DEPTH);
		} finally {
			worker.close();
		}
	}

	private static void assertSetRefused(MBeanServer server, ObjectName name, String attribute, Object value) {
		try {
			server.setAttribute(name, new Attribute(attribute, value));
			fail("setAttribute(" + attribute + ") must be refused on a read-only attribute");
		} catch (Exception expected) {
			// MBeanException wrapping the SetterException — or any refusal — never a mutation
		}
	}

	// ---------------------------------------------------------------------------------------------------
	// G3 — FR-031's allocation claim, asserted in its observable form: the statistics TABLE is built once
	// and never reallocated, copied or grown per request; recording is counter updates on pre-existing
	// objects. The guard shape itself (a null check, then a try/catch, no lambda) is verified by reading
	// the dispatcher's notify* implementations — documented here, asserted by identity below.
	// ---------------------------------------------------------------------------------------------------

	@Test
	public void thePerRequestTableIsNeverReallocatedOrGrown() {
		Map<String, JsonRpcMethodStats> before = inspector.getMethodStats();
		JsonRpcMethodStats row = before.get("user.get");
		Map<Integer, io.activej.jmx.stats.EventStats> errorsByCode = row.getErrorsByCode();

		for (int i = 0; i < 1_000; i++) {
			dispatch("{\"jsonrpc\":\"2.0\",\"id\":" + i + ",\"method\":\"user.get\",\"params\":[" + i + "]}");
			dispatch("{\"jsonrpc\":\"2.0\",\"id\":" + (i + 10_000) + ",\"method\":\"leak.dataCarrier\"}");
			dispatch("{\"jsonrpc\":\"2.0\",\"id\":" + (i + 20_000) + ",\"method\":\"no.such." + i + "\"}");
		}

		assertSame("the map is never reallocated per request (FR-034)", before, inspector.getMethodStats());
		assertSame("the row is never reallocated per request", row, inspector.getMethodStats().get("user.get"));
		assertSame("the errorsByCode map is never reallocated per request (FR-033a)",
			errorsByCode, row.getErrorsByCode());
		assertEquals("the row set stays the closed registered set", dispatcher.wireNames(), before.keySet());
	}

	// ---------------------------------------------------------------------------------------------------
	// Fixtures.
	// ---------------------------------------------------------------------------------------------------

	@JsonRpcService("leak")
	public interface LeakApi {
		/** Echoes its argument — the payload marker travels on the wire and must stay out of JMX. */
		@JsonRpcMethod("echo")
		Promise<String> echo(@JsonRpcParam("value") String value);

		/** Fails with a three-deep cause chain, every level carrying its own marker. */
		@JsonRpcMethod("causeChain")
		Promise<String> causeChain();

		/** Fails with an application error whose message and data both carry markers. */
		@JsonRpcMethod("dataCarrier")
		Promise<String> dataCarrier() throws JsonRpcException;
	}

	public static final class LeakApiImpl implements LeakApi {
		@Override
		public Promise<String> echo(String value) {
			return Promise.of(value);
		}

		@Override
		public Promise<String> causeChain() {
			IllegalStateException root = new IllegalStateException(ROOT_CAUSE_MARKER);
			IllegalStateException middle = new IllegalStateException(MID_CAUSE_MARKER, root);
			throw new IllegalStateException(TOP_CAUSE_MARKER, middle);
		}

		@Override
		public Promise<String> dataCarrier() throws JsonRpcException {
			byte[] data = ("{\"marker\":\"" + ERROR_DATA_MARKER + "\"}").getBytes(US_ASCII);
			JsonRpcError error = JsonRpcErrors.of(1003, "application error " + APPLICATION_MESSAGE_MARKER,
				JsonRpcPayload.raw(data, 0, data.length));
			return Promise.ofException(new JsonRpcException(error, APPLICATION_MESSAGE_MARKER));
		}
	}

	/** A conforming but JMX-free inspector — the C2 "non-JmxInspector" subject. */
	private static final class PlainInspector extends io.activej.common.inspector.AbstractInspector<JsonRpcDispatcher.Inspector>
		implements JsonRpcDispatcher.Inspector {
		@Override
		public void onRequest(JsonRpcMethodDescriptor descriptor) {}

		@Override
		public void onResponse(JsonRpcMethodDescriptor descriptor, long durationMillis) {}

		@Override
		public void onError(JsonRpcMethodDescriptor descriptor, int errorCode, long durationMillis) {}

		@Override
		public void onMethodNotFound(String requestedName) {}

		@Override
		public void onMalformed() {}
	}

	// ---------------------------------------------------------------------------------------------------
	// C4/C5 machinery.
	// ---------------------------------------------------------------------------------------------------

	/** One worker's dispatcher: UserApi plus LeakApi, both with a JmxInspector. */
	private static JsonRpcDispatcher workerDispatcher(Reactor reactor, UserApiImpl userImplementation) {
		return JsonRpcDispatcher.builder(reactor)
			.withService(UserApi.class, userImplementation)
			.withService(LeakApi.class, new LeakApiImpl())
			.withInspector(new JsonRpcDispatcher.JmxInspector())
			.build();
	}

	/** Synchronous dispatch hammer — runs on the worker's own loop thread, paced to keep the race alive. */
	private static void hammer(JsonRpcDispatcher dispatcher, int count) {
		for (int i = 0; i < count; i++) {
			dispatcher.dispatch(
				("{\"jsonrpc\":\"2.0\",\"id\":" + i + ",\"method\":\"user.get\",\"params\":[" + i + "]}")
					.getBytes(UTF_8));
			if (i % 100 == 0) {
				try {
					Thread.sleep(1);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					throw new IllegalStateException("interrupted", e);
				}
			}
		}
	}

	private static Thread reader(String name, JsonRpcDispatcher.JmxInspector first,
		JsonRpcDispatcher.JmxInspector second, AtomicBoolean stop, AtomicInteger failures) {
		Thread thread = new Thread(() -> {
			try {
				while (!stop.get()) {
					for (JsonRpcDispatcher.JmxInspector inspector : List.of(first, second)) {
						inspector.getTotalRequests().getTotalCount();
						inspector.getTotalErrors().getTotalCount();
						inspector.getMethodNotFound().getTotalCount();
						for (JsonRpcMethodStats stats : inspector.getMethodStats().values()) {
							stats.getSuccessfulRequests().getTotalCount();
							stats.getFailedRequests().getTotalCount();
							stats.getOtherErrors().getTotalCount();
							stats.getErrorsByCode().keySet();
						}
					}
				}
			} catch (Throwable t) {
				failures.incrementAndGet();
			}
		}, name);
		thread.setDaemon(true);
		thread.start();
		return thread;
	}

	/** The 1s refresh cycle, simulated: every row's JmxRefreshable is refreshed once per second. */
	private static Thread refreshSimulator(String name, JsonRpcDispatcher.JmxInspector first,
		JsonRpcDispatcher.JmxInspector second, AtomicBoolean stop, AtomicInteger failures) {
		Thread thread = new Thread(() -> {
			try {
				long timestamp = System.currentTimeMillis();
				while (!stop.get()) {
					timestamp += 1_000;
					for (JsonRpcDispatcher.JmxInspector inspector : List.of(first, second)) {
						for (JsonRpcMethodStats stats : inspector.getMethodStats().values()) {
							stats.refresh(timestamp);
						}
					}
					Thread.sleep(1_000);
				}
			} catch (Throwable t) {
				failures.incrementAndGet();
			}
		}, name);
		thread.setDaemon(true);
		thread.start();
		return thread;
	}

	private static Thread mbeanReader(String name, MBeanServer server, ObjectName objectName,
		List<String> attributeNames, AtomicBoolean stop, AtomicInteger failures) {
		Thread thread = new Thread(() -> {
			try {
				while (!stop.get()) {
					for (String attribute : attributeNames) {
						try {
							server.getAttribute(objectName, attribute);
						} catch (Exception e) {
							failures.incrementAndGet();
						}
					}
				}
			} catch (Throwable t) {
				failures.incrementAndGet();
			}
		}, name);
		thread.setDaemon(true);
		thread.start();
		return thread;
	}

	// ---------------------------------------------------------------------------------------------------
	// Helpers.
	// ---------------------------------------------------------------------------------------------------

	private Set<String> attributeNames(MBeanServer server, ObjectName name) throws Exception {
		Set<String> names = new HashSet<>();
		for (MBeanAttributeInfo attribute : server.getMBeanInfo(name).getAttributes()) {
			names.add(attribute.getName());
		}
		return names;
	}

	/** Flattens any attribute value — including nested {@code CompositeData}/{@code TabularData} — to strings. */
	private static void collectRenderings(Object value, List<String> renderings) {
		if (value instanceof TabularData tabularData) {
			for (Object rowKey : tabularData.keySet()) {
				collectRenderings(tabularData.get(((List<?>) rowKey).toArray()), renderings);
			}
		} else if (value instanceof CompositeData compositeData) {
			for (Object key : compositeData.getCompositeType().keySet()) {
				collectRenderings(compositeData.get((String) key), renderings);
			}
		} else {
			renderings.add(String.valueOf(value));
		}
	}
}
