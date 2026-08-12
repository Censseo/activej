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

package io.activej.json;

import io.activej.common.exception.MalformedDataException;
import io.activej.json.annotations.JsonSubclasses;
import io.activej.types.TypeT;
import org.junit.Test;
import org.junit.function.ThrowingRunnable;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.*;

/**
 * What a consumer is told when a {@code record} cannot be derived, and <b>when</b> they are told it.
 *
 * <p>Three facts frame every method here.
 *
 * <p><b>Every failure below is raised at {@code resolve(...)}, never on first use.</b> That is
 * structural rather than asserted: every component is scanned eagerly inside the derivation loop,
 * before assembly, so {@code resolve} does not return a codec at all. Asserting the negative
 * directly is impossible — there is no codec to use later. What is asserted instead is the
 * corollary that keeps it true: a sibling resolution on the same factory is unaffected, so a future
 * refactor that made components lazy would have to break that too.
 *
 * <p><b>No payload exists at derivation time</b>, which is why the security rule of
 * {@code noDerivationMessageCarriesAValue} is a statement about vocabulary rather than about
 * redaction — see that method's own Javadoc for the scope, and for the two things it deliberately
 * does not cover.
 *
 * <p><b>Messages are asserted element by element with {@code contains}, never by equality</b>, with
 * exactly one exception: the path token {@code Order.lines[].product.registeredAt} is a published
 * format (contracts/record-derivation-api.md §6) and is asserted verbatim as a substring. Everything
 * else — wording, order, punctuation — is free to be tuned without editing nine tests.
 *
 * <p>Bare JUnit 4, no {@code @ClassRule}: {@code activej-test} is not on this module's classpath and
 * nothing here touches a {@code ByteBuf}, a {@code Promise} or a reactor. Resolution is always driven
 * through the shipped {@link JsonCodecFactory#defaultInstance()} — the way a consumer reaches it —
 * and never through {@code RecordJsonCodecTest}'s test-local harness, which re-roots the derivation
 * path at every record and would make a path assertion an assertion about the harness.
 */
public class RecordDerivationFailureTest {

	// ---------------------------------------------------------------- fixtures, declared once

	/**
	 * {@code ZonedDateTime} is the offending type of choice throughout: FR-028 deliberately leaves it
	 * out of the resolvable set, and {@code JsonCodecFactoryTypesTest.zonedDateTimeIsStillUnresolved}
	 * pins that. If a later feature registers it, that test goes red first and the intent of this file
	 * stays legible.
	 */
	record Registered(long id, ZonedDateTime at) {}

	record Product(ZonedDateTime registeredAt) {}

	record Line(Product product) {}

	record Order(long id, List<Line> lines) {}

	/**
	 * The component's type is {@code java.lang.Record} itself, which <i>is</i> assignable to the
	 * {@code Record.class} registry entry — so the component scan enters derivation and fails in the
	 * reflective descriptor, where {@code getRecordComponents()} returns {@code null}.
	 */
	record Holder(Record any) {}

	/** Named {@code RawPair} on purpose: {@code Pair} already exists in two sibling test classes. */
	record RawPair<A, B>(A first, B second) {}

	record WithBytes(byte[] payload) {}

	record WithStrings(String[] tags) {}

	record Keyed(Map<LocalDate, String> byDay) {}

	enum Colour {RED, GREEN}

	/** Shaped exactly like {@code RecordJsonCodecTest.Opaque}: the same scenario, one level up. */
	static final class ThirdParty {
		private final String text;

		ThirdParty(String text) {this.text = text;}

		String text() {return text;}

		@Override
		public boolean equals(Object o) {return o instanceof ThirdParty other && text.equals(other.text);}

		@Override
		public int hashCode() {return text.hashCode();}
	}

	record Root(ThirdParty tp) {}

	/** A POJO shaped like a record, and not a record — the type the {@code Object} fallback owns. */
	static final class PlainPojo {
		private final String a;

		PlainPojo(String a) {this.a = a;}

		String a() {return a;}
	}

	record WithPojo(PlainPojo p) {}

	private static final String SENTINEL = "s3cr3t-tag";

	/** Carries consumer-supplied text inside an annotation on an unresolvable component. */
	record Tagged(@JsonSubclasses(value = {PlainPojo.class}, tags = {SENTINEL}) Object x) {}

	/**
	 * Every failure this file produces, in one place, so that a tenth failure path added later is
	 * covered by {@code noDerivationMessageCarriesAValue} by construction.
	 */
	private static final List<ThrowingRunnable> ALL_FAILURES = List.of(
		() -> JsonCodecFactory.defaultInstance().resolve(Registered.class),
		() -> JsonCodecFactory.defaultInstance().resolve(Order.class),
		() -> JsonCodecFactory.defaultInstance().resolve(Holder.class),
		() -> JsonCodecFactory.defaultInstance().resolve(RawPair.class),
		() -> JsonCodecFactory.defaultInstance().resolve(WithBytes.class),
		() -> JsonCodecFactory.defaultInstance().resolve(Keyed.class),
		() -> JsonCodecFactory.defaultInstance().resolve(WithPojo.class),
		() -> JsonCodecFactory.defaultInstance().resolve(Root.class),
		() -> JsonCodecFactory.defaultInstance().resolve(Tagged.class));

	// ================================================================ T045

	/**
	 * FR-018's four elements — path, declaring record, component, offending type — asserted
	 * individually so the wording between them stays free.
	 */
	@Test
	public void anUnresolvableComponentFailsAtResolveTime() {
		JsonCodecFactory factory = JsonCodecFactory.defaultInstance();

		IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
			() -> factory.resolve(Registered.class));

		assertNames(e,
			"Registered.at",                    // the path from the root
			Registered.class.getName(),         // the record that declares the component
			"at",                               // the component
			ZonedDateTime.class.getName());     // the offending type
		assertTrue(String.valueOf(e.getCause()), e.getCause() instanceof UnsupportedOperationException);

		// "not on first use" is structural - resolve never returns, so there is no codec to use later.
		// What can be asserted is that nothing else on the factory was disturbed: a sibling record with
		// the same offending component fails on its own, and an unrelated type still resolves.
		assertThrows(IllegalArgumentException.class, () -> factory.resolve(Product.class));
		assertEquals("\"x\"", JsonUtils.toJson(factory.resolve(String.class), "x"));
	}

	// ================================================================ T046

	/**
	 * The single most load-bearing assertion of the phase: the only one that can tell real path
	 * <i>accumulation</i> from a message that happens to name the failing record.
	 *
	 * <p>The chain is three levels deep <b>with a collection in the middle</b>, because the collection
	 * step is the part no other test can produce. It renders {@code lines[]} because the enclosing
	 * record decorates its own component step — the {@code List} mapping lives in
	 * {@code JsonCodecFactory} and knows nothing about derivation or paths.
	 */
	@Test
	public void aFailureThreeLevelsDownRendersTheWholePath() {
		JsonCodecFactory factory = JsonCodecFactory.defaultInstance();

		IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
			() -> factory.resolve(Order.class));

		// the published path format - the one assertion in this file that pins a token verbatim
		assertNames(e, "Order.lines[].product.registeredAt");
		assertNames(e, Product.class.getName(), "registeredAt", ZonedDateTime.class.getName());

		// the path segments are readable simple names; the declaring record is named fully qualified.
		// Both facts matter, and only this assertion separates them.
		assertFalse(e.getMessage(), e.getMessage().contains("RecordDerivationFailureTest$Order."));

		// the one-level control: a bug that always prefixes the outermost class name fails here
		IllegalArgumentException direct = assertThrows(IllegalArgumentException.class,
			() -> factory.resolve(Product.class));
		assertNames(direct, "Product.registeredAt");
		assertFalse(direct.getMessage(), direct.getMessage().contains("Order"));
	}

	// ================================================================ T047

	/**
	 * The recovery column of the spec's Error Scenarios table, made executable: a message naming a
	 * recovery nobody has run is a claim, not a contract.
	 *
	 * <p>Only the first beat is new behaviour; registering the named type and round-tripping through it
	 * already worked, and a partially-green run before the implementation lands is expected.
	 */
	@Test
	public void registeringTheNamedTypeMakesTheGraphResolve() throws MalformedDataException {
		// 1. it fails, and the message names the type to register
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
			() -> JsonCodecFactory.defaultInstance().resolve(Root.class));
		assertNames(e, ThirdParty.class.getName(), "Root.tp");

		// 2. the exact recovery the message prescribes
		JsonCodecFactory repaired = JsonCodecFactory.defaultInstance().rebuild()
			.with(ThirdParty.class,
				ctx -> JsonCodecs.transform(JsonCodecs.ofString(), ThirdParty::text, ThirdParty::new))
			.build();

		// 3. it resolves, and the registered codec is the one used - a string, not an object shape
		JsonCodec<Root> codec = repaired.resolve(Root.class);
		assertEquals("{\"tp\":\"x\"}", JsonUtils.toJson(codec, new Root(new ThirdParty("x"))));
		assertEquals(new Root(new ThirdParty("x")), JsonUtils.fromJson(codec, "{\"tp\":\"x\"}"));

		// 4. the repair did not leak sideways: a failed derivation publishes nothing, and rebuild()
		// hands the new factory its own memo
		assertThrows(IllegalArgumentException.class, () -> JsonCodecFactory.defaultInstance().resolve(Root.class));
	}

	// ================================================================ T048

	/**
	 * FR-019's mechanism — a reflection-layer failure located by path, attributed to a record and a
	 * member, keeping its cause — exercised through the one such failure that is deterministically
	 * reachable on this classpath.
	 *
	 * <p><b>The literal "inaccessible member" scenario is not provokable here, and this test does not
	 * claim it.</b> {@code setAccessible(true)} on an unnamed-module record always succeeds — this
	 * repository contains no {@code module-info.java} — and the {@code SecurityException} arm is dead
	 * on this baseline, {@code SecurityManager} having been permanently disabled from JDK 24. Both
	 * {@code setAccessible} sites in {@code RecordJsonCodec.describe} already wrap
	 * {@code InaccessibleObjectException | SecurityException} into an {@code IllegalArgumentException}
	 * naming the record and, for an accessor, the component; the path prefix is added around the whole
	 * descriptor call rather than by touching those catches. Neither arm is reproduced by any test in
	 * this repository, and that gap is recorded rather than faked.
	 */
	@Test
	public void aDescribeLayerFailureIsReportedWithRecordMemberAndCause() {
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
			() -> JsonCodecFactory.defaultInstance().resolve(Holder.class));

		assertNames(e, "Holder.any", Holder.class.getName(), "any", "Record");
		assertNotNull(e.getCause());
		assertTrue(String.valueOf(e.getCause().getMessage()),
			e.getCause().getMessage().contains("Not a record: java.lang.Record"));
	}

	// ================================================================ T049

	/**
	 * A raw resolution leaves every variable unbound. The descriptor still succeeds — a raw record has
	 * a canonical constructor, found by the <i>erased</i> component types — so the failure is at
	 * component 0, in {@code AnnotatedTypes.bind}, and its message is what names the variable.
	 *
	 * <p>{@code "Type not found: A"} is asserted whole; the bare {@code "A"} would match every message
	 * ever written and pin nothing.
	 */
	@Test
	public void aRawGenericRecordNamesTheUnboundTypeVariable() throws MalformedDataException {
		JsonCodecFactory factory = JsonCodecFactory.defaultInstance();

		IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
			() -> factory.resolve(RawPair.class));

		assertNames(e, "RawPair.first", RawPair.class.getName(), "first");
		assertNames(e, "Type not found: A");
		assertTrue(String.valueOf(e.getCause()), e.getCause() instanceof IllegalArgumentException);

		// the positive control: a regression that refused ALL generic records would pass without it
		JsonCodec<RawPair<String, Integer>> bound = factory.resolve(new TypeT<RawPair<String, Integer>>() {});
		assertEquals("{\"first\":\"x\",\"second\":2}", JsonUtils.toJson(bound, new RawPair<>("x", 2)));
		assertEquals(new RawPair<>("x", 2), JsonUtils.fromJson(bound, "{\"first\":\"x\",\"second\":2}"));
	}

	// ================================================================ T050

	/**
	 * FR-027 keeps primitive arrays out of scope, and this is where a consumer is told so by name.
	 *
	 * <p>Two boundaries are asserted alongside, and they are what keep the implementation honest.
	 * The classification must sit on the <b>failure</b> path and never as a pre-scan gate: a
	 * consumer who registers a codec for {@code byte[]} must still resolve (FR-009), which the last
	 * beat pins. And the untouched {@code Object.class} fallback must keep raising
	 * {@code UnsupportedOperationException} at the top level (SC-015), which the second beat pins.
	 *
	 * <p>Recorded limit: {@code byte[][]} is a reference array whose <i>element</i> is a primitive
	 * array, so it fails one level deeper with the generic wording. The classification looks at the
	 * component it holds, not at the whole subtree.
	 */
	@Test
	public void aPrimitiveArrayComponentSaysPrimitiveArraysAreOutOfScope() throws MalformedDataException {
		JsonCodecFactory factory = JsonCodecFactory.defaultInstance();

		IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
			() -> factory.resolve(WithBytes.class));
		assertNames(e, "WithBytes.payload", WithBytes.class.getName(), "payload", "byte[]");
		assertTrue(e.getMessage(), e.getMessage().toLowerCase().contains("primitive array"));
		assertTrue(String.valueOf(e.getCause()), e.getCause() instanceof UnsupportedOperationException);

		// the boundary: at the TOP level the untouched fallback still owns this, message-less
		assertThrows(UnsupportedOperationException.class, () -> factory.resolve(int[].class));

		// the refusal is about PRIMITIVE arrays specifically, which is the sentence the message claims
		JsonCodec<WithStrings> strings = factory.resolve(WithStrings.class);
		assertEquals("{\"tags\":[\"a\",\"b\"]}",
			JsonUtils.toJson(strings, new WithStrings(new String[]{"a", "b"})));
		assertArrayEquals(new String[]{"a", "b"},
			JsonUtils.fromJson(strings, "{\"tags\":[\"a\",\"b\"]}").tags());

		// FR-009: a registered byte[] codec resolves, so the classification cannot be a whitelist gate
		JsonCodecFactory withBase64 = factory.rebuild()
			.with(byte[].class, ctx -> JsonCodecs.transform(JsonCodecs.ofString(),
				bytes -> new String((byte[]) bytes, UTF_8), s -> s.getBytes(UTF_8)))
			.build();
		JsonCodec<WithBytes> registered = withBase64.resolve(WithBytes.class);
		assertEquals("{\"payload\":\"hi\"}", JsonUtils.toJson(registered, new WithBytes("hi".getBytes(UTF_8))));
		assertArrayEquals("hi".getBytes(UTF_8),
			JsonUtils.fromJson(registered, "{\"payload\":\"hi\"}").payload());
	}

	// ================================================================ T051

	/**
	 * FR-020 over all three of the map mapping's unnamed failures, not just the one {@code tasks.md}
	 * names: the {@code "TODO"} throw itself, the same throw reached from inside a record, and
	 * {@code JsonKeyCodec.ofNumberKey}'s message-less instance-initialiser refusal — which became
	 * reachable when {@code BigDecimal} was registered as a value, since
	 * {@code Number.isAssignableFrom(BigDecimal.class)} routes it into the number-key branch.
	 *
	 * <p>Case (d) additionally pins that a non-{@code Class} key type reaches the named refusal rather
	 * than a raw {@code ClassCastException} out of the mapping's cast.
	 */
	@Test
	public void anUnsupportedMapKeyNamesTheMapTypeAndTheKeyType() throws MalformedDataException {
		JsonCodecFactory factory = JsonCodecFactory.defaultInstance();

		// (a) top level - no record, no path
		IllegalArgumentException top = assertThrows(IllegalArgumentException.class,
			() -> factory.resolve(new TypeT<Map<LocalDate, String>>() {}));
		assertNames(top, "Map", LocalDate.class.getName());
		assertFalse(top.getMessage(), top.getMessage().contains("TODO"));

		// (b) inside a record - the same message, now located
		IllegalArgumentException nested = assertThrows(IllegalArgumentException.class,
			() -> factory.resolve(Keyed.class));
		assertNames(nested, "Keyed.byDay", LocalDate.class.getName());
		assertFalse(nested.getMessage(), nested.getMessage().contains("TODO"));

		// (c) a Number subclass the number-key codec does not handle
		IllegalArgumentException big = assertThrows(IllegalArgumentException.class,
			() -> factory.resolve(new TypeT<Map<BigDecimal, String>>() {}));
		assertNotNull(big.getMessage());
		assertFalse(big.getMessage().isEmpty());
		assertNames(big, BigDecimal.class.getName());

		// (d) a key type that is not a Class at all
		IllegalArgumentException parameterized = assertThrows(IllegalArgumentException.class,
			() -> factory.resolve(new TypeT<Map<List<String>, String>>() {}));
		assertNames(parameterized, List.class.getName());

		// the positive controls, so the test cannot pass by refusing everything
		JsonCodec<Map<Colour, String>> byColour = factory.resolve(new TypeT<Map<Colour, String>>() {});
		assertEquals("{\"RED\":\"r\"}", JsonUtils.toJson(byColour, Map.of(Colour.RED, "r")));
		JsonCodec<Map<UUID, String>> byUuid = factory.resolve(new TypeT<Map<UUID, String>>() {});
		UUID uuid = UUID.fromString("00000000-0000-0000-0000-000000000001");
		assertEquals(Map.of(uuid, "u"),
			JsonUtils.fromJson(byUuid, "{\"00000000-0000-0000-0000-000000000001\":\"u\"}"));
	}

	// ================================================================ T052

	/**
	 * The invariant this whole phase is at risk of inverting, in one place: <b>"this type is not
	 * registered" and "deriving a record failed" are different facts and keep different exception
	 * types.</b> The untouched {@code Object.class} fallback keeps raising
	 * {@code UnsupportedOperationException}; only a failure reached <i>while deriving a record</i> is
	 * re-raised as {@code IllegalArgumentException}.
	 *
	 * <p>The compatibility statement that goes with it: a consumer catching
	 * {@code UnsupportedOperationException} around {@code resolve} of a <i>record</i> does see a
	 * behaviour change. It is deliberate, and it costs nothing, because the original survives as
	 * {@code getCause()} — the fourth assertion — and because one {@code Builder.with} call restores
	 * the previous behaviour end to end, which the fifth asserts.
	 *
	 * <p>{@code RecordJsonCodecTest.failedDerivationLeavesNoEntry} carries the other half of this one
	 * decision: it asserted the old type for a record with an unresolvable component and was updated
	 * with the implementation, not here.
	 */
	@Test
	public void notRegisteredAndDerivationFailedStayDistinguishable() {
		JsonCodecFactory factory = JsonCodecFactory.defaultInstance();

		// not registered, at the root - the untouched Object.class fallback
		assertThrows(UnsupportedOperationException.class, () -> factory.resolve(PlainPojo.class));

		// not registered, reached through a scan with NO record in the chain - still the fallback
		assertThrows(UnsupportedOperationException.class,
			() -> factory.resolve(new TypeT<List<PlainPojo>>() {}));

		// the same type, now inside a record - derivation failed, so IllegalArgumentException
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
			() -> factory.resolve(WithPojo.class));

		// and nothing was lost: the original signal is the cause
		assertTrue(String.valueOf(e.getCause()), e.getCause() instanceof UnsupportedOperationException);

		// one call restores pre-feature behaviour end to end
		JsonCodecFactory noDerivation = factory.rebuild()
			.with(Record.class, ctx -> {throw new UnsupportedOperationException();})
			.build();
		assertThrows(UnsupportedOperationException.class, () -> noDerivation.resolve(WithPojo.class));
	}

	// ================================================================ T053

	/**
	 * No derivation message carries a decoded value — only types, component names and paths.
	 *
	 * <p><b>Scope, stated because it is the deliverable of this method as much as the code is.</b> The
	 * rule is about <i>derivation-time</i> messages and it is structurally true: derivation runs at
	 * {@code resolve(...)}, before any payload exists, so there is no value in scope to leak. What is
	 * being checked is therefore not "did we remember to redact" but "is the message built from schema
	 * vocabulary only". Two things the rule does <b>not</b> cover:
	 * <ul>
	 *   <li><b>Decode-time messages are a separate contract and deliberately do carry the offending
	 *   token.</b> FR-020 requires the key codecs to name the offending key; {@code ofEnumKey} and
	 *   {@code ofUuidKey} do, and {@code JsonCodecFactoryTypesTest.unknownEnumKeyIsRejected} asserts
	 *   it. A map key is a structural token — the JSON member name — not a business value.</li>
	 *   <li>JPMS and {@code SecurityException} diagnostics, which are unreachable on this classpath.</li>
	 * </ul>
	 *
	 * <p>{@code SENTINEL} guards the one genuinely reachable leak: a message built from
	 * {@code componentType.toString()} rather than {@code componentType.getType().getTypeName()}
	 * renders the annotations too, and an annotation can carry consumer-supplied text. It is not a
	 * decoded value — it is the closest reachable proxy, and it pins a real sloppiness.
	 *
	 * <p>The {@code "} check is deliberately blunt: no derivation message has any reason to quote
	 * anything, which is why component names are wrapped in {@code 'single quotes'}. A future message
	 * that wants double quotes is a decision to be taken here, explicitly.
	 */
	@Test
	public void noDerivationMessageCarriesAValue() {
		for (ThrowingRunnable failing : ALL_FAILURES) {
			IllegalArgumentException e = assertThrows(IllegalArgumentException.class, failing);

			// the positive complement: the test must not be satisfiable by an empty message
			assertNotNull(e.getMessage());
			assertFalse(e.getMessage().isEmpty());
			assertTrue(e.getMessage(), e.getMessage().contains("."));

			for (Throwable t = e; t != null; t = t.getCause()) {
				String m = t.getMessage();
				if (m == null) continue;
				assertFalse(m, m.contains("\""));                             // no JSON fragment
				assertFalse(m, m.contains(SENTINEL));                         // no annotation-borne text
				assertFalse(m, m.contains("@io.activej.json.annotations"));   // no annotation rendering
			}
		}
	}

	// ---------------------------------------------------------------- helpers

	/** One spelling of the FR-018 element check, so eight methods cannot drift into eight of them. */
	private static void assertNames(IllegalArgumentException e, String... required) {
		for (String s : required) {
			assertTrue(s + " not in: " + e.getMessage(), e.getMessage().contains(s));
		}
	}
}
