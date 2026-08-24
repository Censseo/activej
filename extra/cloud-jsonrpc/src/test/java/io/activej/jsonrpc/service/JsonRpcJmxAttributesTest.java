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
import io.activej.inject.Key;
import io.activej.jmx.DynamicMBeanFactory;
import io.activej.jmx.JmxBeanSettings;
import io.activej.jmx.JmxRegistry;
import io.activej.jsonrpc.JsonRpcError;
import io.activej.jsonrpc.JsonRpcErrors;
import io.activej.jsonrpc.JsonRpcLimits;
import io.activej.jsonrpc.schema.OpenRpcInfo;
import io.activej.jsonrpc.service.fixtures.FailingApi;
import io.activej.jsonrpc.service.fixtures.FailingApiImpl;
import io.activej.jsonrpc.service.fixtures.SlowApi;
import io.activej.jsonrpc.service.fixtures.SlowApiImpl;
import io.activej.jsonrpc.service.fixtures.UserApi;
import io.activej.jsonrpc.service.fixtures.UserApiImpl;
import io.activej.promise.Promise;
import io.activej.reactor.Reactor;
import io.activej.test.rules.ActivePromisesRule;
import io.activej.test.rules.ByteBufRule;
import io.activej.test.rules.EventloopRule;
import org.jetbrains.annotations.Nullable;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;

import javax.management.MBeanAttributeInfo;
import javax.management.MBeanInfo;
import javax.management.MBeanServer;
import javax.management.MBeanServerFactory;
import javax.management.ObjectName;
import javax.management.openmbean.CompositeData;
import javax.management.openmbean.TabularData;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static io.activej.promise.TestUtils.await;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * User story 2's JMX surface against a <b>real {@link MBeanServer}</b> (FR-030…FR-038).
 * <p>
 * This is the test that resolves research unknowns U1, U2 and U6a: whether {@code JmxModule}'s machinery
 * walks a nested inspector reached through {@code @JmxAttribute(name = "")}, what that flattening does to
 * the namespace, and whether a {@code Map}-valued node survives it. Findings:
 * <ul>
 *     <li><b>U1 (discovery)</b> — registration is explicit ({@code JmxRegistry.registerSingleton}); the
 *     nested {@code JmxInspector} is reached because the dispatcher's {@code getStats()} is a
 *     {@code @JmxAttribute} getter returning it.</li>
 *     <li><b>U2 (flattening)</b> — an empty attribute name emits the pojo's children unprefixed, so
 *     {@code methodStats}, {@code totalRequests}, … appear as top-level attributes on the
 *     {@code io.activej.jsonrpc.service:type=JsonRpcDispatcher} MBean.</li>
 *     <li><b>U6a (map under flattening)</b> — {@code methodStats} survives as {@code TabularData} with one
 *     row per registered wire name; the row type is built from {@code JsonRpcMethodStats}' visible
 *     {@code @JmxAttribute} getters.</li>
 * </ul>
 * The test also carries T022's disclosure audit: no attribute or rendered value may carry a payload, a
 * parameter value, an {@code id}, an unregistered wire name, or exception-derived text (Spec
 * §Security Considerations).
 */
public class JsonRpcJmxAttributesTest {
	@ClassRule
	public static final EventloopRule eventloopRule = new EventloopRule();
	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();
	@ClassRule
	public static final ActivePromisesRule activePromisesRule = new ActivePromisesRule();

	private MBeanServer mbs;
	private JmxRegistry jmxRegistry;
	private JsonRpcDispatcher.JmxInspector inspector;
	private JsonRpcDispatcher dispatcher;

	@Before
	public void setUp() {
		mbs = MBeanServerFactory.newMBeanServer();
		jmxRegistry = JmxRegistry.create(mbs, DynamicMBeanFactory.create());
		inspector = new JsonRpcDispatcher.JmxInspector();
		dispatcher = JsonRpcDispatcher.builder(Reactor.getCurrentReactor())
			.withService(UserApi.class, new UserApiImpl())
			.withService(FailingApi.class, new FailingApiImpl())
			.withInspector(inspector)
			.build();
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
	// T019 — the attribute tree: rows, sub-attributes, flattening (resolves U1, U2, U6a).
	// ---------------------------------------------------------------------------------------------------

	@Test
	public void methodStatsReadsAsTabularDataWithOneRowPerRegisteredMethod() throws Exception {
		ObjectName name = dispatcherName();

		Object raw = mbs.getAttribute(name, "methodStats");
		assertTrue("methodStats must survive as TabularData, was: " + raw.getClass(), raw instanceof TabularData);
		TabularData methodStats = (TabularData) raw;

		// row set = the closed key set, fixed at build()
		assertEquals(dispatcher.wireNames().size(), methodStats.size());
		Set<String> rowKeys = new HashSet<>();
		for (Object rowKey : methodStats.keySet()) {
			rowKeys.add(((List<?>) rowKey).get(0).toString());
		}
		assertEquals(dispatcher.wireNames(), rowKeys);

		// the row type's columns are the §2 sub-attribute names, plus the platform's standard
		// extraSubAttributes flattening: <name> (rendered string) and <name>_totalCount (Long)
		Set<String> columns = methodStats.getTabularType().getRowType().keySet();
		assertTrue(columns.containsAll(Set.of(
			"> key", "successfulRequests", "successfulRequests_totalCount", "failedRequests",
			"failedRequests_totalCount", "requestHandlingTime", "errorsByCode", "otherErrors",
			"otherErrors_totalCount")));

		// a row's errorsByCode column is itself TabularData keyed by the nine named codes
		CompositeData userGet = (CompositeData) methodStats.get(new Object[]{"user.get"});
		assertNotNull(userGet);
		Object errorsRaw = userGet.get("errorsByCode");
		assertTrue("errorsByCode must be TabularData, was: " + errorsRaw.getClass(), errorsRaw instanceof TabularData);
		TabularData errorsByCode = (TabularData) errorsRaw;
		assertEquals(9, errorsByCode.size());
		Set<String> errorKeys = new HashSet<>();
		for (Object rowKey : errorsByCode.keySet()) {
			errorKeys.add(((List<?>) rowKey).get(0).toString());
		}
		for (JsonRpcError error : JsonRpcErrors.named()) {
			assertTrue("named code " + error.code() + " missing from errorsByCode: " + errorKeys,
				errorKeys.contains(Integer.toString(error.code())));
		}
	}

	@Test
	public void flatteningEmitsTopLevelAttributesWithoutPrefix() throws Exception {
		ObjectName name = dispatcherName();

		// U2: the nested inspector reached through @JmxAttribute(name = "") flattens to top level
		Object totalRequests = mbs.getAttribute(name, "totalRequests");
		assertTrue("totalRequests must be the rendered string, was: " + totalRequests.getClass(),
			totalRequests instanceof String);

		dispatch("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"user.get\",\"params\":[42]}");
		assertEquals(1L, mbs.getAttribute(name, "totalRequests_totalCount"));

		assertEquals(dispatcher.wireNames().size(), mbs.getAttribute(name, "registeredMethods"));
	}

	@Test
	public void countersMoveAfterDispatch() throws Exception {
		ObjectName name = dispatcherName();

		dispatch("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"user.get\",\"params\":[42]}");
		dispatch("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"no.such\"}");

		TabularData methodStats = (TabularData) mbs.getAttribute(name, "methodStats");
		CompositeData userGet = (CompositeData) methodStats.get(new Object[]{"user.get"});
		assertNotNull(userGet);
		assertTrue((long) userGet.get("successfulRequests_totalCount") >= 1);

		assertEquals(1L, mbs.getAttribute(name, "methodNotFound_totalCount"));
	}

	// ---------------------------------------------------------------------------------------------------
	// T021 — the limits are observable, read-only, and report the effective values.
	// ---------------------------------------------------------------------------------------------------

	@Test
	public void limitsAreReadOnlyAndReportEffectiveValues() throws Exception {
		ObjectName name = dispatcherName();

		assertEquals(JsonRpcLimits.MAX_BATCH_SIZE, mbs.getAttribute(name, "maxBatchSize"));
		assertEquals(JsonRpcLimits.MAX_JSON_DEPTH, mbs.getAttribute(name, "maxJsonDepth"));

		MBeanInfo info = mbs.getMBeanInfo(name);
		boolean maxBatchSizeSeen = false;
		boolean maxJsonDepthSeen = false;
		for (MBeanAttributeInfo attribute : info.getAttributes()) {
			if (attribute.getName().equals("maxBatchSize")) {
				maxBatchSizeSeen = true;
				assertFalse("maxBatchSize must be read-only", attribute.isWritable());
			}
			if (attribute.getName().equals("maxJsonDepth")) {
				maxJsonDepthSeen = true;
				assertFalse("maxJsonDepth must be read-only", attribute.isWritable());
			}
		}
		assertTrue(maxBatchSizeSeen);
		assertTrue(maxJsonDepthSeen);
	}

	// ---------------------------------------------------------------------------------------------------
	// T022 — disclosure: counts, latencies and error codes only (Spec §Security Considerations).
	// ---------------------------------------------------------------------------------------------------

	@Test
	public void noAttributeCarriesPayloadIdsWireNamesOrExceptionText() throws Exception {
		ObjectName name = dispatcherName();

		String payloadMarker = "hunter2";
		String idMarker = "secret-id-9";
		String methodMarker = "no.such.hunter2";

		dispatch("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"user.get\",\"params\":[42]}");
		dispatch("{\"jsonrpc\":\"2.0\",\"id\":\"" + idMarker + "\",\"method\":\"no.such." + payloadMarker + "\"}");
		dispatch("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"fail.thrown\"}");
		dispatch("{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"fail.failedWithJsonRpc\"}");

		List<String> renderings = new ArrayList<>();
		for (String attributeName : attributeNames(name)) {
			Object value = mbs.getAttribute(name, attributeName);
			collectRenderings(value, renderings);
		}
		String all = String.join("\n", renderings);

		// payload value, id, wire-supplied-but-unmatched method name, exception text and class name — none may appear
		assertFalse("payload marker leaked: " + all, all.contains(payloadMarker));
		assertFalse("id leaked: " + all, all.contains(idMarker));
		assertFalse("unmatched wire method name leaked: " + all, all.contains(methodMarker));
		assertFalse("exception message leaked: " + all, all.contains(FailingApiImpl.SECRET));
		assertFalse("exception class name leaked: " + all, all.contains("IllegalStateException"));
		assertFalse("application error message leaked: " + all, all.contains("Too many requests"));

		// the only method-name strings present are the registered wire names — the "matched nothing" boundary
		TabularData methodStats = (TabularData) mbs.getAttribute(name, "methodStats");
		Set<String> rowKeys = new HashSet<>();
		for (Object rowKey : methodStats.keySet()) {
			rowKeys.add(((List<?>) rowKey).get(0).toString());
		}
		assertEquals(dispatcher.wireNames(), rowKeys);

		// code 429 landed in otherErrors and never appears as a key (outside the closed named set)
		TabularData errorsByCode = (TabularData) ((CompositeData) methodStats.get(new Object[]{"fail.failedWithJsonRpc"}))
			.get("errorsByCode");
		assertFalse("application code must not appear as an errorsByCode key: " + errorsByCode.keySet(),
			errorsByCode.keySet().stream().anyMatch(k -> ((List<?>) k).get(0).toString().equals("429")));
	}

	@Test
	public void attributeNamesAreExactlyTheContractSurface() throws Exception {
		// the §2 base attributes, plus the platform's standard extraSubAttributes flattening (_totalCount).
		// Feature 019 appends three names and renames or reshapes none — this set is the whole proof of that
		Set<String> expected = Set.of(
			"methodStats", "totalRequests", "totalErrors", "methodNotFound", "malformedDocuments",
			"registeredMethods", "maxBatchSize", "maxJsonDepth",
			"totalRequests_totalCount", "totalErrors_totalCount", "methodNotFound_totalCount",
			"malformedDocuments_totalCount",
			"maxInFlight", "inFlight", "rejectedRequests", "rejectedRequests_totalCount");
		assertEquals(expected, attributeNames(dispatcherName()));
	}

	// ---------------------------------------------------------------------------------------------------
	// T033 — the three additive in-flight attributes (FR-038, contracts/jmx-attributes.md).
	// ---------------------------------------------------------------------------------------------------

	@Test
	public void theInFlightAttributesAreReadOnlyAndReportTheConfiguredAndLiveValues() throws Exception {
		ObjectName name = dispatcherName();

		assertEquals(JsonRpcDispatcher.MAX_IN_FLIGHT, mbs.getAttribute(name, "maxInFlight"));
		assertEquals("nothing is in flight between dispatches", 0, mbs.getAttribute(name, "inFlight"));
		assertEquals(0L, mbs.getAttribute(name, "rejectedRequests_totalCount"));

		Set<String> readOnly = new HashSet<>(Set.of("maxInFlight", "inFlight", "rejectedRequests"));
		for (MBeanAttributeInfo attribute : mbs.getMBeanInfo(name).getAttributes()) {
			if (readOnly.remove(attribute.getName())) {
				assertFalse(attribute.getName() + " must be read-only", attribute.isWritable());
			}
		}
		assertTrue("missing attributes: " + readOnly, readOnly.isEmpty());
	}

	@Test
	public void maxInFlightReportsTheBuilderValueNotTheProcessDefault() throws Exception {
		Registered registered = register(dispatcherBuilder()
			.withMaxInFlight(7)
			.withInspector(new JsonRpcDispatcher.JmxInspector()));

		assertEquals(7, registered.server.getAttribute(registered.name, "maxInFlight"));
	}

	@Test
	public void inFlightReadsTheLiveCountAndDrainsBackToZero() throws Exception {
		SlowApiImpl slow = new SlowApiImpl();
		Registered registered = register(JsonRpcDispatcher.builder(Reactor.getCurrentReactor())
			.withService(SlowApi.class, slow)
			.withInspector(new JsonRpcDispatcher.JmxInspector()));

		List<Promise<byte[]>> held = new ArrayList<>();
		for (int i = 1; i <= 3; i++) {
			held.add(registered.dispatcher.dispatch(
				("{\"jsonrpc\":\"2.0\",\"id\":" + i + ",\"method\":\"slow.call\",\"params\":[\"x\"]}")
					.getBytes(UTF_8)));
			assertEquals("the live count, read while the invocations are held", i,
				registered.server.getAttribute(registered.name, "inFlight"));
		}

		slow.releaseAll();
		for (Promise<byte[]> promise : held) {
			await(promise);
		}
		assertEquals("every completion gave its slot back", 0,
			registered.server.getAttribute(registered.name, "inFlight"));
	}

	@Test
	public void rejectedRequestsFoldsRequestsAndNotificationsIntoOneAggregate() throws Exception {
		SlowApiImpl slow = new SlowApiImpl();
		Registered registered = register(JsonRpcDispatcher.builder(Reactor.getCurrentReactor())
			.withService(SlowApi.class, slow)
			.withMaxInFlight(1)
			.withFailureHandler((descriptor, e) -> {})
			.withInspector(new JsonRpcDispatcher.JmxInspector()));
		MBeanServer server = registered.server;
		ObjectName name = registered.name;

		Promise<byte[]> held = registered.dispatcher.dispatch(
			"{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"slow.call\",\"params\":[\"a\"]}".getBytes(UTF_8));
		assertEquals(1, server.getAttribute(name, "inFlight"));

		await(registered.dispatcher.dispatch(
			"{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"slow.call\",\"params\":[\"b\"]}".getBytes(UTF_8)));
		await(registered.dispatcher.dispatch(
			"{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"slow.call\",\"params\":[\"c\"]}".getBytes(UTF_8)));
		await(registered.dispatcher.dispatch(
			"{\"jsonrpc\":\"2.0\",\"method\":\"slow.notify\",\"params\":[\"d\"]}".getBytes(UTF_8)));

		// two shed requests and one shed notification, in one aggregate — the rejection carries no wire
		// name by construction, so there is nothing else it could be keyed by
		assertEquals(3L, server.getAttribute(name, "rejectedRequests_totalCount"));

		// and it is counted THERE and nowhere else: -32005 is deliberately not in JsonRpcErrors.named(),
		// so no per-method row exists for it and none was invented
		TabularData methodStats = (TabularData) server.getAttribute(name, "methodStats");
		CompositeData row = (CompositeData) methodStats.get(new Object[]{"slow.call"});
		assertNotNull(row);
		assertEquals(0L, row.get("failedRequests_totalCount"));
		assertEquals(0L, row.get("otherErrors_totalCount"));
		TabularData errorsByCode = (TabularData) row.get("errorsByCode");
		assertEquals("the closed key set stays at the nine named codes", 9, errorsByCode.size());
		assertFalse("-32005 must not appear as an errorsByCode key: " + errorsByCode.keySet(),
			rowKeys(errorsByCode).contains("-32005"));

		// a shed element is not a method-not-found and not an error the server chose per method
		assertEquals(0L, server.getAttribute(name, "methodNotFound_totalCount"));
		assertEquals(0L, server.getAttribute(name, "totalErrors_totalCount"));
		assertEquals("only the admitted invocation was announced", 1L,
			server.getAttribute(name, "totalRequests_totalCount"));

		slow.releaseAll();
		await(held);
		assertEquals("the counter is cumulative, not a gauge", 3L,
			server.getAttribute(name, "rejectedRequests_totalCount"));
		assertEquals(0, server.getAttribute(name, "inFlight"));
	}

	// ---------------------------------------------------------------------------------------------------
	// Feature 018 — rpc.discover is an ordinary member of the closed row set, or absent (FR-033).
	// ---------------------------------------------------------------------------------------------------

	@Test
	public void discoveryDisabledKeepsRpcDiscoverOutOfTheSetHandedToInitialize() {
		RecordingInspector recording = new RecordingInspector();
		JsonRpcDispatcher built = dispatcherBuilder().withInspector(recording).build();

		assertNotNull("initialize(...) must fire exactly once, at build()", recording.wireNames);
		assertEquals(built.wireNames(), recording.wireNames);
		assertFalse("rpc.discover is not registered unless withDiscovery(...) was called: " + recording.wireNames,
			recording.wireNames.contains(RPC_DISCOVER));
	}

	@Test
	public void discoveryEnabledPutsRpcDiscoverInTheSetHandedToInitialize() {
		RecordingInspector recording = new RecordingInspector();
		JsonRpcDispatcher built = dispatcherBuilder()
			.withDiscovery(DISCOVERY_INFO)
			.withInspector(recording)
			.build();

		assertNotNull(recording.wireNames);
		// the set handed to initialize() IS the frozen table's key set — discovery included, nothing else
		assertEquals(built.wireNames(), recording.wireNames);
		assertTrue("rpc.discover must join the closed set at build(): " + recording.wireNames,
			recording.wireNames.contains(RPC_DISCOVER));
	}

	@Test
	public void discoveryDisabledHasNoRpcDiscoverRow() throws Exception {
		TabularData methodStats = (TabularData) mbs.getAttribute(dispatcherName(), "methodStats");
		assertFalse(rowKeys(methodStats).contains(RPC_DISCOVER));
		assertEquals(dispatcher.wireNames(), rowKeys(methodStats));
	}

	@Test
	public void discoveryEnabledGivesRpcDiscoverItsOwnMethodStatsRow() throws Exception {
		MBeanServer server = MBeanServerFactory.newMBeanServer();
		JsonRpcDispatcher discovering = dispatcherBuilder()
			.withDiscovery(DISCOVERY_INFO)
			.withInspector(new JsonRpcDispatcher.JmxInspector())
			.build();
		JmxRegistry.create(server, DynamicMBeanFactory.create())
			.registerSingleton(Key.of(JsonRpcDispatcher.class), discovering, JmxBeanSettings.create());
		ObjectName name = server
			.queryNames(new ObjectName("io.activej.jsonrpc.service:type=JsonRpcDispatcher"), null)
			.iterator().next();

		assertEquals(discovering.wireNames(), rowKeys((TabularData) server.getAttribute(name, "methodStats")));
		assertNotNull("rpc.discover must have its own row",
			((TabularData) server.getAttribute(name, "methodStats")).get(new Object[]{RPC_DISCOVER}));

		// FR-033: a served rpc.discover is counted on its own row and never through the aggregate-only
		// onMethodNotFound path — "no wire-text keying" is the same rule read from the other side
		await(discovering.dispatch(
			("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"" + RPC_DISCOVER + "\"}").getBytes(UTF_8)));

		CompositeData row = (CompositeData) ((TabularData) server.getAttribute(name, "methodStats"))
			.get(new Object[]{RPC_DISCOVER});
		assertNotNull(row);
		assertEquals(1L, row.get("successfulRequests_totalCount"));
		assertEquals(0L, server.getAttribute(name, "methodNotFound_totalCount"));
		assertEquals(1L, server.getAttribute(name, "totalRequests_totalCount"));

		// and the set stays closed: a neighbouring reserved name moves the aggregate and creates no row
		await(discovering.dispatch(
			"{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"rpc.discoverX\"}".getBytes(UTF_8)));

		assertEquals(discovering.wireNames(), rowKeys((TabularData) server.getAttribute(name, "methodStats")));
		assertEquals(1L, server.getAttribute(name, "methodNotFound_totalCount"));
	}

	// ---------------------------------------------------------------------------------------------------
	// T032 — characterization (FR-037). Nothing indexed by wire input, proven from three sides at once:
	// the rendered JMX surface, the inspector's own field graph, and the callbacks a wire miss can reach.
	//
	// This test is green the day it is written. That is its point: it pins a property the code already has
	// so that the change which breaks it — a computeIfAbsent, a per-name row, a "helpful" diagnostic map —
	// fails loudly here instead of shipping as a memory-exhaustion primitive.
	// ---------------------------------------------------------------------------------------------------

	@Test
	public void manyUnknownWireNamesMoveOnlyTheAggregateCounterAndCreateNoStructure() throws Exception {
		ObjectName name = dispatcherName();
		Set<String> frozenRows = rowKeys((TabularData) mbs.getAttribute(name, "methodStats"));
		Set<String> frozenAttributes = attributeNames(name);
		assertEquals(dispatcher.wireNames(), frozenRows);

		int distinctNames = 64;
		for (int i = 0; i < distinctNames; i++) {
			// a request miss and a notification miss: both reach the same aggregate-only callback
			dispatch("{\"jsonrpc\":\"2.0\",\"id\":" + i + ",\"method\":\"ghost.request." + i + "\"}");
			dispatch("{\"jsonrpc\":\"2.0\",\"method\":\"ghost.notification." + i + "\"}");
		}

		// the one thing that moved
		assertEquals(2L * distinctNames, mbs.getAttribute(name, "methodNotFound_totalCount"));

		// the row set, the attribute set and every map inside the inspector are exactly what they were
		assertEquals("no per-name row appeared", frozenRows,
			rowKeys((TabularData) mbs.getAttribute(name, "methodStats")));
		assertEquals("no per-name attribute appeared", frozenAttributes, attributeNames(name));
		assertNoWireKeyedMap(inspector, frozenRows);

		// and no rendered value anywhere carries one of the names
		List<String> renderings = new ArrayList<>();
		for (String attributeName : frozenAttributes) {
			collectRenderings(mbs.getAttribute(name, attributeName), renderings);
		}
		String all = String.join("\n", renderings);
		assertFalse("a wire-supplied name leaked into the JMX surface: " + all, all.contains("ghost."));
	}

	@Test
	public void anUnknownWireNameReachesOnlyTheAggregateOnlyCallback() {
		RecordingInspector recording = new RecordingInspector();
		JsonRpcDispatcher built = dispatcherBuilder().withInspector(recording).build();

		for (int i = 0; i < 32; i++) {
			await(built.dispatch(("{\"jsonrpc\":\"2.0\",\"id\":" + i + ",\"method\":\"ghost." + i + "\"}")
				.getBytes(UTF_8)));
		}

		// FR-034's type-system argument, read from the call side: a descriptor-typed callback can only be
		// reached through the closed handler table, so a miss reaches exactly one callback and no other
		assertEquals(32, recording.methodNotFound);
		assertTrue("no descriptor-typed callback may fire for a name that matched nothing: " +
				   recording.descriptors, recording.descriptors.isEmpty());
		assertEquals(0, recording.malformed);
		assertEquals(0, recording.rejected);
	}

	/**
	 * Every {@link Map} reachable through the inspector's own fields, and through each statistics row's
	 * fields, is keyed by a <b>closed</b> set — the registered wire names, or the nine named error codes.
	 * Reflection rather than getters, so a map added later without a {@code @JmxAttribute} is caught too.
	 */
	private static void assertNoWireKeyedMap(JsonRpcDispatcher.JmxInspector inspector, Set<String> wireNames)
		throws Exception {
		Set<String> namedCodes = new HashSet<>();
		for (JsonRpcError error : JsonRpcErrors.named()) {
			namedCodes.add(Integer.toString(error.code()));
		}

		for (Field field : JsonRpcDispatcher.JmxInspector.class.getDeclaredFields()) {
			if (Modifier.isStatic(field.getModifiers())) continue;
			field.setAccessible(true);
			Object value = field.get(inspector);
			if (!(value instanceof Map<?, ?> map)) continue;

			assertEquals("JmxInspector." + field.getName() + " grew a key from the wire",
				wireNames, keysAsStrings(map));
			for (Object row : map.values()) {
				for (Field rowField : row.getClass().getDeclaredFields()) {
					if (Modifier.isStatic(rowField.getModifiers())) continue;
					rowField.setAccessible(true);
					Object rowValue = rowField.get(row);
					if (!(rowValue instanceof Map<?, ?> rowMap)) continue;
					assertEquals(row.getClass().getSimpleName() + '.' + rowField.getName() +
								 " grew a key from the wire", namedCodes, keysAsStrings(rowMap));
				}
			}
		}
	}

	private static Set<String> keysAsStrings(Map<?, ?> map) {
		Set<String> keys = new HashSet<>();
		for (Object key : map.keySet()) {
			keys.add(String.valueOf(key));
		}
		return keys;
	}

	// ---------------------------------------------------------------------------------------------------
	// Helpers.
	// ---------------------------------------------------------------------------------------------------

	/** The one name feature 018 adds to a dispatcher. */
	private static final String RPC_DISCOVER = "rpc.discover";

	/** Application-supplied identity; this test asserts the row set, never the document's content. */
	private static final OpenRpcInfo DISCOVERY_INFO = new OpenRpcInfo("JsonRpcJmxAttributesTest", "1.0.0");

	/** The same two services {@link #setUp()} registers, so a discovery row is the only possible difference. */
	private static JsonRpcDispatcher.Builder dispatcherBuilder() {
		return JsonRpcDispatcher.builder(Reactor.getCurrentReactor())
			.withService(UserApi.class, new UserApiImpl())
			.withService(FailingApi.class, new FailingApiImpl());
	}

	/** A dispatcher on its own {@link MBeanServer}, so a test can register a second one of the same key. */
	private record Registered(JsonRpcDispatcher dispatcher, MBeanServer server, ObjectName name) {}

	private static Registered register(JsonRpcDispatcher.Builder builder) throws Exception {
		JsonRpcDispatcher built = builder.build();
		MBeanServer server = MBeanServerFactory.newMBeanServer();
		JmxRegistry.create(server, DynamicMBeanFactory.create())
			.registerSingleton(Key.of(JsonRpcDispatcher.class), built, JmxBeanSettings.create());
		ObjectName name = server
			.queryNames(new ObjectName("io.activej.jsonrpc.service:type=JsonRpcDispatcher"), null)
			.iterator().next();
		return new Registered(built, server, name);
	}

	private static Set<String> rowKeys(TabularData tabularData) {
		Set<String> keys = new HashSet<>();
		for (Object rowKey : tabularData.keySet()) {
			keys.add(((List<?>) rowKey).get(0).toString());
		}
		return keys;
	}

	/**
	 * Captures the one argument {@code doBuild()} hands {@link JsonRpcDispatcher.Inspector#initialize(Set)},
	 * and — for T032 — which callbacks a dispatch actually reached. The descriptor-typed callbacks are
	 * collected by wire name; a name that matched nothing can never appear there, which is the property
	 * FR-034 pushes into the type system.
	 */
	private static final class RecordingInspector
		extends AbstractInspector<JsonRpcDispatcher.Inspector>
		implements JsonRpcDispatcher.Inspector {
		private @Nullable Set<String> wireNames;
		private final List<String> descriptors = new ArrayList<>();
		private int methodNotFound;
		private int malformed;
		private int rejected;

		@Override
		public void initialize(Set<String> wireNames) {
			this.wireNames = Set.copyOf(wireNames);
		}

		@Override
		public void onRequest(JsonRpcMethodDescriptor descriptor) {
			descriptors.add(descriptor.wireName());
		}

		@Override
		public void onResponse(JsonRpcMethodDescriptor descriptor, long durationMillis) {
			descriptors.add(descriptor.wireName());
		}

		@Override
		public void onError(JsonRpcMethodDescriptor descriptor, int errorCode, long durationMillis) {
			descriptors.add(descriptor.wireName());
		}

		@Override
		public void onMethodNotFound(String requestedName) {
			methodNotFound++;
		}

		@Override
		public void onMalformed() {
			malformed++;
		}

		@Override
		public void onRejected() {
			rejected++;
		}
	}

	private Set<String> attributeNames(ObjectName name) throws Exception {
		Set<String> names = new HashSet<>();
		for (MBeanAttributeInfo attribute : mbs.getMBeanInfo(name).getAttributes()) {
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
