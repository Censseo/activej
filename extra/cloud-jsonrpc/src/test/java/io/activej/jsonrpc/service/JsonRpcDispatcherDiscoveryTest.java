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

import io.activej.json.JsonCodecFactory;
import io.activej.jsonrpc.schema.JsonRpcSchemaGenerator;
import io.activej.jsonrpc.schema.OpenRpcInfo;
import io.activej.jsonrpc.schema.fixtures.ReferenceApi;
import io.activej.jsonrpc.schema.fixtures.ReferenceApiImpl;
import io.activej.reactor.Reactor;
import io.activej.test.rules.ActivePromisesRule;
import io.activej.test.rules.ByteBufRule;
import io.activej.test.rules.EventloopRule;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;

import java.util.List;

import static io.activej.promise.TestUtils.await;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * User story 1 — {@code rpc.discover} as a built-in entry of the dispatcher's frozen handler table
 * (FR-002…FR-006, FR-031, FR-034; research Decision 1).
 * <p>
 * Everything here runs against two dispatchers over the <b>same</b> services, differing only in whether
 * {@code withDiscovery(...)} was called. That is the whole point of the design under test: discovery is not a
 * branch taken before the table lookup, it is one more entry <i>in</i> the table — so every property below
 * (batch, notification, {@code -32602}, {@code wireNames()}, totality) must fall out of machinery that already
 * existed, with no parallel path.
 *
 * <h2>The two load-bearing assertions</h2>
 * <ul>
 *     <li><b>Disabled is indistinguishable</b> (FR-004) — asserted as a <b>byte</b> comparison against the
 *     answer to an arbitrary unregistered name, not as "both are {@code -32601}". A peer must not be able to
 *     tell a dispatcher that could serve the document from one that has no such feature at all.</li>
 *     <li><b>Enabled serves the generator's bytes</b> (FR-040) — asserted against
 *     {@link JsonRpcSchemaGenerator#generateBytes} called independently here, byte for byte. One generator,
 *     three consumers: if the dispatcher re-derived or re-encoded the document, this is where the two would
 *     drift apart.</li>
 * </ul>
 */
public class JsonRpcDispatcherDiscoveryTest {
	@ClassRule
	public static final EventloopRule eventloopRule = new EventloopRule();
	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();
	@ClassRule
	public static final ActivePromisesRule activePromisesRule = new ActivePromisesRule();

	/** The one name this feature adds to a dispatcher. */
	private static final String RPC_DISCOVER = "rpc.discover";

	/** Application-supplied, never derived — the same value the independent generator call below is given. */
	private static final OpenRpcInfo INFO = new OpenRpcInfo("Reference API", "1.2.3");

	/** The canonical {@code -32601} answer, copied from the pre-existing unknown-method tests. */
	private static final String METHOD_NOT_FOUND =
		"{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":{\"code\":-32601,\"message\":\"Method not found\"}}";

	private static final String INVALID_PARAMS =
		"{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":{\"code\":-32602,\"message\":\"Invalid params\"}}";

	private JsonRpcDispatcher plain;
	private JsonRpcDispatcher discovering;

	/** The document as the offline export produces it — generated here, independently of any dispatcher. */
	private String document;

	@Before
	public void setUp() {
		plain = JsonRpcDispatcher.builder(Reactor.getCurrentReactor())
			.withService(ReferenceApi.class, new ReferenceApiImpl())
			.build();
		discovering = JsonRpcDispatcher.builder(Reactor.getCurrentReactor())
			.withService(ReferenceApi.class, new ReferenceApiImpl())
			.withDiscovery(INFO)
			.build();
		document = new String(JsonRpcSchemaGenerator.generateBytes(INFO, List.of(
			JsonRpcServiceContract.of(ReferenceApi.class, JsonCodecFactory.defaultInstance()))), UTF_8);
	}

	// ---------------------------------------------------------------------------------------------------
	// FR-003 / FR-004 — off by default, and indistinguishable when off.
	// ---------------------------------------------------------------------------------------------------

	@Test
	public void discoveryIsOffUnlessWithDiscoveryWasCalled() {
		assertFalse("rpc.discover must not be registered on a dispatcher built without withDiscovery(...)",
			plain.wireNames().contains(RPC_DISCOVER));
	}

	@Test
	public void disabledAnswersRpcDiscoverExactlyAsAnyUnregisteredName() {
		byte[] discover = dispatchBytes(plain, request(RPC_DISCOVER, null));
		byte[] unknown = dispatchBytes(plain, request("no.such.method", null));

		// FR-004: byte-indistinguishable, not merely "both -32601"
		assertArrayEquals("a disabled dispatcher must not be distinguishable from one that never had the " +
						  "feature: " + new String(discover, UTF_8) + " vs " + new String(unknown, UTF_8),
			unknown, discover);
		assertEquals(METHOD_NOT_FOUND, new String(discover, UTF_8));
	}

	@Test
	public void disabledAnswersNothingToARpcDiscoverNotification() {
		// §4.1 forbids answering a notification, unknown name or not — the disabled case must not leak a
		// -32601 that the registered case would not have produced either
		assertEquals(0, dispatchBytes(plain, notification(RPC_DISCOVER, null)).length);
	}

	// ---------------------------------------------------------------------------------------------------
	// FR-002 / FR-040 — enabled serves the generator's bytes, verbatim.
	// ---------------------------------------------------------------------------------------------------

	@Test
	public void enabledRegistersRpcDiscoverInTheFrozenWireNameSet() {
		assertTrue(discovering.wireNames().contains(RPC_DISCOVER));
		// every user method is still there, and nothing else was added
		assertTrue(discovering.wireNames().containsAll(plain.wireNames()));
		assertEquals(plain.wireNames().size() + 1, discovering.wireNames().size());
	}

	@Test
	public void enabledAnswersWithTheGeneratorsDocumentByteForByte() {
		assertEquals("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":" + document + '}',
			dispatch(discovering, request(RPC_DISCOVER, null)));
	}

	@Test
	public void theDocumentDoesNotDescribeRpcDiscoverItself() {
		// rule M2: the served name is not in methods[] — it belongs to no contract
		assertFalse("the document must not describe the discovery method itself: " + document,
			document.contains('"' + RPC_DISCOVER + '"'));
		// ... while every user method is
		for (String wireName : plain.wireNames()) {
			assertTrue("the document must describe " + wireName, document.contains("\"name\":\"" + wireName + '"'));
		}
	}

	@Test
	public void theDocumentIsComputedOnceAndServedRepeatedly() {
		// FR-015 / FR-032: nothing is regenerated per call, so two answers are identical
		String first = dispatch(discovering, request(RPC_DISCOVER, null));
		String second = dispatch(discovering, request(RPC_DISCOVER, null));
		assertEquals(first, second);
	}

	// ---------------------------------------------------------------------------------------------------
	// FR-005 — no params; absent or empty accepted, anything else -32602.
	// ---------------------------------------------------------------------------------------------------

	@Test
	public void absentParamsServeTheDocument() {
		assertResultIsTheDocument(dispatch(discovering, request(RPC_DISCOVER, null)));
	}

	@Test
	public void emptyPositionalParamsServeTheDocument() {
		assertResultIsTheDocument(dispatch(discovering, request(RPC_DISCOVER, "[]")));
	}

	@Test
	public void emptyNamedParamsServeTheDocument() {
		assertResultIsTheDocument(dispatch(discovering, request(RPC_DISCOVER, "{}")));
	}

	@Test
	public void nullParamsServeTheDocument() {
		// §4.2 permits the literal, and the dispatcher reads it exactly as an omitted member
		assertResultIsTheDocument(dispatch(discovering, request(RPC_DISCOVER, "null")));
	}

	@Test
	public void nonEmptyPositionalParamsAreInvalidParams() {
		assertEquals(INVALID_PARAMS, dispatch(discovering, request(RPC_DISCOVER, "[1]")));
	}

	@Test
	public void nonEmptyNamedParamsAreInvalidParams() {
		assertEquals(INVALID_PARAMS, dispatch(discovering, request(RPC_DISCOVER, "{\"anything\":1}")));
	}

	@Test
	public void aScalarParamsMemberIsRefusedByTheEnvelopeBeforeDiscoveryIsReached() {
		// §4.2 admits only an array, an object or null there, and the decoder enforces it — so a bare literal
		// is -32600 whether or not the name is registered. Pinned here so the two refusals are not confused
		assertEquals("{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":{\"code\":-32600,\"message\":\"Invalid Request\"}}",
			dispatch(discovering, request(RPC_DISCOVER, "42")));
	}

	// ---------------------------------------------------------------------------------------------------
	// FR-006 — ordinary batch and notification semantics, inherited rather than re-implemented.
	// ---------------------------------------------------------------------------------------------------

	@Test
	public void asANotificationItProducesNoResponseElementAtAll() {
		assertEquals(0, dispatchBytes(discovering, notification(RPC_DISCOVER, null)).length);
	}

	@Test
	public void asABatchElementItProducesItsOwnResponseElement() {
		String batch = '[' + request(RPC_DISCOVER, null) + ',' +
					   "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"reference.archive\",\"params\":[7]}]";

		assertEquals("[{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":" + document + "}," +
					 "{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":null}]",
			dispatch(discovering, batch));
	}

	@Test
	public void aBatchOfOnlyANotificationOfItProducesNothing() {
		assertEquals(0, dispatchBytes(discovering, '[' + notification(RPC_DISCOVER, null) + ']').length);
	}

	// ---------------------------------------------------------------------------------------------------
	// FR-031 / FR-034 — totality and non-disclosure are unchanged.
	// ---------------------------------------------------------------------------------------------------

	@Test
	public void dispatchStaysTotalAroundDiscovery() {
		// every one of these is answered, none fails the promise — await() would throw if one did
		for (String input : List.of(
			request(RPC_DISCOVER, null),
			request(RPC_DISCOVER, "[1,2,3]"),
			notification(RPC_DISCOVER, null),
			"{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"rpc.discover\",\"params\":", // truncated
			"[" + request(RPC_DISCOVER, null) + ",not-json]",
			"{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"rpc.\"}",
			"{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"rpc.discoverX\"}")) {
			assertNotNull(input, dispatchBytes(discovering, input));
		}
	}

	@Test
	public void aNeighbouringReservedNameIsStillUnknown() {
		// only rpc.discover is served; nothing else in the reserved namespace is
		assertEquals(METHOD_NOT_FOUND, dispatch(discovering, request("rpc.discoverX", null)));
		assertEquals(METHOD_NOT_FOUND, dispatch(discovering, request("rpc.", null)));
	}

	@Test
	public void anInvalidParamsRefusalCarriesNoData() {
		// FR-034: nothing derived from the local decode failure reaches the wire
		String response = dispatch(discovering, request(RPC_DISCOVER, "[\"hunter2\"]"));
		assertEquals(INVALID_PARAMS, response);
		assertFalse(response.contains("hunter2"));
		assertFalse(response.contains("data"));
	}

	// ---------------------------------------------------------------------------------------------------
	// Helpers.
	// ---------------------------------------------------------------------------------------------------

	private void assertResultIsTheDocument(String response) {
		assertEquals("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":" + document + '}', response);
	}

	private static String request(String method, String params) {
		return "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"" + method + '"' +
			   (params == null ? "" : ",\"params\":" + params) + '}';
	}

	private static String notification(String method, String params) {
		return "{\"jsonrpc\":\"2.0\",\"method\":\"" + method + '"' +
			   (params == null ? "" : ",\"params\":" + params) + '}';
	}

	private static byte[] dispatchBytes(JsonRpcDispatcher dispatcher, String document) {
		byte[] response = await(dispatcher.dispatch(document.getBytes(UTF_8)));
		assertNotNull(response);
		return response;
	}

	private static String dispatch(JsonRpcDispatcher dispatcher, String document) {
		return new String(dispatchBytes(dispatcher, document), UTF_8);
	}
}
