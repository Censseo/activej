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
import io.activej.jsonrpc.JsonRpcError;
import io.activej.jsonrpc.JsonRpcErrors;
import io.activej.jsonrpc.JsonRpcLimits;
import io.activej.jsonrpc.service.fixtures.FailingApi;
import io.activej.jsonrpc.service.fixtures.FailingApiImpl;
import io.activej.jsonrpc.service.fixtures.UserApi;
import io.activej.jsonrpc.service.fixtures.UserApiImpl;
import io.activej.reactor.Reactor;
import io.activej.test.rules.ActivePromisesRule;
import io.activej.test.rules.ByteBufRule;
import io.activej.test.rules.EventloopRule;
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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
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
		// the §2 base attributes, plus the platform's standard extraSubAttributes flattening (_totalCount)
		Set<String> expected = Set.of(
			"methodStats", "totalRequests", "totalErrors", "methodNotFound", "malformedDocuments",
			"registeredMethods", "maxBatchSize", "maxJsonDepth",
			"totalRequests_totalCount", "totalErrors_totalCount", "methodNotFound_totalCount",
			"malformedDocuments_totalCount");
		assertEquals(expected, attributeNames(dispatcherName()));
	}

	// ---------------------------------------------------------------------------------------------------
	// Helpers.
	// ---------------------------------------------------------------------------------------------------

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
