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

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.*;
import java.util.*;

import static org.junit.Assert.*;

/**
 * User story 3: <b>the common types a realistic model actually uses</b> — every type this feature
 * newly makes reachable from the shipped {@link JsonCodecFactory#defaultInstance()}, asserted on its
 * <i>exact JSON</i> and not merely on a round trip.
 * <p>
 * The exact-JSON assertions are deliberate (SC-008). A round trip passes for any self-consistent
 * encoding — a {@code UUID} written as two longs, a {@code BigDecimal} written as a number, an
 * {@code Instant} written as epoch millis all round-trip perfectly and are all wrong. The wire form
 * of a resolved codec is a consumer-visible commitment, so the bytes are the assertion.
 * <p>
 * <b>Never {@code Set.of} / {@code Map.of} for a literal-JSON assertion.</b> Their iteration order is
 * {@code SALT32L}-randomised per JVM launch for two or more entries, for <i>every</i> element type,
 * {@code String} included (measured in this feature's T024 result). Every multi-element fixture below
 * is a {@code LinkedHashSet} or a {@code LinkedHashMap}.
 * <p>
 * Bare JUnit 4, no {@code @ClassRule}: {@code activej-test} is not on this module's classpath and
 * nothing here touches a {@code ByteBuf}, a {@code Promise} or a reactor (FR-046).
 */
public class JsonCodecFactoryTypesTest {

	// ---------------------------------------------------------------- shared fixtures

	private static final UUID UUID_VALUE = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");

	record Tag(String name) {}

	record Line(String sku, int qty) {}

	enum Colour {RED, GREEN}

	// ================================================================ T028 — Set

	/**
	 * FR-043: the encoded element order is the {@code Set}'s own iteration order, <b>not</b> a sorted
	 * one. The literal is the assertion; a codec that sorted would still round-trip.
	 */
	@Test
	public void setOfStringRoundTripsInIterationOrder() throws MalformedDataException {
		JsonCodec<Set<String>> codec = JsonCodecFactory.defaultInstance().resolve(new TypeT<Set<String>>() {});
		Set<String> value = new LinkedHashSet<>(List.of("b", "a", "c"));
		assertJson("[\"b\",\"a\",\"c\"]", codec, value);
		assertEquals(value, decode(codec, "[\"b\",\"a\",\"c\"]"));
	}

	/**
	 * The decoded {@code Set} preserves the payload's order. The <i>order</i> is the contract; the
	 * concrete {@code LinkedHashSet} accumulator behind it is an implementation detail and is
	 * deliberately not asserted.
	 */
	@Test
	public void setDecodesIntoAnOrderPreservingSet() throws MalformedDataException {
		JsonCodec<Set<String>> codec = JsonCodecFactory.defaultInstance().resolve(new TypeT<Set<String>>() {});
		assertEquals(List.of("b", "a", "c"), List.copyOf(decode(codec, "[\"b\",\"a\",\"c\"]")));
	}

	@Test
	public void emptySetRoundTrips() throws MalformedDataException {
		JsonCodec<Set<String>> codec = JsonCodecFactory.defaultInstance().resolve(new TypeT<Set<String>>() {});
		assertJson("[]", codec, new LinkedHashSet<>());
		assertTrue(decode(codec, "[]").isEmpty());
	}

	/**
	 * {@code JsonCodecs.ofSet} rejects a duplicate element with a message-less
	 * {@link JsonValidationException} — so the <i>type</i> is asserted and nothing else. Pinning the
	 * absence of a message would pin something the contract does not state.
	 */
	@Test
	public void duplicateElementIsRejected() {
		JsonCodec<Set<String>> codec = JsonCodecFactory.defaultInstance().resolve(new TypeT<Set<String>>() {});
		MalformedDataException e = assertThrows(MalformedDataException.class, () -> decode(codec, "[\"a\",\"a\"]"));
		assertTrue(e.getCause() instanceof JsonValidationException);
	}

	/** The registration has to compose with record derivation, or it is a standalone curiosity. */
	@Test
	public void setOfRecordsResolves() throws MalformedDataException {
		JsonCodec<Set<Tag>> codec = JsonCodecFactory.defaultInstance().resolve(new TypeT<Set<Tag>>() {});
		Set<Tag> value = new LinkedHashSet<>(List.of(new Tag("x")));
		assertJson("[{\"name\":\"x\"}]", codec, value);
		assertEquals(value, decode(codec, "[{\"name\":\"x\"}]"));
	}

	// ================================================================ T029 — UUID

	/**
	 * FR-024 / SC-008: a JSON <b>string</b>, lower case, hyphenated, exactly {@code UUID.toString()}.
	 * The quotes in the expected literal are the assertion.
	 */
	@Test
	public void uuidEncodesAsItsCanonicalString() throws MalformedDataException {
		JsonCodec<UUID> codec = JsonCodecFactory.defaultInstance().resolve(UUID.class);
		assertJson("\"123e4567-e89b-12d3-a456-426614174000\"", codec, UUID_VALUE);
		assertEquals(UUID_VALUE, decode(codec, "\"123e4567-e89b-12d3-a456-426614174000\""));
	}

	record Entity(UUID id, String name) {}

	/** US3's goal as an assertion: a realistic model does not stop at the first {@code UUID}. */
	@Test
	public void uuidInsideADerivedRecord() throws MalformedDataException {
		JsonCodec<Entity> codec = JsonCodecFactory.defaultInstance().resolve(Entity.class);
		Entity value = new Entity(UUID_VALUE, "n");
		assertJson("{\"id\":\"123e4567-e89b-12d3-a456-426614174000\",\"name\":\"n\"}", codec, value);
		assertEquals(value, decode(codec, "{\"id\":\"123e4567-e89b-12d3-a456-426614174000\",\"name\":\"n\"}"));
	}

	@Test
	public void malformedUuidIsRejected() {
		JsonCodec<UUID> codec = JsonCodecFactory.defaultInstance().resolve(UUID.class);
		MalformedDataException e = assertThrows(MalformedDataException.class, () -> decode(codec, "\"not-a-uuid\""));
		assertTrue(e.getCause() instanceof JsonValidationException);
		assertTrue(e.getCause().getMessage().contains("not-a-uuid"));
	}

	/**
	 * {@code UUID.fromString} is case-insensitive on input while {@code toString} always emits lower
	 * case. The asymmetry is deliberate and pinned here so nobody later "fixes" it into a rejection:
	 * a round trip through this codec <b>normalises case</b> — value-preserving, not byte-preserving,
	 * for an upper-case source.
	 */
	@Test
	public void upperCaseInputDecodesToTheSameUuid() throws MalformedDataException {
		JsonCodec<UUID> codec = JsonCodecFactory.defaultInstance().resolve(UUID.class);
		assertEquals(UUID_VALUE, decode(codec, "\"123E4567-E89B-12D3-A456-426614174000\""));
	}

	// ================================================================ T030 — BigDecimal / BigInteger

	/**
	 * FR-025: big numbers encode as JSON <b>strings</b>, and the quotes are the assertion.
	 * <p>
	 * Why not a JSON number (research.md Decision 7):
	 * <ul>
	 *     <li>dsl-json's generic tree yields {@code Long} for <i>any</i> integral literal and
	 *     {@code BigDecimal} for <i>any</i> fractional one, so a numeric encoding hands the round trip
	 *     to the parser's normalisation rather than to the codec;</li>
	 *     <li>{@code BigDecimal.equals} is scale-sensitive — {@code new BigDecimal("1.500")} does not
	 *     equal {@code new BigDecimal("1.5")} though {@code compareTo} is {@code 0} — so a numeric
	 *     encoding makes round-trip <i>equality</i> depend on how many trailing zeros survive;</li>
	 *     <li>{@code BigInteger} has no {@code long} bound, so a large enough JSON number is at the
	 *     mercy of the reader.</li>
	 * </ul>
	 */
	@Test
	public void bigDecimalEncodesAsAStringNotANumber() {
		JsonCodec<BigDecimal> codec = JsonCodecFactory.defaultInstance().resolve(BigDecimal.class);
		assertJson("\"1.500\"", codec, new BigDecimal("1.500"));
	}

	/**
	 * The scale-sensitive round trip. {@code assertEquals} is the primary assertion precisely because
	 * a numeric encoding that normalised {@code 1.500} to {@code 1.5} would still pass the
	 * {@code compareTo} one.
	 */
	@Test
	public void bigDecimalPreservesScale() throws MalformedDataException {
		JsonCodec<BigDecimal> codec = JsonCodecFactory.defaultInstance().resolve(BigDecimal.class);
		BigDecimal decoded = decode(codec, "\"1.500\"");
		assertEquals(new BigDecimal("1.500"), decoded);
		assertEquals(3, decoded.scale());
		assertNotEquals(new BigDecimal("1.5"), decoded);
		assertEquals(0, decoded.compareTo(new BigDecimal("1.5")));
	}

	/**
	 * The second scale trap, and the reason the encoder must be {@code toString()}:
	 * {@code BigDecimal.toString()} round-trips exactly against {@code new BigDecimal(String)} by its
	 * javadoc's guarantee, while {@code toPlainString()} would emit {@code 1000} at scale 0. If the
	 * codec is ever written with {@code toPlainString} this method is the only thing that catches it.
	 */
	@Test
	public void bigDecimalPreservesExponentForm() throws MalformedDataException {
		JsonCodec<BigDecimal> codec = JsonCodecFactory.defaultInstance().resolve(BigDecimal.class);
		assertJson("\"1E+3\"", codec, new BigDecimal("1E+3"));
		BigDecimal exp = decode(codec, "\"1E+3\"");
		assertEquals(-3, exp.scale());
		assertNotEquals(new BigDecimal("1000"), exp);
	}

	@Test
	public void bigDecimalBeyondDoubleRoundTrips() throws MalformedDataException {
		JsonCodec<BigDecimal> codec = JsonCodecFactory.defaultInstance().resolve(BigDecimal.class);
		BigDecimal value = new BigDecimal("123456789012345678901234567890.123456789");
		assertJson("\"123456789012345678901234567890.123456789\"", codec, value);
		assertEquals(value, decode(codec, "\"123456789012345678901234567890.123456789\""));
	}

	@Test
	public void bigIntegerEncodesAsAString() throws MalformedDataException {
		JsonCodec<BigInteger> codec = JsonCodecFactory.defaultInstance().resolve(BigInteger.class);
		BigInteger value = new BigInteger("123456789012345678901234567890");
		assertJson("\"123456789012345678901234567890\"", codec, value);
		assertEquals(value, decode(codec, "\"123456789012345678901234567890\""));

		assertJson("\"-42\"", codec, BigInteger.valueOf(-42));
		assertEquals(BigInteger.valueOf(-42), decode(codec, "\"-42\""));
	}

	record Money(BigDecimal amount, BigInteger units) {}

	@Test
	public void bigNumbersInsideADerivedRecord() throws MalformedDataException {
		JsonCodec<Money> codec = JsonCodecFactory.defaultInstance().resolve(Money.class);
		Money value = new Money(new BigDecimal("1.500"), BigInteger.valueOf(7));
		assertJson("{\"amount\":\"1.500\",\"units\":\"7\"}", codec, value);
		assertEquals(value, decode(codec, "{\"amount\":\"1.500\",\"units\":\"7\"}"));
	}

	@Test
	public void malformedBigNumbersAreRejected() {
		JsonCodec<BigDecimal> decimalCodec = JsonCodecFactory.defaultInstance().resolve(BigDecimal.class);
		MalformedDataException decimalException =
			assertThrows(MalformedDataException.class, () -> decode(decimalCodec, "\"not-a-number\""));
		assertTrue(decimalException.getCause() instanceof JsonValidationException);

		JsonCodec<BigInteger> integerCodec = JsonCodecFactory.defaultInstance().resolve(BigInteger.class);
		MalformedDataException integerException =
			assertThrows(MalformedDataException.class, () -> decode(integerCodec, "\"not-a-number\""));
		assertTrue(integerException.getCause() instanceof JsonValidationException);
	}

	// ================================================================ T031 — java.time

	/**
	 * {@code LocalTime.toString()} is variable-length — it omits the seconds when they are zero and
	 * the nanoseconds when they are zero. Both forms are real and both are pinned; the short one is
	 * the one a reader will not expect.
	 */
	@Test
	public void localTimeRoundTrips() throws MalformedDataException {
		JsonCodec<LocalTime> codec = JsonCodecFactory.defaultInstance().resolve(LocalTime.class);

		assertJson("\"10:15:30.123456789\"", codec, LocalTime.of(10, 15, 30, 123456789));
		assertEquals(LocalTime.of(10, 15, 30, 123456789), decode(codec, "\"10:15:30.123456789\""));

		assertJson("\"10:15\"", codec, LocalTime.of(10, 15));
		assertEquals(LocalTime.of(10, 15), decode(codec, "\"10:15\""));
	}

	@Test
	public void localDateTimeRoundTrips() throws MalformedDataException {
		JsonCodec<LocalDateTime> codec = JsonCodecFactory.defaultInstance().resolve(LocalDateTime.class);

		assertJson("\"2026-08-11T10:15:30\"", codec, LocalDateTime.of(2026, 8, 11, 10, 15, 30));
		assertEquals(LocalDateTime.of(2026, 8, 11, 10, 15, 30), decode(codec, "\"2026-08-11T10:15:30\""));

		LocalDateTime withNanos = LocalDateTime.of(2026, 8, 11, 10, 15, 30, 123456789);
		assertJson("\"2026-08-11T10:15:30.123456789\"", codec, withNanos);
		assertEquals(withNanos, decode(codec, "\"2026-08-11T10:15:30.123456789\""));
	}

	/**
	 * FR-028's load-bearing case. The nanosecond assertion is what makes "ISO-8601, not epoch millis"
	 * falsifiable: an epoch-millis encoding truncates {@code 123456789} to {@code 123000000} and would
	 * pass every other assertion here.
	 */
	@Test
	public void instantIsIso8601NotEpochMillis() throws MalformedDataException {
		JsonCodec<Instant> codec = JsonCodecFactory.defaultInstance().resolve(Instant.class);
		Instant instant = Instant.parse("2026-08-11T10:15:30.123456789Z");
		assertJson("\"2026-08-11T10:15:30.123456789Z\"", codec, instant);
		assertEquals(instant, decode(codec, "\"2026-08-11T10:15:30.123456789Z\""));
		assertEquals(123456789, decode(codec, "\"2026-08-11T10:15:30.123456789Z\"").getNano());
	}

	@Test
	public void durationRoundTrips() throws MalformedDataException {
		JsonCodec<Duration> codec = JsonCodecFactory.defaultInstance().resolve(Duration.class);

		Duration duration = Duration.ofHours(8).plusMinutes(6).plusSeconds(12).plusMillis(345);
		assertJson("\"PT8H6M12.345S\"", codec, duration);
		assertEquals(duration, decode(codec, "\"PT8H6M12.345S\""));

		assertJson("\"PT0S\"", codec, Duration.ZERO);
		assertEquals(Duration.ZERO, decode(codec, "\"PT0S\""));

		assertJson("\"PT-5S\"", codec, Duration.ofSeconds(-5));
		assertEquals(Duration.ofSeconds(-5), decode(codec, "\"PT-5S\""));
	}

	record Event(Instant at, Duration took, LocalDate on, LocalTime local) {}

	/**
	 * One assertion covering the four new temporal types <b>and</b> the pre-existing
	 * {@code LocalDate}, proving a model mixing them has one encoding style rather than two.
	 */
	@Test
	public void javaTimeInsideADerivedRecord() throws MalformedDataException {
		JsonCodec<Event> codec = JsonCodecFactory.defaultInstance().resolve(Event.class);
		Event value = new Event(
			Instant.parse("2026-08-11T10:15:30Z"),
			Duration.ofMinutes(90),
			LocalDate.of(2026, 8, 11),
			LocalTime.of(10, 15));
		String json = "{\"at\":\"2026-08-11T10:15:30Z\",\"took\":\"PT1H30M\",\"on\":\"2026-08-11\",\"local\":\"10:15\"}";
		assertJson(json, codec, value);
		assertEquals(value, decode(codec, json));
	}

	/**
	 * The <b>cause type is asserted only for the new four</b>. The pre-existing {@code LocalDate}
	 * codec raises a dsl-json {@code ParsingException} through {@code reader.newParseError(...)} while
	 * these raise {@link JsonValidationException}; both are wrapped into {@link MalformedDataException}
	 * at the {@link JsonUtils} boundary, so a caller cannot tell them apart. Harmonising them would be
	 * a behaviour change to a shipped codec, which User Story 2 exists to prevent.
	 */
	@Test
	public void malformedTemporalsAreRejected() {
		JsonCodecFactory factory = JsonCodecFactory.defaultInstance();

		JsonCodec<LocalTime> timeCodec = factory.resolve(LocalTime.class);
		assertTrue(assertThrows(MalformedDataException.class, () -> decode(timeCodec, "\"25:99\""))
			.getCause() instanceof JsonValidationException);

		JsonCodec<Instant> instantCodec = factory.resolve(Instant.class);
		assertTrue(assertThrows(MalformedDataException.class, () -> decode(instantCodec, "\"not-a-time\""))
			.getCause() instanceof JsonValidationException);

		JsonCodec<Duration> durationCodec = factory.resolve(Duration.class);
		assertTrue(assertThrows(MalformedDataException.class, () -> decode(durationCodec, "\"P\""))
			.getCause() instanceof JsonValidationException);
	}

	/** FR-028's boundary, executable: {@code ZonedDateTime}, {@code OffsetDateTime} and {@code Period} stay out. */
	@Test
	public void zonedDateTimeIsStillUnresolved() {
		assertThrows(UnsupportedOperationException.class,
			() -> JsonCodecFactory.defaultInstance().resolve(ZonedDateTime.class));
	}

	// ================================================================ T032 — reference arrays

	@Test
	public void stringArrayRoundTripsElementWise() throws MalformedDataException {
		JsonCodec<String[]> codec = JsonCodecFactory.defaultInstance().resolve(String[].class);
		assertJson("[\"a\",\"b\"]", codec, new String[]{"a", "b"});
		assertArrayEquals(new String[]{"a", "b"}, decode(codec, "[\"a\",\"b\"]"));
	}

	/**
	 * <b>The assertion that must not be dropped.</b> An implementation that accumulates into a
	 * {@code List} and calls {@code toArray()} with no argument returns an {@code Object[]}, which
	 * passes {@code assertArrayEquals} and then fails at <i>runtime</i> inside a derived record with
	 * {@code IllegalArgumentException: argument type mismatch} from {@code Constructor.newInstance}.
	 */
	@Test
	public void decodedArrayHasTheDeclaredComponentType() throws MalformedDataException {
		JsonCodec<String[]> codec = JsonCodecFactory.defaultInstance().resolve(String[].class);
		assertSame(String[].class, decode(codec, "[\"a\"]").getClass());
	}

	@Test
	public void emptyArrayRoundTrips() throws MalformedDataException {
		JsonCodec<String[]> codec = JsonCodecFactory.defaultInstance().resolve(String[].class);
		assertJson("[]", codec, new String[0]);
		String[] decoded = decode(codec, "[]");
		assertEquals(0, decoded.length);
		assertSame(String[].class, decoded.getClass());
	}

	/**
	 * The registration must recurse into its own component. Resolved through {@code TypeT} rather than
	 * a {@code Class} on purpose — only one of the two entry paths exercises
	 * {@code AnnotatedTypes.annotatedTypeOf}'s array branch, and both must reach the registration.
	 */
	@Test
	public void nestedArrayRoundTrips() throws MalformedDataException {
		JsonCodec<String[][]> codec = JsonCodecFactory.defaultInstance().resolve(new TypeT<String[][]>() {});
		assertJson("[[\"a\"],[\"b\",\"c\"]]", codec, new String[][]{{"a"}, {"b", "c"}});
		String[][] decoded = decode(codec, "[[\"a\"],[\"b\",\"c\"]]");
		assertSame(String[][].class, decoded.getClass());
		assertSame(String[].class, decoded[0].getClass());
		assertArrayEquals(new String[]{"b", "c"}, decoded[1]);
	}

	@Test
	public void arrayOfRecordsRoundTrips() throws MalformedDataException {
		JsonCodec<Line[]> codec = JsonCodecFactory.defaultInstance().resolve(Line[].class);
		assertJson("[{\"sku\":\"x\",\"qty\":2}]", codec, new Line[]{new Line("x", 2)});
		Line[] decoded = decode(codec, "[{\"sku\":\"x\",\"qty\":2}]");
		assertSame(Line[].class, decoded.getClass());
		assertArrayEquals(new Line[]{new Line("x", 2)}, decoded);
	}

	record Tags(String name, String[] tags) {}

	/**
	 * Compared <b>component-wise</b> on purpose: a record's generated {@code equals} uses
	 * {@code Object.equals} on an array component, i.e. reference identity, so
	 * {@code assertEquals(record, decoded)} fails even when the decode is perfect. That is a property
	 * of {@code record}, not a bug in the codec.
	 */
	@Test
	public void arrayInsideADerivedRecord() throws MalformedDataException {
		JsonCodec<Tags> codec = JsonCodecFactory.defaultInstance().resolve(Tags.class);
		assertJson("{\"name\":\"n\",\"tags\":[\"a\",\"b\"]}", codec, new Tags("n", new String[]{"a", "b"}));
		Tags decoded = decode(codec, "{\"name\":\"n\",\"tags\":[\"a\",\"b\"]}");
		assertEquals("n", decoded.name());
		assertArrayEquals(new String[]{"a", "b"}, decoded.tags());
	}

	/**
	 * FR-027 keeps primitive arrays out of scope: {@code int[].class} is not assignable to
	 * {@code Object[].class}, so it falls to the untouched {@code Object.class} fallback. The
	 * <i>named</i> in-record failure message is Phase 6, so no message is asserted here.
	 */
	@Test
	public void primitiveArraysAreStillUnresolved() {
		assertThrows(UnsupportedOperationException.class,
			() -> JsonCodecFactory.defaultInstance().resolve(int[].class));
	}

	// ================================================================ T033 — Optional

	record Note(String id, Optional<String> note) {}

	record AllOptional(Optional<String> a, Optional<Integer> b) {}

	/**
	 * <b>Container behaviour.</b> Omission is decided by {@code ObjectJsonCodec.BuilderArray}'s
	 * default-value overload — its encoder provider returns {@code null} when the value equals the
	 * default, and {@code AbstractMapJsonCodec.write} skips such a member <i>before</i> writing its
	 * comma, so no dangling separator appears. Not {@code {"id":"a","note":null}}.
	 */
	@Test
	public void emptyOptionalMemberIsOmitted() {
		JsonCodec<Note> codec = JsonCodecFactory.defaultInstance().resolve(Note.class);
		assertJson("{\"id\":\"a\"}", codec, new Note("a", Optional.empty()));
	}

	/**
	 * <b>Codec behaviour.</b> A present value encodes bare — not {@code {"note":{"value":"x"}}},
	 * not {@code {"note":["x"]}}.
	 */
	@Test
	public void presentOptionalMemberEncodesBare() throws MalformedDataException {
		JsonCodec<Note> codec = JsonCodecFactory.defaultInstance().resolve(Note.class);
		Note value = new Note("a", Optional.of("x"));
		assertJson("{\"id\":\"a\",\"note\":\"x\"}", codec, value);
		assertEquals(value, decode(codec, "{\"id\":\"a\",\"note\":\"x\"}"));
	}

	/**
	 * <b>Both mechanisms, two code paths, one value.</b> Absence is supplied by the container's
	 * default; an explicit {@code null} is turned into {@code Optional.empty()} by the codec's
	 * {@code wasNull()} guard. The labels are what make a future failure readable.
	 */
	@Test
	public void absentAndExplicitNullBothDecodeToEmpty() throws MalformedDataException {
		JsonCodec<Note> codec = JsonCodecFactory.defaultInstance().resolve(Note.class);
		assertEquals(new Note("a", Optional.empty()), decode(codec, "{\"id\":\"a\"}"));                  // container
		assertEquals(new Note("a", Optional.empty()), decode(codec, "{\"id\":\"a\",\"note\":null}"));    // codec
	}

	/**
	 * <b>The defect-catching method.</b> When <i>every</i> component carries a default,
	 * {@code ObjectJsonCodec.BuilderArray.doBuild()} used to take its second branch and allocate
	 * {@code new Object[prototype.length]} instead of copying the prototype, silently discarding every
	 * default — so {@code {}} decoded to {@code AllOptional(null, null)}. If this method fails, the
	 * cause is that line in {@code ObjectJsonCodec}, <b>not</b> {@code JsonCodecs.ofOptional}.
	 */
	@Test
	public void aRecordWhoseComponentsAreAllOptionalStillRoundTrips() throws MalformedDataException {
		JsonCodec<AllOptional> codec = JsonCodecFactory.defaultInstance().resolve(AllOptional.class);
		assertJson("{}", codec, new AllOptional(Optional.empty(), Optional.empty()));
		assertEquals(new AllOptional(Optional.empty(), Optional.empty()), decode(codec, "{}"));
		assertEquals(new AllOptional(Optional.of("x"), Optional.empty()), decode(codec, "{\"a\":\"x\"}"));
	}

	/**
	 * <b>Codec only, no container.</b> Alone, an empty {@code Optional} is JSON {@code null};
	 * omission is the container's job and must not migrate into the codec.
	 */
	@Test
	public void optionalResolvesStandalone() throws MalformedDataException {
		JsonCodec<Optional<String>> codec =
			JsonCodecFactory.defaultInstance().resolve(new TypeT<Optional<String>>() {});
		assertJson("\"x\"", codec, Optional.of("x"));
		assertJson("null", codec, Optional.empty());
		assertEquals(Optional.of("x"), decode(codec, "\"x\""));
		assertEquals(Optional.empty(), decode(codec, "null"));
	}

	/** A list has no omission mechanism, so this is the codec's {@code null} form in a second container. */
	@Test
	public void optionalInsideAList() throws MalformedDataException {
		JsonCodec<List<Optional<String>>> codec =
			JsonCodecFactory.defaultInstance().resolve(new TypeT<List<Optional<String>>>() {});
		List<Optional<String>> value = List.of(Optional.of("x"), Optional.<String>empty());
		assertJson("[\"x\",null]", codec, value);
		assertEquals(value, decode(codec, "[\"x\",null]"));
	}

	// ================================================================ T042 — the BuilderArray repair

	record Pair(String a, String b) {}

	/**
	 * The {@code ObjectJsonCodec.BuilderArray} defect this feature repaired, pinned at the
	 * <b>mechanism</b> rather than through derivation, so a future regression is attributed to the
	 * right class.
	 * <p>
	 * {@code doBuild()} branches on whether any field lacks a default. When <b>every</b> field has one
	 * the second branch used to allocate {@code new Object[prototype.length]} — an all-{@code null}
	 * accumulator — instead of copying the prototype, so every default was silently discarded and an
	 * absent member decoded to {@code null}. The mixed case (below) always took the correct branch,
	 * which is why the defect stayed latent: {@code cloud-lsmt-cube}'s only defaulted-field consumer
	 * mixes a defaulted field with an undefaulted one.
	 */
	@Test
	public void builderArraySuppliesDefaultsWhenEveryFieldHasOne() throws MalformedDataException {
		JsonCodec<Pair> codec = ObjectJsonCodec.builder(params -> new Pair((String) params[0], (String) params[1]))
			.with("a", Pair::a, JsonCodecs.ofString(), "defaultA")
			.with("b", Pair::b, JsonCodecs.ofString(), "defaultB")
			.build();

		assertEquals(new Pair("defaultA", "defaultB"), decode(codec, "{}"));
		assertEquals(new Pair("x", "defaultB"), decode(codec, "{\"a\":\"x\"}"));
		// and the encode half of the same overload: a member equal to its default is omitted
		assertJson("{}", codec, new Pair("defaultA", "defaultB"));
		assertJson("{\"a\":\"x\"}", codec, new Pair("x", "defaultB"));
	}

	/** The branch that was always correct, kept beside the repaired one so the pair is readable. */
	@Test
	public void builderArrayStillFailsOnAnAbsentUndefaultedField() throws MalformedDataException {
		JsonCodec<Pair> codec = ObjectJsonCodec.builder(params -> new Pair((String) params[0], (String) params[1]))
			.with("a", Pair::a, JsonCodecs.ofString())
			.with("b", Pair::b, JsonCodecs.ofString(), "defaultB")
			.build();

		assertEquals(new Pair("x", "defaultB"), decode(codec, "{\"a\":\"x\"}"));
		MalformedDataException e = assertThrows(MalformedDataException.class, () -> decode(codec, "{\"b\":\"y\"}"));
		assertTrue(e.getCause() instanceof JsonValidationException);
	}

	// ================================================================ T034 — Map with Enum / UUID keys

	/**
	 * The key form is {@code Enum.name()}, upper case, verbatim — not {@code ordinal()}, not
	 * {@code toString()}. {@code LinkedHashMap}, never {@code Map.of}: see the class Javadoc.
	 */
	@Test
	public void enumKeyedMapRoundTrips() throws MalformedDataException {
		JsonCodec<Map<Colour, String>> codec =
			JsonCodecFactory.defaultInstance().resolve(new TypeT<Map<Colour, String>>() {});
		Map<Colour, String> value = new LinkedHashMap<>();
		value.put(Colour.RED, "r");
		value.put(Colour.GREEN, "g");
		assertJson("{\"RED\":\"r\",\"GREEN\":\"g\"}", codec, value);
		assertEquals(value, decode(codec, "{\"RED\":\"r\",\"GREEN\":\"g\"}"));
	}

	@Test
	public void uuidKeyedMapRoundTrips() throws MalformedDataException {
		JsonCodec<Map<UUID, String>> codec =
			JsonCodecFactory.defaultInstance().resolve(new TypeT<Map<UUID, String>>() {});
		Map<UUID, String> value = new LinkedHashMap<>();
		value.put(UUID_VALUE, "v");
		assertJson("{\"123e4567-e89b-12d3-a456-426614174000\":\"v\"}", codec, value);
		assertEquals(value, decode(codec, "{\"123e4567-e89b-12d3-a456-426614174000\":\"v\"}"));
	}

	/** The value side must still resolve through {@code ctx.scanTypeArgument(1)} after the key branch changes. */
	@Test
	public void enumKeyedMapWithARecordValue() throws MalformedDataException {
		JsonCodec<Map<Colour, Line>> codec =
			JsonCodecFactory.defaultInstance().resolve(new TypeT<Map<Colour, Line>>() {});
		Map<Colour, Line> value = new LinkedHashMap<>();
		value.put(Colour.RED, new Line("x", 1));
		assertJson("{\"RED\":{\"sku\":\"x\",\"qty\":1}}", codec, value);
		assertEquals(value, decode(codec, "{\"RED\":{\"sku\":\"x\",\"qty\":1}}"));
	}

	/** Contract §3: the message names the enum and the offending key. {@code contains}, never equality. */
	@Test
	public void unknownEnumKeyIsRejected() {
		JsonCodec<Map<Colour, String>> codec =
			JsonCodecFactory.defaultInstance().resolve(new TypeT<Map<Colour, String>>() {});
		MalformedDataException e =
			assertThrows(MalformedDataException.class, () -> decode(codec, "{\"MAUVE\":\"m\"}"));
		assertTrue(e.getCause() instanceof JsonValidationException);
		assertTrue(e.getCause().getMessage().contains("MAUVE"));
		assertTrue(e.getCause().getMessage().contains("Colour"));
	}

	@Test
	public void malformedUuidKeyIsRejected() {
		JsonCodec<Map<UUID, String>> codec =
			JsonCodecFactory.defaultInstance().resolve(new TypeT<Map<UUID, String>>() {});
		MalformedDataException e = assertThrows(MalformedDataException.class, () -> decode(codec, "{\"nope\":\"v\"}"));
		assertTrue(e.getCause() instanceof JsonValidationException);
	}

	/**
	 * The local guard that the two pre-existing key branches were not disturbed by the two new ones,
	 * ahead of the full-suite run. Reproduces {@code FactoryNonRegressionTest}'s literals.
	 */
	@Test
	public void stringAndNumberKeyedMapsAreUnaffected() throws MalformedDataException {
		JsonCodec<Map<String, Integer>> stringKeyed =
			JsonCodecFactory.defaultInstance().resolve(new TypeT<Map<String, Integer>>() {});
		Map<String, Integer> stringKeyedValue = new LinkedHashMap<>();
		stringKeyedValue.put("abc", 123);
		stringKeyedValue.put("def", 456);
		assertJson("{\"abc\":123,\"def\":456}", stringKeyed, stringKeyedValue);
		assertEquals(stringKeyedValue, decode(stringKeyed, "{\"abc\":123,\"def\":456}"));

		JsonCodec<Map<Integer, Integer>> numberKeyed =
			JsonCodecFactory.defaultInstance().resolve(new TypeT<Map<Integer, Integer>>() {});
		Map<Integer, Integer> numberKeyedValue = new LinkedHashMap<>();
		numberKeyedValue.put(1, 123);
		numberKeyedValue.put(2, 456);
		assertJson("{\"1\":123,\"2\":456}", numberKeyed, numberKeyedValue);
		assertEquals(numberKeyedValue, decode(numberKeyed, "{\"1\":123,\"2\":456}"));
	}

	/**
	 * Decision 10's boundary, executable: extending the existing dispatch must not quietly become
	 * "accept anything". <b>Type only</b> — the message is still {@code "TODO"} until Phase 6's T056,
	 * and T051 is the task that asserts it once one has been written.
	 */
	@Test
	public void anUnsupportedMapKeyStillFails() {
		assertThrows(IllegalArgumentException.class,
			() -> JsonCodecFactory.defaultInstance().resolve(new TypeT<Map<LocalDate, String>>() {}));
	}

	// ---------------------------------------------------------------- helpers

	private static <T> void assertJson(String expected, JsonCodec<T> codec, T value) {
		assertEquals(expected, JsonUtils.toJson(codec, value));
	}

	private static <T> T decode(JsonCodec<T> codec, String json) throws MalformedDataException {
		return JsonUtils.fromJson(codec, json);
	}
}
