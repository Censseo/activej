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
import io.activej.types.TypeT;
import org.junit.Test;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

/**
 * User story 2: <b>nothing that works today changes</b>. The other test classes in this feature ask
 * "does the new thing work"; this one asks "is the old thing still bit-for-bit what it was", which is
 * acceptance criterion number one.
 * <p>
 * Four separate claims live here, and they are separate on purpose:
 * <ol>
 *     <li><b>T024</b> — every type the factory resolved <i>before</i> this feature still emits the
 *     literal JSON captured in {@code specs/011-record-codec-derivation/baseline-capture.md}, which was
 *     taken before any {@code src/main} edit.</li>
 *     <li><b>T025</b> — a hand-registered codec for a {@code record} wins, and derivation does not run
 *     at all for that type. Observed with a counter, not merely inferred from matching output.</li>
 *     <li><b>T026</b> — a non-{@code record}, non-registered type still reaches the untouched
 *     {@code Object.class} fallback and its {@link UnsupportedOperationException}.</li>
 *     <li><b>T027</b> — one registration on {@code java.lang.Record} restores pre-feature behaviour
 *     wholesale; the feature is opt-out-able through the API that already existed.</li>
 *     <li><b>T071</b> — decode-time behaviour is unchanged: unknown key and absent required member
 *     both still raise {@link JsonValidationException}, for a hand-written
 *     {@link ObjectJsonCodec} <i>and</i> for a derived one.</li>
 * </ol>
 * Bare JUnit 4, no {@code @ClassRule}: {@code activej-test} is not on this module's classpath and
 * nothing here touches a {@code ByteBuf}, a {@code Promise} or a reactor.
 */
public class FactoryNonRegressionTest {

	// ================================================================ T024 — the captured baseline

	enum Color {RED, GREEN, BLUE}

	/**
	 * Resolves the type <b>fresh from the shipped {@code defaultInstance()}</b> — not from a rebuilt or
	 * test-local factory — and asserts the emitted JSON byte for byte, then the decode back. Byte
	 * identity is the point: a round trip alone would pass for a codec that changed the wire format
	 * consistently in both directions, and {@code cloud-lsmt-cube} and {@code crdt} persist this output.
	 */
	private static <T> void assertBaseline(String expectedJson, Class<T> type, T value) throws MalformedDataException {
		JsonCodec<T> codec = JsonCodecFactory.defaultInstance().resolve(type);
		assertEquals(expectedJson, JsonUtils.toJson(codec, value));
		assertEquals(value, JsonUtils.fromJson(codec, expectedJson));
	}

	private static <T> void assertBaseline(String expectedJson, TypeT<T> type, T value) throws MalformedDataException {
		JsonCodec<T> codec = JsonCodecFactory.defaultInstance().resolve(type);
		assertEquals(expectedJson, JsonUtils.toJson(codec, value));
		assertEquals(value, JsonUtils.fromJson(codec, expectedJson));
	}

	/** Both spellings of each of the eight scalars: the primitive class and its wrapper are separate registrations. */
	@Test
	public void primitivesAndWrappersEncodeExactlyAsBefore() throws MalformedDataException {
		assertBaseline("7", byte.class, (byte) 7);
		assertBaseline("7", Byte.class, (byte) 7);

		assertBaseline("7", short.class, (short) 7);
		assertBaseline("7", Short.class, (short) 7);

		assertBaseline("7", int.class, 7);
		assertBaseline("7", Integer.class, 7);

		assertBaseline("7", long.class, 7L);
		assertBaseline("7", Long.class, 7L);

		assertBaseline("7.5", float.class, 7.5f);
		assertBaseline("7.5", Float.class, 7.5f);

		assertBaseline("7.5", double.class, 7.5d);
		assertBaseline("7.5", Double.class, 7.5d);

		assertBaseline("true", boolean.class, true);
		assertBaseline("true", Boolean.class, true);

		assertBaseline("\"x\"", char.class, 'x');
		assertBaseline("\"x\"", Character.class, 'x');
	}

	@Test
	public void stringLocalDateAndEnumEncodeExactlyAsBefore() throws MalformedDataException {
		assertBaseline("\"abc\"", String.class, "abc");
		assertBaseline("\"2026-08-11\"", LocalDate.class, LocalDate.of(2026, 8, 11));
		assertBaseline("\"GREEN\"", Color.class, Color.GREEN);
	}

	@Test
	public void listEncodesExactlyAsBefore() throws MalformedDataException {
		assertBaseline("[1,2,3]", new TypeT<List<Integer>>() {}, List.of(1, 2, 3));
	}

	/**
	 * The input is a {@link LinkedHashMap}, not {@code Map.of("abc", 123, "def", 456)} as the baseline
	 * capture used, and the resulting literal is nevertheless the captured one byte for byte. The
	 * substitution is deliberate: {@code Map.of} with two or more entries returns an
	 * {@code ImmutableCollections.MapN} whose iteration <i>start index</i> is derived from a
	 * per-JVM-launch {@code SALT32L}, so its member order is stable within one {@code mvn test} run and
	 * not across runs. Asserting a literal against it would be a test that passes today and fails on
	 * some future launch for no reason anyone could attribute. An insertion-ordered map removes the
	 * randomness without weakening the assertion.
	 */
	@Test
	public void stringKeyedMapEncodesExactlyAsBefore() throws MalformedDataException {
		Map<String, Integer> map = new LinkedHashMap<>();
		map.put("abc", 123);
		map.put("def", 456);

		assertBaseline("{\"abc\":123,\"def\":456}", new TypeT<Map<String, Integer>>() {}, map);
	}

	/**
	 * The three {@code Number}-keyed maps of the baseline table. The single-entry cases are
	 * {@code Map.of}'s {@code Map1}, which holds exactly one entry and therefore has no order to
	 * randomise — they are the captured literals unchanged. The two-entry case is a
	 * {@link LinkedHashMap} for the reason given on {@link #stringKeyedMapEncodesExactlyAsBefore}, so
	 * its literal is insertion order rather than the salted {@code {"2":456,"1":123}} the capture
	 * happened to record.
	 */
	@Test
	public void numberKeyedMapsEncodeExactlyAsBefore() throws MalformedDataException {
		assertBaseline("{\"1\":123}", new TypeT<Map<Byte, Integer>>() {}, Map.of((byte) 1, 123));
		assertBaseline("{\"1\":123}", new TypeT<Map<Long, Integer>>() {}, Map.of(1L, 123));
		assertBaseline("{\"1\":123}", new TypeT<Map<Integer, Integer>>() {}, Map.of(1, 123));

		Map<Integer, Integer> ordered = new LinkedHashMap<>();
		ordered.put(1, 123);
		ordered.put(2, 456);
		assertBaseline("{\"1\":123,\"2\":456}", new TypeT<Map<Integer, Integer>>() {}, ordered);
	}

	/**
	 * The one row of the baseline table that cannot be asserted as a literal — {@code Map<Integer,
	 * Integer>} built by {@code Map.of(1, 123, 2, 456)}, captured as {@code {"2":456,"1":123}}. Value
	 * equality is asserted instead, which is what the row was really evidence for; the byte-identity
	 * evidence for this codec is the deterministic case above.
	 */
	@Test
	public void multiEntryNonStringKeyedMapRoundTripsThoughItsOrderIsJvmRandomised() throws MalformedDataException {
		JsonCodec<Map<Integer, Integer>> codec =
			JsonCodecFactory.defaultInstance().resolve(new TypeT<Map<Integer, Integer>>() {});

		Map<Integer, Integer> map = Map.of(1, 123, 2, 456);
		assertEquals(map, JsonUtils.fromJson(codec, JsonUtils.toJson(codec, map)));
	}

	// ================================================================ T025 — a hand-registered codec wins

	record Hand(String a, int b) {}

	/**
	 * The hand-written codec uses <b>upper-case keys</b>, which derivation could never produce (component
	 * names are keys verbatim, FR-038), so matching output would already be strong evidence. It is not
	 * relied on alone: the counter on the {@code String} mapping is what proves derivation did not
	 * <i>run</i>, as opposed to running and being discarded. Derivation resolves every component through
	 * {@code ctx.scan}, so a single component scan would move it; the hand-written codec composes
	 * {@code JsonCodecs.ofString()} directly and never scans.
	 */
	@Test
	public void handRegisteredRecordCodecWinsAndDerivationDoesNotRun() throws MalformedDataException {
		AtomicInteger componentScans = new AtomicInteger();

		JsonCodecFactory factory = JsonCodecFactory.defaultInstance().rebuild()
			.with(String.class, ctx -> {
				componentScans.incrementAndGet();
				return JsonCodecs.ofString();
			})
			.with(Hand.class, ctx -> JsonCodecs.ofObject(Hand::new,
				"A", Hand::a, JsonCodecs.ofString(),
				"B", Hand::b, JsonCodecs.ofInteger()))
			.build();

		JsonCodec<Hand> codec = factory.resolve(Hand.class);

		assertEquals(0, componentScans.get());
		assertEquals("{\"A\":\"x\",\"B\":2}", JsonUtils.toJson(codec, new Hand("x", 2)));
		assertEquals(new Hand("x", 2), JsonUtils.fromJson(codec, "{\"A\":\"x\",\"B\":2}"));

		// the counter is not vacuous: the same record derived through the shipped factory does scan its
		// String component, and produces a visibly different shape
		AtomicInteger derivedScans = new AtomicInteger();
		JsonCodecFactory deriving = JsonCodecFactory.defaultInstance().rebuild()
			.with(String.class, ctx -> {
				derivedScans.incrementAndGet();
				return JsonCodecs.ofString();
			})
			.build();

		JsonCodec<Hand> derived = deriving.resolve(Hand.class);
		assertEquals(1, derivedScans.get());
		assertEquals("{\"a\":\"x\",\"b\":2}", JsonUtils.toJson(derived, new Hand("x", 2)));
	}

	interface Marker {}

	record Marked(int n) implements Marker {}

	/**
	 * The distinction a consumer can get wrong, pinned rather than described: <b>only an exact-type
	 * registration beats derivation</b>. {@code TypeScannerRegistry.match} promotes a candidate only when
	 * the newcomer is assignable to the incumbent, and {@code Record} is registered inside
	 * {@code builder()} — therefore always ahead of anything a consumer adds through
	 * {@code rebuild().with(...)}. {@code Marked} is assignable to {@code Record}, so
	 * {@code .with(Marked.class, …)} promotes and wins; {@code Marker} is not assignable to
	 * {@code Record}, so {@code .with(Marker.class, …)} does not, and the record derives instead.
	 * <p>
	 * This is a <b>behaviour change</b> against the pre-feature factory, where a supertype registration
	 * was the only candidate and therefore won by default. No consumer in this repository registers a
	 * supertype of a record — {@code cloud-lsmt-cube}'s and {@code launchers/crdt}'s registrations are
	 * the only ones outside this module, and {@code CrdtData} is a {@code final class}, not a record —
	 * so nothing regresses here. It is recorded as an assertion so the compatibility statement has
	 * something executable behind it, and so a future change of the registration position is caught.
	 */
	@Test
	public void onlyAnExactTypeRegistrationBeatsDerivationNotAnInterfaceOne() {
		JsonCodec<Marked> handWritten = JsonCodecs.ofObject(Marked::new, "N", Marked::n, JsonCodecs.ofInteger());

		JsonCodecFactory viaInterface = JsonCodecFactory.defaultInstance().rebuild()
			.with(Marker.class, ctx -> handWritten)
			.build();
		assertEquals("{\"n\":1}", JsonUtils.toJson(viaInterface.resolve(Marked.class), new Marked(1)));

		// the same codec registered on the record's own class does win
		JsonCodecFactory viaExactType = viaInterface.rebuild()
			.with(Marked.class, ctx -> handWritten)
			.build();
		assertEquals("{\"N\":1}", JsonUtils.toJson(viaExactType.resolve(Marked.class), new Marked(1)));

		// and the interface registration is not dead — a non-record implementor still reaches it
		assertEquals("{\"N\":1}", JsonUtils.toJson(viaInterface.resolve(Marker.class), new Marked(1)));
	}

	// ================================================================ T026 — the Object fallback is untouched

	interface NotARecord {
		int n();
	}

	static class PlainPojo {
		private final int n;

		PlainPojo(int n) {this.n = n;}

		public int getN() {return n;}
	}

	abstract static class PlainAbstract {
		abstract int n();
	}

	/**
	 * The registration this feature added is {@code .with(Record.class, …)} <b>in addition to</b>
	 * {@code .with(Object.class, …)}, never in place of it (FR-002). Anything that is not a
	 * {@code record} and is not registered therefore still falls all the way through to the original
	 * mapping and its bare {@link UnsupportedOperationException} — including a POJO shaped exactly like a
	 * record, which is the case a getter-based derivation would have swallowed (FR-036 says there is no
	 * such derivation).
	 */
	@Test
	public void nonRecordUnregisteredTypesStillThrowFromTheObjectFallback() {
		JsonCodecFactory factory = JsonCodecFactory.defaultInstance();

		assertThrows(UnsupportedOperationException.class, () -> factory.resolve(NotARecord.class));
		assertThrows(UnsupportedOperationException.class, () -> factory.resolve(PlainPojo.class));
		assertThrows(UnsupportedOperationException.class, () -> factory.resolve(PlainAbstract.class));
		assertThrows(UnsupportedOperationException.class, () -> factory.resolve(Object.class));

		// also as a type argument, where the fallback is reached through a scan rather than at the root
		assertThrows(UnsupportedOperationException.class,
			() -> factory.resolve(new TypeT<List<PlainPojo>>() {}));
	}

	// ================================================================ T027 — the one-call opt-out

	record OptedOut(String a, List<Hand> hands) {}

	/**
	 * SC-010 / FR-039: a consumer who does not want derivation gets pre-feature behaviour back with
	 * <b>one</b> {@code Builder.with} call, through the API that already existed — no flag was added to
	 * the builder and none is needed. Two entries whose types are {@code equals} resolve to the later
	 * one, so the registration below shadows {@code builder()}'s.
	 * <p>
	 * "Pre-feature behaviour" is asserted in both directions: every record now fails again, at the root
	 * and nested, and everything that resolved before this feature still resolves on the very same
	 * factory.
	 */
	@Test
	public void oneRegistrationOnJavaLangRecordRestoresPreFeatureBehaviour() throws MalformedDataException {
		JsonCodecFactory noDerivation = JsonCodecFactory.defaultInstance().rebuild()
			.with(Record.class, ctx -> {throw new UnsupportedOperationException();})
			.build();

		assertThrows(UnsupportedOperationException.class, () -> noDerivation.resolve(Hand.class));
		assertThrows(UnsupportedOperationException.class, () -> noDerivation.resolve(Marked.class));
		assertThrows(UnsupportedOperationException.class, () -> noDerivation.resolve(OptedOut.class));
		assertThrows(UnsupportedOperationException.class, () -> noDerivation.resolve(new TypeT<List<Hand>>() {}));

		// everything the factory resolved before the feature still resolves, on this same instance
		assertEquals("\"abc\"", JsonUtils.toJson(noDerivation.resolve(String.class), "abc"));
		assertEquals("7", JsonUtils.toJson(noDerivation.resolve(int.class), 7));
		assertEquals("\"GREEN\"", JsonUtils.toJson(noDerivation.resolve(Color.class), Color.GREEN));
		assertEquals("[1,2,3]",
			JsonUtils.toJson(noDerivation.resolve(new TypeT<List<Integer>>() {}), List.of(1, 2, 3)));

		// and a hand-written record codec still works on top of the opt-out, exactly as before the feature
		JsonCodecFactory handWrittenOnly = noDerivation.rebuild()
			.with(Hand.class, ctx -> JsonCodecs.ofObject(Hand::new,
				"a", Hand::a, JsonCodecs.ofString(),
				"b", Hand::b, JsonCodecs.ofInteger()))
			.build();

		JsonCodec<Hand> codec = handWrittenOnly.resolve(Hand.class);
		assertEquals("{\"a\":\"x\",\"b\":2}", JsonUtils.toJson(codec, new Hand("x", 2)));
		assertEquals(new Hand("x", 2), JsonUtils.fromJson(codec, "{\"a\":\"x\",\"b\":2}"));

		// the shipped instance is unaffected by any of the above — rebuild() copies, never mutates
		assertEquals("{\"a\":\"x\",\"b\":2}",
			JsonUtils.toJson(JsonCodecFactory.defaultInstance().resolve(Hand.class), new Hand("x", 2)));
	}

	// ================================================================ T071 — decode-time behaviour is unchanged

	record Pinned(String a, int b) {}

	/**
	 * The hand-written reference codec: the <b>same</b> {@code ObjectJsonCodec.BuilderArray} assembly
	 * derivation fills, with the same keys, composed by hand. That is what makes the pair of assertions
	 * below a comparison rather than two independent claims.
	 */
	private static JsonCodec<Pinned> handWrittenPinnedCodec() {
		return JsonCodecs.ofObject(Pinned::new,
			"a", Pinned::a, JsonCodecs.ofString(),
			"b", Pinned::b, JsonCodecs.ofInteger());
	}

	private static JsonValidationException assertDecodeRejected(JsonCodec<Pinned> codec, String json) {
		MalformedDataException e = assertThrows(MalformedDataException.class, () -> JsonUtils.fromJson(codec, json));
		// JsonUtils wraps JsonValidationException into MalformedDataException at the boundary; the
		// validation exception itself is the contract, and it is the cause
		Throwable cause = e.getCause();
		assertTrue("expected a JsonValidationException cause, got " + cause, cause instanceof JsonValidationException);
		return (JsonValidationException) cause;
	}

	/**
	 * FR-021, half one: an unknown key is <b>rejected</b>, not tolerated and not silently skipped. The
	 * message is {@code ObjectJsonCodec.decoder}'s and is asserted verbatim, because "raises something"
	 * would also be satisfied by a parse error from three members later.
	 * <p>
	 * The derived codec is asserted to do the identical thing. Derivation inherits this by construction —
	 * it fills the same assembly and overrides none of the six hooks — but "by construction" is an
	 * architectural claim, and this is the executable one.
	 */
	@Test
	public void unknownKeyStillRaisesKeyNotFound() {
		String json = "{\"a\":\"x\",\"b\":2,\"zzz\":9}";

		assertEquals("Key not found: zzz", assertDecodeRejected(handWrittenPinnedCodec(), json).getMessage());

		JsonCodec<Pinned> derived = JsonCodecFactory.defaultInstance().resolve(Pinned.class);
		assertEquals("Key not found: zzz", assertDecodeRejected(derived, json).getMessage());
	}

	/** The unknown key is rejected wherever it sits, including first and last. */
	@Test
	public void unknownKeyIsRejectedInAnyPosition() {
		JsonCodec<Pinned> derived = JsonCodecFactory.defaultInstance().resolve(Pinned.class);

		for (String json : List.of(
			"{\"zzz\":9,\"a\":\"x\",\"b\":2}",
			"{\"a\":\"x\",\"zzz\":9,\"b\":2}",
			"{\"a\":\"x\",\"b\":2,\"zzz\":9}")
		) {
			assertEquals(json, "Key not found: zzz", assertDecodeRejected(handWrittenPinnedCodec(), json).getMessage());
			assertEquals(json, "Key not found: zzz", assertDecodeRejected(derived, json).getMessage());
		}
	}

	/**
	 * FR-021, half two: a required member absent from the payload is a {@link JsonValidationException},
	 * raised by {@code BuilderArray}'s {@code NO_DEFAULT_VALUE} check at construction. Only the type is
	 * asserted — that exception is raised with no message today, and pinning the absence of a message
	 * would pin something the contract does not state.
	 */
	@Test
	public void absentRequiredMemberStillRaisesJsonValidationException() {
		JsonCodec<Pinned> derived = JsonCodecFactory.defaultInstance().resolve(Pinned.class);

		for (String json : List.of("{}", "{\"a\":\"x\"}", "{\"b\":2}")) {
			assertDecodeRejected(handWrittenPinnedCodec(), json);
			assertDecodeRejected(derived, json);
		}
	}

	/** The corroborating positive: the very same two codecs accept a well-formed payload, in any member order. */
	@Test
	public void aWellFormedPayloadIsStillAccepted() throws MalformedDataException {
		JsonCodec<Pinned> derived = JsonCodecFactory.defaultInstance().resolve(Pinned.class);

		assertEquals(new Pinned("x", 2), JsonUtils.fromJson(handWrittenPinnedCodec(), "{\"a\":\"x\",\"b\":2}"));
		assertEquals(new Pinned("x", 2), JsonUtils.fromJson(derived, "{\"a\":\"x\",\"b\":2}"));
		assertEquals(new Pinned("x", 2), JsonUtils.fromJson(derived, "{\"b\":2,\"a\":\"x\"}"));
	}
}
