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

package io.activej.jsonrpc.schema;

import io.activej.common.exception.MalformedDataException;
import io.activej.json.JsonCodecFactory;
import io.activej.json.JsonUtils;
import io.activej.jsonrpc.schema.JsonSchemaFragment.Any;
import io.activej.jsonrpc.schema.JsonSchemaFragment.Typed;
import io.activej.jsonrpc.schema.fixtures.Order;
import io.activej.jsonrpc.schema.fixtures.ReferenceApi;
import io.activej.jsonrpc.schema.fixtures.ReferenceApiImpl;
import io.activej.jsonrpc.schema.fixtures.Status;
import io.activej.jsonrpc.service.JsonRpcContractException;
import io.activej.jsonrpc.service.JsonRpcDispatcher;
import io.activej.jsonrpc.service.JsonRpcMethod;
import io.activej.jsonrpc.service.JsonRpcNotification;
import io.activej.jsonrpc.service.JsonRpcParam;
import io.activej.jsonrpc.service.JsonRpcService;
import io.activej.jsonrpc.service.JsonRpcServiceContract;
import io.activej.promise.Promise;
import io.activej.reactor.Reactor;
import io.activej.test.rules.EventloopRule;
import org.junit.ClassRule;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;

import static io.activej.promise.TestUtils.await;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Pins {@link JsonRpcSchemaGenerator} — the one generator of research Decision 5, the call that the runtime
 * {@code rpc.discover} answer (T013), the offline export (T016) and the frozen references (T024) all make.
 *
 * <p>The subject here is the <b>mapping from validated contracts to a document</b>: which methods appear and
 * in what order (rule M2), which Content Descriptors a method gets and in what order (M4), when
 * {@code result} is absent (M3) and when {@code paramStructure} is present (M5). The <i>bytes</i> of the
 * document are {@link OpenRpcDocumentTest}'s subject and the <i>schema fragments</i> are
 * {@link JsonSchemaMappingTest}'s; assertions here go through {@link JsonSchemaMapping} rather than restating
 * a fragment literally, so a mapping-table change breaks one test class and not three.
 *
 * <h2>Almost nothing here constructs an implementation instance</h2>
 * The generator reads a {@link JsonRpcServiceContract}, a contract is a property of the interface alone, and
 * the schema is a pure function of the declared types (FR-023) — so the export can describe a service nobody
 * has implemented yet, and every section but the last proves that by never having one. Section <b>(g)</b> is
 * the exception and has to be: a {@code JsonRpcDispatcher} needs an instance to register a service against,
 * and agreement with what a <i>running</i> dispatcher serves is exactly what FR-040 claims.
 *
 * <h2>{@code EventloopRule} belongs to section (g), and no other rule is needed</h2>
 * The whole {@code io.activej.jsonrpc.schema} package is synchronous and reactor-free by decision — a fact
 * {@code ModuleBoundaryTest} establishes by scanning {@code src/main} as text, and one that no rule declared
 * in a test class can strengthen or weaken. The rule is here because FR-040's third consumer lives one
 * package over, in the reactor-bound service layer, and dispatching a call to it needs a reactor on this
 * thread. There is deliberately no {@code ByteBufRule} and no {@code ActivePromisesRule}: neither the
 * generator nor the dispatcher allocates a {@code ByteBuf}, and nothing here tracks a promise.
 */
public final class JsonRpcSchemaGeneratorTest {
	@ClassRule
	public static final EventloopRule eventloopRule = new EventloopRule();

	private static final OpenRpcInfo INFO = new OpenRpcInfo("Reference API", "1.0.0");

	/**
	 * Every wire name of {@link ReferenceApi}, in the order rule M2 pins — sorted by wire name, which is the
	 * order the frozen reference document (FR-051) will be compared in. Spelled out rather than derived from
	 * {@code wireNames()}, so that a generator emitting some other order cannot agree with itself.
	 */
	private static final List<String> REFERENCE_NAMES_SORTED = List.of(
		"reference.archive",
		"reference.audit",
		"reference.boxedScalars",
		"reference.catalogue",
		"reference.containers",
		"reference.createOrder",
		"reference.orderSeen",
		"reference.ping",
		"reference.scalars",
		"reference.sum");

	// -------------------------------------------------------------------------------------------------
	// Local fixtures. ReferenceApi is the feature's reference service; these four exist only for the
	// structural cases it deliberately does not have - a second service, an empty one, a colliding one,
	// and one reaching into the reserved namespace.
	// -------------------------------------------------------------------------------------------------

	/**
	 * A second service with <b>no prefix</b>, built to break two different shortcuts at once.
	 * <ul>
	 *     <li>Its two wire names <i>bracket</i> the whole {@code reference.*} block — {@code red} sorts
	 *     before it, {@code refill} after it — so a generator concatenating per-contract blocks instead of
	 *     sorting the union emits both contracts' methods and still fails the ordering assertion.</li>
	 *     <li>Each Java identifier is deliberately unrelated to its wire name, and
	 *     {@code JsonRpcServiceContract} orders its methods by <i>Java</i> identifier. So this contract's own
	 *     discovery order ({@code refill}, {@code red}) is not its wire-name order ({@code red},
	 *     {@code refill}) — which {@link ReferenceApi}'s cannot show, every one of its wire names being its
	 *     Java name under one prefix.</li>
	 * </ul>
	 */
	@JsonRpcService("")
	public interface BracketApi {
		@JsonRpcMethod("refill")
		Promise<String> alpha(@JsonRpcParam("amount") int amount);

		@JsonRpcNotification("red")
		void zulu();
	}

	/** A service declaring nothing. Valid: a contract with no methods is not an error (rule M2). */
	@JsonRpcService("empty")
	public interface EmptyApi {
	}

	/** Claims {@code reference.sum}, which {@link ReferenceApi} already claims. */
	@JsonRpcService("reference")
	public interface CollidingApi {
		@JsonRpcMethod("sum")
		Promise<Double> sum(double left, double right);
	}

	/**
	 * Reaches into the namespace JSON-RPC 2.0 reserves for protocol-level methods. Contract rule 10 refuses
	 * it, which is <i>why</i> the generator needs no {@code rpc.discover} filter of its own — see
	 * {@link #theReservedNamespaceIsUnreachableFromAContractAtAll()}.
	 */
	@JsonRpcService("rpc")
	public interface ReservedApi {
		@JsonRpcMethod("discover")
		Promise<String> discover();
	}

	// -------------------------------------------------------------------------------------------------
	// (a) M2 - which methods appear, and in which order
	// -------------------------------------------------------------------------------------------------

	@Test
	public void everyWireNameOfTheContractAppearsExactlyOnceSortedByWireName() {
		OpenRpcDocument document = generate(ReferenceApi.class);

		assertEquals(REFERENCE_NAMES_SORTED, names(document));
		assertEquals("the fixture's own wire names, and no others",
			contractOf(ReferenceApi.class).wireNames(), new HashSet<>(names(document)));
	}

	@Test
	public void theDocumentOrderIsWireNameOrderAndNotTheContractsDiscoveryOrder() {
		JsonRpcServiceContract contract = contractOf(BracketApi.class);

		assertEquals("the fixture must keep the two orders distinguishable",
			List.of("refill", "red"), new ArrayList<>(contract.wireNames()));
		assertEquals(List.of("red", "refill"), names(JsonRpcSchemaGenerator.generate(INFO, contract)));
	}

	@Test
	public void theUnionOfSeveralContractsIsSortedAcrossAllOfThem() {
		OpenRpcDocument document = generate(ReferenceApi.class, BracketApi.class);

		List<String> expected = new ArrayList<>();
		expected.add("red");
		expected.addAll(REFERENCE_NAMES_SORTED);
		expected.add("refill");
		assertEquals(expected, names(document));

		// the same statement made structurally, so it survives a change to either fixture
		List<String> sorted = new ArrayList<>(names(document));
		Collections.sort(sorted);
		assertEquals(sorted, names(document));
	}

	@Test
	public void aContractWithNoMethodsStillProducesAValidDocument() {
		OpenRpcDocument document = generate(EmptyApi.class);

		assertEquals(List.of(), document.methods());
		assertEquals(
			"{\"openrpc\":\"1.4.0\",\"info\":{\"title\":\"Reference API\",\"version\":\"1.0.0\"}," +
			"\"methods\":[]}",
			json(document));
	}

	@Test
	public void noContractAtAllProducesTheSameEmptyDocument() {
		assertEquals(json(generate(EmptyApi.class)), json(JsonRpcSchemaGenerator.generate(INFO)));
		assertEquals(List.of(), JsonRpcSchemaGenerator.generate(INFO, List.of()).methods());
	}

	@Test
	public void twoServicesClaimingOneWireNameAreRefused() {
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
			() -> generate(ReferenceApi.class, CollidingApi.class));

		assertTrue(e.getMessage(), e.getMessage().contains("reference.sum"));
		assertTrue(e.getMessage(), e.getMessage().contains(ReferenceApi.class.getName()));
		assertTrue(e.getMessage(), e.getMessage().contains(CollidingApi.class.getName()));
	}

	// -------------------------------------------------------------------------------------------------
	// (b) rpc.discover never appears - and why no filter is needed to keep that true
	// -------------------------------------------------------------------------------------------------

	@Test
	public void noEmittedNameLiesInTheReservedRpcNamespace() {
		for (String name : names(generate(ReferenceApi.class, BracketApi.class))) {
			assertFalse(name + " lies in the reserved 'rpc.' namespace", name.startsWith("rpc."));
		}
	}

	@Test
	public void theReservedNamespaceIsUnreachableFromAContractAtAll() {
		// This is the invariant that LICENSES the generator's lack of an rpc.discover filter (M2): its input
		// type is a validated contract, and contract rule 10 (FR-001) refuses the whole 'rpc.' prefix at the
		// wire-name computation point. The dispatcher's own built-in rpc.discover entry (T013) is registered
		// into the handler table directly and belongs to no contract, so it cannot reach here either.
		//
		// If rule 10 is ever relaxed - research Decision 2 contemplates future protocol methods - this
		// assertion fails, and the generator must grow an explicit filter in the same change.
		JsonRpcContractException e =
			assertThrows(JsonRpcContractException.class, () -> contractOf(ReservedApi.class));

		assertEquals(1, e.violations().size());
		assertTrue(e.violations().toString(), e.violations().get(0).contains("rpc.discover"));
	}

	// -------------------------------------------------------------------------------------------------
	// (c) M4 - params, in declaration order, named or positionally labelled
	// -------------------------------------------------------------------------------------------------

	@Test
	public void paramsAppearInDeclarationOrderUnderTheirJsonRpcParamNames() {
		OpenRpcDocument document = generate(ReferenceApi.class);

		assertEquals(List.of("customer", "quantity", "express"),
			paramNames(method(document, "reference.createOrder")));
		assertEquals(
			List.of("flag", "tiny", "small", "count", "id", "ratio", "amount", "label", "initial"),
			paramNames(method(document, "reference.scalars")));
		assertEquals(List.of("tags", "lines", "statuses", "quantities"),
			paramNames(method(document, "reference.containers")));
	}

	@Test
	public void anUnannotatedParamGetsThePositionalLabelForItsIndex() {
		OpenRpcMethod sum = method(generate(ReferenceApi.class), "reference.sum");

		assertEquals(List.of("param0", "param1"), paramNames(sum));
	}

	@Test
	public void aMethodWithNoParamsCarriesAnEmptyParamList() {
		OpenRpcMethod catalogue = method(generate(ReferenceApi.class), "reference.catalogue");

		assertEquals(List.of(), catalogue.params());
	}

	@Test
	public void everyParamSchemaIsWhatTheMappingSaysAboutItsDeclaredType() {
		OpenRpcMethod createOrder = method(generate(ReferenceApi.class), "reference.createOrder");

		assertEquals(JsonSchemaMapping.map(String.class), createOrder.params().get(0).schema());
		assertEquals(JsonSchemaMapping.map(int.class), createOrder.params().get(1).schema());
		assertEquals(JsonSchemaMapping.map(boolean.class), createOrder.params().get(2).schema());
	}

	// -------------------------------------------------------------------------------------------------
	// (d) M5 - paramStructure is present exactly when the method is not namable
	// -------------------------------------------------------------------------------------------------

	@Test
	public void aPositionalMethodCarriesParamStructureByPosition() {
		OpenRpcDocument document = generate(ReferenceApi.class);

		assertEquals(OpenRpcMethod.BY_POSITION, method(document, "reference.sum").paramStructure());
		// the two axes crossed: a positional NOTIFICATION carries the member too
		assertEquals(OpenRpcMethod.BY_POSITION, method(document, "reference.ping").paramStructure());
		assertEquals(List.of("param0"), paramNames(method(document, "reference.ping")));
	}

	@Test
	public void aNamableMethodOmitsParamStructureEntirely() {
		OpenRpcDocument document = generate(ReferenceApi.class);

		assertNull(method(document, "reference.createOrder").paramStructure());
		assertNull(method(document, "reference.orderSeen").paramStructure());
		// vacuously namable: no params at all
		assertNull(method(document, "reference.catalogue").paramStructure());
	}

	// -------------------------------------------------------------------------------------------------
	// (e) M3 / M7 - the result member, its absence, and the null schema
	// -------------------------------------------------------------------------------------------------

	@Test
	public void aNotificationHasNoResultContentDescriptorAtAll() {
		OpenRpcDocument document = generate(ReferenceApi.class);

		assertNull(method(document, "reference.orderSeen").result());
		assertNull(method(document, "reference.ping").result());

		// ... while every callable method has one
		for (String name : List.of("reference.archive", "reference.audit", "reference.boxedScalars",
			"reference.catalogue", "reference.containers", "reference.createOrder", "reference.scalars",
			"reference.sum")
		) {
			assertNotNull(name + " is callable and must carry a result", method(document, name).result());
		}
	}

	@Test
	public void theResultContentDescriptorIsNamedResult() {
		OpenRpcDocument document = generate(ReferenceApi.class);

		assertEquals("result", JsonRpcSchemaGenerator.RESULT_NAME);
		assertEquals(JsonRpcSchemaGenerator.RESULT_NAME,
			method(document, "reference.createOrder").result().name());
		assertEquals(JsonRpcSchemaGenerator.RESULT_NAME, method(document, "reference.sum").result().name());
	}

	@Test
	public void aPromiseVoidResultIsDescribedAsTheNullType() {
		// M7: resultCodec() == null on a callable method means void / Promise<Void>, and the wire value is
		// the JSON literal null - a PRESENT value, which is why the member is there at all
		OpenRpcContentDescriptor result = method(generate(ReferenceApi.class), "reference.archive").result();

		assertNotNull(result);
		assertEquals(JsonSchemaMapping.VOID_RESULT, result.schema());
		assertEquals(Typed.of("null"), result.schema());
	}

	@Test
	public void anAsynchronousResultIsDescribedByItsTypeArgumentAndNotByThePromise() {
		OpenRpcDocument document = generate(ReferenceApi.class);

		assertEquals(JsonSchemaMapping.map(Order.class),
			method(document, "reference.createOrder").result().schema());
		assertEquals(JsonSchemaMapping.map(Double.class),
			method(document, "reference.sum").result().schema());
		// Promise<List<Status>>: the type ARGUMENT is described, generic parameters and all
		assertEquals(Typed.array(JsonSchemaMapping.map(Status.class)),
			method(document, "reference.catalogue").result().schema());
	}

	@Test
	public void aTypeOutsideTheSubsetIsMarkedInBothPositions() {
		OpenRpcMethod audit = method(generate(ReferenceApi.class), "reference.audit");

		assertEquals(Any.INSTANCE, audit.params().get(0).schema());   // Instant
		assertEquals(Any.INSTANCE, audit.params().get(1).schema());   // Map<Status, BigDecimal>
		assertEquals(Any.INSTANCE, audit.result().schema());          // UUID
	}

	// -------------------------------------------------------------------------------------------------
	// (f) the document envelope, and the byte-producing entry point
	// -------------------------------------------------------------------------------------------------

	@Test
	public void theDocumentCarriesThePinnedVersionAndTheSuppliedInfoVerbatim() {
		OpenRpcDocument document = generate(ReferenceApi.class);

		assertEquals(OpenRpcDocument.OPENRPC_VERSION, document.openrpc());
		assertEquals(INFO, document.info());
		assertEquals("Reference API", document.info().title());
		assertEquals("1.0.0", document.info().version());
	}

	@Test
	public void generateBytesIsTheDocumentEncodedByItsOwnCodec() throws MalformedDataException {
		JsonRpcServiceContract contract = contractOf(ReferenceApi.class);

		OpenRpcDocument document = JsonRpcSchemaGenerator.generate(INFO, contract);
		byte[] bytes = JsonRpcSchemaGenerator.generateBytes(INFO, contract);

		assertArrayEquals(JsonUtils.toJsonBytes(OpenRpcDocument.CODEC, document), bytes);
		assertEquals(document, JsonUtils.fromJsonBytes(OpenRpcDocument.CODEC, bytes));
		assertTrue(new String(bytes, StandardCharsets.UTF_8).startsWith("{\"openrpc\":\"1.4.0\","));
	}

	@Test
	public void generationIsDeterministicAndIndependentOfTheContractOrderItWasGiven() {
		// FR-040 rests on it: the runtime answer, the offline export and the frozen reference are the same
		// bytes only if two calls over the same contracts agree, however the caller happened to order them
		assertArrayEquals(
			JsonRpcSchemaGenerator.generateBytes(INFO,
				contractOf(ReferenceApi.class), contractOf(BracketApi.class)),
			JsonRpcSchemaGenerator.generateBytes(INFO,
				contractOf(BracketApi.class), contractOf(ReferenceApi.class)));
	}

	@Test
	public void nullArgumentsAreRefused() {
		JsonRpcServiceContract contract = contractOf(ReferenceApi.class);

		assertThrows(NullPointerException.class, () -> JsonRpcSchemaGenerator.generate(null, contract));
		assertThrows(NullPointerException.class,
			() -> JsonRpcSchemaGenerator.generate(INFO, (List<JsonRpcServiceContract>) null));
		assertThrows(NullPointerException.class,
			() -> JsonRpcSchemaGenerator.generate(INFO, (JsonRpcServiceContract) null));
		assertThrows(NullPointerException.class, () -> JsonRpcSchemaGenerator.generateBytes(null, contract));
	}

	// -------------------------------------------------------------------------------------------------
	// (g) FR-040 - one generator, three consumers.
	//
	// The claim is not "the three agree about the service", it is that they are THE SAME BYTES. So every
	// assertion below is a byte comparison, and the three are cross-checked against each OTHER rather than
	// each against a literal: a document frozen in this file would be a fourth source of truth, and the
	// first thing to drift.
	//
	//   1. rpc.discover at runtime      - dispatch(...) through the dispatcher's frozen handler table (T013)
	//   2. the offline export           - JsonRpcSchemaGenerator.generateBytes(...), no dispatcher at all
	//   3. the built dispatcher's own accessor (T017) - what the transport tier reads to serve a GET
	// -------------------------------------------------------------------------------------------------

	@Test
	public void theRuntimeAnswerTheOfflineExportAndTheAccessorAreTheSameBytes() {
		JsonRpcDispatcher dispatcher = discovering();

		byte[] runtime = discoverResult(dispatcher);
		byte[] offline = JsonRpcSchemaGenerator.generateBytes(INFO, contractOf(ReferenceApi.class));
		byte[] accessor = dispatcher.discoveryDocument();

		assertNotNull("a dispatcher built with withDiscovery(...) must expose the document it serves", accessor);
		// all three pairs, spelled out: an equality that holds only by transitivity through one of them would
		// leave the failing pair unnamed, and the point of the test is to name which consumer drifted
		assertArrayEquals("offline export != accessor", offline, accessor);
		assertArrayEquals("accessor != what rpc.discover answered", accessor, runtime);
		assertArrayEquals("offline export != what rpc.discover answered", offline, runtime);
	}

	@Test
	public void theAccessorIsAbsentWhenDiscoveryWasNeverEnabled() {
		// FR-003 / FR-004: presence of the array is presence of the feature, exactly as presence of the wire
		// name is. There is no empty document and no disabled-signalling document to tell apart from a real one
		JsonRpcDispatcher plain = JsonRpcDispatcher.builder(Reactor.getCurrentReactor())
			.withService(ReferenceApi.class, new ReferenceApiImpl())
			.build();

		assertNull(plain.discoveryDocument());
		assertFalse(plain.wireNames().contains("rpc.discover"));
	}

	@Test
	public void theAccessorHandsOutTheOneArrayComputedAtBuildTime() {
		// FR-015 / FR-032: computed once, never regenerated and never copied per call - which is also the
		// ownership statement the accessor's Javadoc makes, pinned here so a defensive copy cannot be added
		// silently and turn a shared constant into a per-call allocation on the transport tier's path
		JsonRpcDispatcher dispatcher = discovering();

		assertSame(dispatcher.discoveryDocument(), dispatcher.discoveryDocument());
	}

	@Test
	public void theAccessorCarriesTheUnionOfEveryRegisteredService() {
		// the dispatcher must hand the generator ALL its contracts, not the first or the last: two services
		// whose wire names interleave (BracketApi brackets the reference.* block) cannot agree by accident
		JsonRpcDispatcher dispatcher = JsonRpcDispatcher.builder(Reactor.getCurrentReactor())
			.withService(ReferenceApi.class, new ReferenceApiImpl())
			.withService(BracketApi.class, BRACKET)
			.withDiscovery(INFO)
			.build();

		assertArrayEquals(
			JsonRpcSchemaGenerator.generateBytes(INFO,
				contractOf(ReferenceApi.class), contractOf(BracketApi.class)),
			dispatcher.discoveryDocument());
		assertArrayEquals(dispatcher.discoveryDocument(), discoverResult(dispatcher));
	}

	@Test
	public void theInfoTheAccessorCarriesIsTheOneTheApplicationSupplied() {
		// FR-013: nothing derives a title from a class name or a version from a POM, on any of the three paths
		OpenRpcInfo other = new OpenRpcInfo("Some Other Name", "9.9.9");
		JsonRpcDispatcher dispatcher = JsonRpcDispatcher.builder(Reactor.getCurrentReactor())
			.withService(ReferenceApi.class, new ReferenceApiImpl())
			.withDiscovery(other)
			.build();

		assertArrayEquals(
			JsonRpcSchemaGenerator.generateBytes(other, contractOf(ReferenceApi.class)),
			dispatcher.discoveryDocument());
		assertTrue(new String(dispatcher.discoveryDocument(), StandardCharsets.UTF_8)
			.contains("\"title\":\"Some Other Name\",\"version\":\"9.9.9\""));
	}

	// -------------------------------------------------------------------------------------------------

	/** {@link BracketApi}'s answers are never read — only its contract is, and a contract needs no behaviour. */
	private static final BracketApi BRACKET = new BracketApi() {
		@Override
		public Promise<String> alpha(int amount) {
			return Promise.of(String.valueOf(amount));
		}

		@Override
		public void zulu() {}
	};

	private static final String DISCOVER_REQUEST = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"rpc.discover\"}";
	private static final String RESULT_PREFIX = "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":";

	private static JsonRpcDispatcher discovering() {
		return JsonRpcDispatcher.builder(Reactor.getCurrentReactor())
			.withService(ReferenceApi.class, new ReferenceApiImpl())
			.withDiscovery(INFO)
			.build();
	}

	/** The {@code result} member of a real {@code rpc.discover} answer, sliced out of the response bytes. */
	private static byte[] discoverResult(JsonRpcDispatcher dispatcher) {
		byte[] response = await(dispatcher.dispatch(DISCOVER_REQUEST.getBytes(StandardCharsets.UTF_8)));
		String text = new String(response, StandardCharsets.UTF_8);

		assertTrue(text, text.startsWith(RESULT_PREFIX));
		assertTrue(text, text.endsWith("}"));
		int start = RESULT_PREFIX.getBytes(StandardCharsets.UTF_8).length;
		return Arrays.copyOfRange(response, start, response.length - 1);
	}

	private static JsonRpcServiceContract contractOf(Class<?> serviceType) {
		return JsonRpcServiceContract.of(serviceType, JsonCodecFactory.defaultInstance());
	}

	private static OpenRpcDocument generate(Class<?>... serviceTypes) {
		return JsonRpcSchemaGenerator.generate(INFO,
			Arrays.stream(serviceTypes).map(JsonRpcSchemaGeneratorTest::contractOf).toList());
	}

	private static List<String> names(OpenRpcDocument document) {
		return document.methods().stream().map(OpenRpcMethod::name).toList();
	}

	private static List<String> paramNames(OpenRpcMethod method) {
		return method.params().stream().map(OpenRpcContentDescriptor::name).toList();
	}

	private static OpenRpcMethod method(OpenRpcDocument document, String wireName) {
		for (OpenRpcMethod method : document.methods()) {
			if (method.name().equals(wireName)) return method;
		}
		fail(wireName + " is absent from " + names(document));
		throw new AssertionError();
	}

	private static String json(OpenRpcDocument document) {
		return JsonUtils.toJson(OpenRpcDocument.CODEC, document);
	}
}
