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
import io.activej.jsonrpc.schema.fixtures.ReferenceApi;
import io.activej.jsonrpc.schema.fixtures.ScaleApi;
import io.activej.jsonrpc.service.JsonRpcMethod;
import io.activej.jsonrpc.service.JsonRpcNotification;
import io.activej.jsonrpc.service.JsonRpcParam;
import io.activej.jsonrpc.service.JsonRpcService;
import io.activej.jsonrpc.service.JsonRpcServiceContract;
import io.activej.promise.Promise;
import org.junit.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Adversarial coverage of {@link JsonRpcSchemaGenerator} — the sort at a scale and with wire names that break
 * a comparator which is only accidentally right, the cross-contract collision refusal with many colliding
 * pairs rather than one, and a regression re-check of {@link OpenRpcSchemaTest}'s drift message.
 *
 * <p>{@link JsonRpcSchemaGeneratorTest} already pins the rules themselves — M2/M3/M4/M5, the empty document,
 * the two-contract collision, {@code rpc.discover}'s unreachability, and FR-040's three consumers agreeing
 * byte for byte. None of that is repeated. What is added is <b>the same rules under load and under an input
 * arranged to be as unhelpful as possible</b>:
 *
 * <ol>
 *     <li>{@link ScaleApi} — 500 methods whose declaration order is the exact reverse of the emitted order,
 *     whose names collide numerically ({@code method10} before {@code method2}), by case ({@code ITEM3},
 *     {@code Item3}, {@code item3}) and across the namespace separator ({@code a0}, {@code a0.b}, {@code a0Z},
 *     {@code a0b}). A generator that sorts numerically, folds case, or treats the dot as a separator emits a
 *     <i>different</i> document and fails here rather than in a consumer's tooling.</li>
 *     <li>Ten contracts with <b>three distinct colliding groups</b>, generated in 200 deterministic
 *     permutations. The refusal must name a genuinely offending pair every time — not one pair it happened to
 *     find first in the order the test author wrote.</li>
 *     <li>Two drift categories {@link OpenRpcSchemaTest} does not exercise (a parameter removed, a parameter
 *     renamed) plus the same mechanism run against the <b>real committed frozen bytes</b> rather than a model
 *     re-encoded — a guard that the diff message still works on the file it exists for.</li>
 * </ol>
 *
 * <p>No {@code EventloopRule}: nothing here dispatches. The generator is static, synchronous and reactor-free,
 * and a contract needs no reactor and no implementation instance to be built.
 */
public final class JsonRpcSchemaGeneratorAdversarialTest {

	private static final OpenRpcInfo INFO = new OpenRpcInfo("Scale API", "1.0.0");

	// -------------------------------------------------------------------------------------------------
	// (a) 500 methods. Rule M2 at a size where an accidentally-correct sort stops being accidental.
	// -------------------------------------------------------------------------------------------------

	@Test
	public void everyOneOfTheFiveHundredWireNamesAppearsExactlyOnceInNaturalStringOrder() {
		OpenRpcDocument document = generate(ScaleApi.class);
		List<String> names = names(document);

		assertEquals(500, names.size());
		assertEquals("a wire name was emitted twice", 500, new LinkedHashSet<>(names).size());
		assertEquals("the emitted set is the contract's own wire-name set",
			contractOf(ScaleApi.class).wireNames(), new LinkedHashSet<>(names));

		List<String> sorted = new ArrayList<>(names);
		Collections.sort(sorted);
		assertEquals("rule M2: sorted by wire name, natural String order", sorted, names);

		// the fixture is only adversarial if the contract really does hand them over backwards
		List<String> discoveryOrder = new ArrayList<>(contractOf(ScaleApi.class).wireNames());
		Collections.reverse(discoveryOrder);
		assertEquals("the fixture stopped being adversarial: declaration order is no longer the reverse " +
					 "of the emitted order", names, discoveryOrder);
	}

	@Test
	public void aLongCommonPrefixDoesNotStopTheSortFromBeingLexicographic() {
		List<String> names = names(generate(ScaleApi.class));
		String prefix = "scale.deeply.nested.namespace.with.a.long.common.prefix.method";

		List<String> block = names.stream().filter(name -> name.startsWith(prefix)).toList();
		assertEquals(200, block.size());

		// the 200 names share 61 characters and differ only in an unpadded decimal, so lexicographic and
		// numeric order disagree on almost every pair. This is the assertion a numeric sort fails
		assertEquals(prefix + "0", block.get(0));
		assertEquals(prefix + "1", block.get(1));
		assertEquals(prefix + "10", block.get(2));
		assertEquals(prefix + "100", block.get(3));

		assertTrue("method10 must precede method2 - natural String order, not numeric",
			block.indexOf(prefix + "10") < block.indexOf(prefix + "2"));
		assertTrue("method199 must precede method2 as well",
			block.indexOf(prefix + "199") < block.indexOf(prefix + "2"));

		// and the block is contiguous: a sort that grouped by prefix and then failed to order within the
		// group would still satisfy the two assertions above if the block were split
		int first = names.indexOf(block.get(0));
		assertEquals(block, names.subList(first, first + 200));
	}

	@Test
	public void namesDifferingOnlyInCaseAreOrderedByCodePointAndNeverFolded() {
		List<String> names = names(generate(ScaleApi.class));

		// 'I'(0x49) < 'I'... the three spellings differ at the first character after "scale.case.":
		// 'I' (0x49) for ITEM, 'I' for Item, 'i' (0x69) for item - so uppercase-first, and ITEM3 before
		// Item3 because 'T'(0x54) < 't'(0x74). A Collator or CASE_INSENSITIVE_ORDER puts these in a
		// locale- or JDK-dependent order, which is exactly what rule M2's "natural String order" refuses
		assertTrue(names.indexOf("scale.case.ITEM3") < names.indexOf("scale.case.Item3"));
		assertTrue(names.indexOf("scale.case.Item3") < names.indexOf("scale.case.item3"));

		// and case beats numeric adjacency: every ITEM* precedes every Item*, which case folding would
		// interleave
		int lastUpper = names.indexOf("scale.case.ITEM9");
		int firstMixed = names.indexOf("scale.case.Item0");
		assertTrue("the three case blocks must not interleave", lastUpper < firstMixed);
	}

	@Test
	public void theNamespaceSeparatorIsJustACharacterAndSortsWhereItsCodePointSays() {
		List<String> names = names(generate(ScaleApi.class));

		// '.'(0x2e) < 'Z'(0x5a) < 'b'(0x62), so the four names below are in THAT order, which is not the
		// order any dot-aware "namespace then leaf" comparator produces
		List<String> quartet = names.stream().filter(name -> name.startsWith("scale.edge.a0")).toList();
		assertEquals(List.of("scale.edge.a0", "scale.edge.a0.b", "scale.edge.a0Z", "scale.edge.a0b"), quartet);
	}

	@Test
	public void theFiveHundredMethodDocumentIsDeterministicAndReadsBackEqual() throws MalformedDataException {
		JsonRpcServiceContract contract = contractOf(ScaleApi.class);

		byte[] first = JsonRpcSchemaGenerator.generateBytes(INFO, contract);
		byte[] second = JsonRpcSchemaGenerator.generateBytes(INFO, contract);
		byte[] fromAFreshContract = JsonRpcSchemaGenerator.generateBytes(INFO, contractOf(ScaleApi.class));

		assertArrayEquals(first, second);
		assertArrayEquals("a second introspection of the same interface must produce the same document",
			first, fromAFreshContract);
		assertEquals(JsonRpcSchemaGenerator.generate(INFO, contract),
			JsonUtils.fromJsonBytes(OpenRpcDocument.CODEC, first));

		// one document, not five hundred documents concatenated: a bound loose enough to survive any
		// reasonable fixture edit and tight enough to catch a per-method duplication of the envelope
		assertTrue("the document is " + first.length + " bytes for 500 methods", first.length < 1_000_000);
		assertTrue(new String(first, UTF_8).startsWith("{\"openrpc\":\"1.4.0\","));
	}

	@Test
	public void allFourMethodShapesSurviveTheScaleAndAccountForEveryMethod() {
		OpenRpcDocument document = generate(ScaleApi.class);

		int notifications = 0;
		int twoNamedParams = 0;
		int onePositionalParam = 0;
		int noParams = 0;
		for (OpenRpcMethod method : document.methods()) {
			if (method.result() == null) {
				// M3: absence of `result` IS the notification marker
				notifications++;
				assertEquals(List.of("id"), paramNames(method));
				assertNull("a named parameter makes the method namable (M5)", method.paramStructure());
			} else if (method.params().size() == 2) {
				twoNamedParams++;
				assertEquals(List.of("left", "right"), paramNames(method));
				assertNull(method.paramStructure());
			} else if (method.params().size() == 1) {
				onePositionalParam++;
				// M4: an unannotated parameter is described by the positional label for its index...
				assertEquals(List.of("param0"), paramNames(method));
				// ... and M5 says so on the method itself
				assertEquals(OpenRpcMethod.BY_POSITION, method.paramStructure());
			} else {
				noParams++;
				assertEquals(List.of(), method.params());
				assertNull("vacuously namable", method.paramStructure());
			}
			assertFalse(method.name() + " lies in the reserved 'rpc.' namespace", method.name().startsWith("rpc."));
		}

		assertEquals(500, notifications + twoNamedParams + onePositionalParam + noParams);
		assertTrue("the fixture must keep exercising all four shapes",
			notifications > 0 && twoNamedParams > 0 && onePositionalParam > 0 && noParams > 0);
	}

	@Test
	public void generatingTheFiveHundredMethodDocumentIsNotQuadratic() {
		// A deliberately loose ceiling. Warm, one generation over 500 methods is single-digit
		// milliseconds; ten of them cannot approach ten seconds unless the complexity class changed -
		// a per-method re-sort, a per-method re-encode of the whole document, a re-introspection
		JsonRpcServiceContract contract = contractOf(ScaleApi.class);
		JsonRpcSchemaGenerator.generateBytes(INFO, contract);   // warm the codecs

		long start = System.nanoTime();
		for (int i = 0; i < 10; i++) JsonRpcSchemaGenerator.generateBytes(INFO, contract);
		long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

		assertTrue("10 generations over 500 methods took " + elapsedMillis + "ms", elapsedMillis < 10_000);
	}

	@Test
	public void aFiveHundredMethodContractCombinesWithOthersAndTheSortStaysGlobal() {
		// M2 is a sort across ALL contracts, not per-contract blocks - the property most likely to be
		// quietly lost at a size where one contract dominates the document
		OpenRpcDocument document = generate(ScaleApi.class, Solo.class);
		List<String> names = names(document);

		assertEquals(501, names.size());
		List<String> sorted = new ArrayList<>(names);
		Collections.sort(sorted);
		assertEquals(sorted, names);

		// it lands INSIDE the 500 - after the scale.edge.* block, before the scale.z* one - rather than
		// being appended or prepended the way a per-contract block would be
		int at = names.indexOf("scale.middle.of.the.block");
		assertTrue("the solo method must interleave, not bracket: it landed at " + at, at > 0 && at < 500);
		assertTrue(names.get(at - 1), names.get(at - 1).startsWith("scale.edge."));
		assertTrue(names.get(at + 1), names.get(at + 1).startsWith("scale.z"));
	}

	/** One method whose wire name sorts into the middle of {@link ScaleApi}'s 500. */
	@JsonRpcService("")
	public interface Solo {
		@JsonRpcMethod("scale.middle.of.the.block")
		Promise<String> only();
	}

	// -------------------------------------------------------------------------------------------------
	// (b) Cross-contract collisions, with more than one colliding pair in play.
	//
	// The refusal has to be true, not merely thrown: the two service types it names must BOTH actually
	// claim the wire name it names. With one colliding pair that is hard to get wrong; with three groups
	// and ten contracts in an arbitrary order it is exactly the kind of thing a "first found versus
	// everything else" bookkeeping bug produces.
	// -------------------------------------------------------------------------------------------------

	@JsonRpcService("")
	public interface S0 {
		@JsonRpcMethod("collide.alpha") Promise<String> aaa();

		@JsonRpcMethod("s0.unique") Promise<String> zzz();
	}

	@JsonRpcService("")
	public interface S1 {
		@JsonRpcMethod("s1.unique") Promise<String> aaa();

		@JsonRpcMethod("collide.beta") Promise<String> zzz();
	}

	@JsonRpcService("")
	public interface S2 {
		@JsonRpcMethod("s2.one") Promise<String> aaa();

		@JsonRpcMethod("s2.two") Promise<String> zzz();
	}

	@JsonRpcService("")
	public interface S3 {
		@JsonRpcMethod("s3.unique") Promise<String> aaa();

		@JsonRpcMethod("collide.alpha") Promise<String> zzz();
	}

	@JsonRpcService("")
	public interface S4 {
		@JsonRpcMethod("collide.gamma") Promise<String> aaa();

		@JsonRpcMethod("s4.unique") Promise<String> zzz();
	}

	@JsonRpcService("")
	public interface S5 {
		@JsonRpcMethod("s5.unique") Promise<String> aaa();

		@JsonRpcMethod("collide.gamma") Promise<String> zzz();
	}

	@JsonRpcService("")
	public interface S6 {
		@JsonRpcNotification("s6.only") void aaa(@JsonRpcParam("id") long id);
	}

	@JsonRpcService("")
	public interface S7 {
		@JsonRpcMethod("collide.alpha") Promise<String> aaa();

		@JsonRpcMethod("s7.unique") Promise<String> zzz();
	}

	@JsonRpcService("")
	public interface S8 {
		@JsonRpcMethod("s8.only") Promise<String> aaa();
	}

	@JsonRpcService("")
	public interface S9 {
		@JsonRpcMethod("collide.beta") Promise<String> aaa();

		@JsonRpcMethod("s9.unique") Promise<String> zzz();
	}

	private static final List<Class<?>> TEN_SERVICES =
		List.of(S0.class, S1.class, S2.class, S3.class, S4.class, S5.class, S6.class, S7.class, S8.class, S9.class);

	/** Ground truth, written down independently of the interfaces above: who claims what. */
	private static final Map<String, Set<Class<?>>> CLAIMANTS = Map.of(
		"collide.alpha", Set.of(S0.class, S3.class, S7.class),
		"collide.beta", Set.of(S1.class, S9.class),
		"collide.gamma", Set.of(S4.class, S5.class));

	private static final Pattern REFUSAL = Pattern.compile(
		"wire name '(?<name>[^']+)' is claimed by two services: (?<first>\\S+) and (?<second>[^;]+);");

	@Test
	public void theClaimantsFixtureIsWhatItSaysItIs() {
		// the ground truth above is only useful if it is checked against the interfaces rather than
		// asserted about them: a fixture edit that broke the correspondence would make every assertion in
		// this section vacuously true
		Map<String, Set<String>> actual = new LinkedHashMap<>();
		for (Class<?> service : TEN_SERVICES) {
			for (String wireName : contractOf(service).wireNames()) {
				actual.computeIfAbsent(wireName, ignored -> new LinkedHashSet<>()).add(service.getName());
			}
		}
		for (Map.Entry<String, Set<Class<?>>> entry : CLAIMANTS.entrySet()) {
			assertEquals(entry.getKey(),
				entry.getValue().stream().map(Class::getName).collect(Collectors.toSet()),
				actual.get(entry.getKey()));
		}
		long shared = actual.values().stream().filter(claimants -> claimants.size() > 1).count();
		assertEquals("exactly three names must be contested", 3, shared);
	}

	@Test
	public void everyPermutationOfTenContractsIsRefusedNamingAPairThatReallyCollides() {
		List<Class<?>> services = new ArrayList<>(TEN_SERVICES);
		Random random = new Random(20260822);   // fixed seed: this test is deterministic or it is nothing
		Set<String> namesReported = new LinkedHashSet<>();

		for (int round = 0; round < 200; round++) {
			Collections.shuffle(services, random);
			List<JsonRpcServiceContract> contracts = services.stream()
				.map(JsonRpcSchemaGeneratorAdversarialTest::contractOf).toList();

			IllegalArgumentException e = assertThrows("order " + services,
				IllegalArgumentException.class, () -> JsonRpcSchemaGenerator.generate(INFO, contracts));

			Matcher matcher = REFUSAL.matcher(e.getMessage());
			assertTrue("unparseable refusal: " + e.getMessage(), matcher.find());
			String name = matcher.group("name");
			String first = matcher.group("first");
			String second = matcher.group("second");

			Set<Class<?>> claimants = CLAIMANTS.get(name);
			assertNotNull("named a wire name nobody contests: " + e.getMessage(), claimants);
			Set<String> claimantNames = new LinkedHashSet<>();
			for (Class<?> claimant : claimants) claimantNames.add(claimant.getName());

			// the whole point: BOTH named services must really claim the named name
			assertTrue("first claimant does not claim " + name + ": " + e.getMessage(),
				claimantNames.contains(first));
			assertTrue("second claimant does not claim " + name + ": " + e.getMessage(),
				claimantNames.contains(second));
			assertFalse("a service was reported as colliding with itself: " + e.getMessage(),
				first.equals(second));

			namesReported.add(name);
		}

		// and it is not always the same group: over 200 orders every contested name is reported at least
		// once, which is the difference between "names a real pair" and "names the pair the author put
		// first"
		assertEquals(CLAIMANTS.keySet(), namesReported);
	}

	@Test
	public void theNonCollidingSubsetOfTheSameTenGeneratesCleanly() {
		// the refusal must be caused by the collision and by nothing else about ten contracts at once
		OpenRpcDocument document = generate(S2.class, S6.class, S8.class);

		assertEquals(List.of("s2.one", "s2.two", "s6.only", "s8.only"), names(document));
	}

	@Test
	public void aCollisionIsFoundWhereverItSitsInEitherContract() {
		// S0 claims collide.alpha FIRST and S3 claims it LAST (their Java identifiers, which is the order
		// a contract iterates in, are aaa/zzz the other way round). Both directions are refused, so the
		// check cannot be reading only the head of either method table
		assertTrue(messageOf(S0.class, S3.class).contains("collide.alpha"));
		assertTrue(messageOf(S3.class, S0.class).contains("collide.alpha"));
	}

	@Test
	public void theSameContractPassedTwiceIsRefusedNamingItselfOnBothSides() {
		// CHARACTERIZATION. Handing the same contract over twice is a caller mistake, and the refusal is
		// correct but reads oddly: "claimed by two services: X and X". It is pinned rather than fixed
		// because the message is right about the situation - the name IS claimed twice - and because a
		// special case for it would be a branch that only ever fires on a programming error the very next
		// line of the message already explains
		JsonRpcServiceContract contract = contractOf(S8.class);

		IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
			() -> JsonRpcSchemaGenerator.generate(INFO, contract, contract));

		assertTrue(e.getMessage(), e.getMessage().contains("s8.only"));
		assertTrue(e.getMessage(), e.getMessage().contains(S8.class.getName() + " and " + S8.class.getName()));
	}

	@Test
	public void theRefusalHappensBeforeAnyDocumentIsProduced() {
		// a partially built document must never escape: generate() either returns a complete document or
		// throws, and there is no third outcome a caller could observe
		List<JsonRpcServiceContract> contracts =
			List.of(contractOf(S0.class), contractOf(S2.class), contractOf(S3.class));

		assertThrows(IllegalArgumentException.class, () -> JsonRpcSchemaGenerator.generate(INFO, contracts));
		assertThrows(IllegalArgumentException.class, () -> JsonRpcSchemaGenerator.generateBytes(INFO, contracts));
	}

	// -------------------------------------------------------------------------------------------------
	// (c) The drift message, re-checked. OpenRpcSchemaTest owns this mechanism and tests six categories;
	// two more are added here, and the third test below runs the mechanism against the REAL committed
	// bytes rather than a model re-encoded - the only form in which it will ever actually be used.
	// -------------------------------------------------------------------------------------------------

	@Test
	public void theDriftMessageNamesAParameterThatDisappeared() {
		OpenRpcDocument reference = referenceDocument();
		OpenRpcMethod createOrder = method(reference, "reference.createOrder");
		List<OpenRpcContentDescriptor> shortened = createOrder.params().subList(0, 2);

		String message = OpenRpcSchemaTest.describeDrift(encode(reference), encode(mapMethod(reference,
			"reference.createOrder",
			new OpenRpcMethod(createOrder.name(), shortened, createOrder.result(), createOrder.paramStructure()))));

		assertTrue(message, message.contains("reference.createOrder"));
		assertTrue(message, message.contains("[customer, quantity, express]"));
		assertTrue(message, message.contains("[customer, quantity]"));
		assertTrue(message, message.contains("first byte divergence at offset "));
	}

	@Test
	public void theDriftMessageNamesAParameterThatWasRenamed() {
		OpenRpcDocument reference = referenceDocument();
		OpenRpcMethod scalars = method(reference, "reference.scalars");
		List<OpenRpcContentDescriptor> renamed = new ArrayList<>(scalars.params());
		OpenRpcContentDescriptor was = renamed.get(3);
		renamed.set(3, new OpenRpcContentDescriptor("counter", was.schema()));

		String message = OpenRpcSchemaTest.describeDrift(encode(reference), encode(mapMethod(reference,
			"reference.scalars",
			new OpenRpcMethod(scalars.name(), renamed, scalars.result(), scalars.paramStructure()))));

		// a rename is the drift with no type consequence at all - the schema is identical on both sides,
		// so a diff comparing schemas only would report the two documents as structurally equal
		assertEquals("count", was.name());
		assertTrue(message, message.contains("reference.scalars"));
		assertTrue(message, message.contains("param[3] named \"count\" -> \"counter\""));
		assertFalse("the schema did not change and must not be reported as having", message.contains("schema {"));
	}

	@Test
	public void theDriftMessageWorksOnTheRealFrozenBytesAndNotOnlyOnAReEncodedModel() {
		// The mechanism exists for exactly one input: the committed file at
		// OpenRpcSchemaTest.REFERENCE_RESOURCE, edited or regenerated by accident. So it is exercised
		// here on those bytes, mutated as text - a member deleted and a value changed - rather than on a
		// document this test built and re-encoded, which is a different array with the same shape.
		String frozen = new String(readReference(), UTF_8);
		assertTrue("the frozen document must still carry the member this test removes",
			frozen.contains(",\"paramStructure\":\"by-position\""));
		assertTrue("the frozen document must still carry the version this test changes",
			frozen.contains("\"version\":\"1.0.0\""));

		String mutated = frozen
			.replaceFirst(",\"paramStructure\":\"by-position\"", "")
			.replace("\"version\":\"1.0.0\"", "\"version\":\"1.0.1\"");
		assertFalse("the mutation must actually change the bytes", frozen.equals(mutated));

		String message = OpenRpcSchemaTest.describeDrift(frozen.getBytes(UTF_8), mutated.getBytes(UTF_8));

		// the removed member, named as removed
		assertTrue(message, message.contains("paramStructure \"by-position\" -> (absent)"));
		// the changed value, named with both sides
		assertTrue(message, message.contains("info.version: \"1.0.0\" -> \"1.0.1\""));
		// and the byte-level half still fires, which is what survives when the structural half cannot run
		assertTrue(message, message.contains("first byte divergence at offset "));
		assertTrue(message, message.contains(OpenRpcSchemaTest.REFERENCE_RESOURCE));
	}

	// -------------------------------------------------------------------------------------------------

	private static JsonRpcServiceContract contractOf(Class<?> serviceType) {
		return JsonRpcServiceContract.of(serviceType, JsonCodecFactory.defaultInstance());
	}

	private static OpenRpcDocument generate(Class<?>... serviceTypes) {
		List<JsonRpcServiceContract> contracts = new ArrayList<>(serviceTypes.length);
		for (Class<?> serviceType : serviceTypes) contracts.add(contractOf(serviceType));
		return JsonRpcSchemaGenerator.generate(INFO, contracts);
	}

	private static String messageOf(Class<?>... serviceTypes) {
		try {
			generate(serviceTypes);
		} catch (IllegalArgumentException e) {
			return e.getMessage();
		}
		throw new AssertionError("expected a collision refusal for " + List.of(serviceTypes));
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

	private static OpenRpcDocument mapMethod(OpenRpcDocument document, String wireName, OpenRpcMethod replacement) {
		List<OpenRpcMethod> methods = document.methods().stream()
			.map(method -> method.name().equals(wireName) ? replacement : method)
			.toList();
		return new OpenRpcDocument(document.openrpc(), document.info(), methods);
	}

	private static OpenRpcDocument referenceDocument() {
		return JsonRpcSchemaGenerator.generate(OpenRpcSchemaTest.INFO,
			contractOf(ReferenceApi.class));
	}

	private static byte[] encode(OpenRpcDocument document) {
		return JsonUtils.toJsonBytes(OpenRpcDocument.CODEC, document);
	}

	private static byte[] readReference() {
		try (InputStream in = OpenRpcSchemaTest.class.getResourceAsStream(OpenRpcSchemaTest.REFERENCE_RESOURCE)) {
			assertNotNull("the frozen reference is not on the classpath", in);
			return in.readAllBytes();
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}
}
