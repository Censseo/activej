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
import io.activej.json.annotations.JsonNullable;
import io.activej.json.annotations.JsonSubclasses;
import io.activej.types.TypeT;
import org.junit.Test;

import java.lang.reflect.AnnotatedParameterizedType;
import java.lang.reflect.AnnotatedType;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

/**
 * Pins the reflective descriptor {@code RecordJsonCodec} reads from a {@code record} and the codec
 * it assembles from it.
 * <p>
 * Bare JUnit 4 by design: {@code activej-test} is not on this module's classpath, and nothing here
 * touches a {@code ByteBuf}, a {@code Promise} or a reactor.
 */
public class RecordJsonCodecTest {

	record Pair(String a, String b) {}

	/**
	 * Declares a <b>second two-argument constructor</b>. {@code getDeclaredConstructors()} has no
	 * specified order, so an arity-based search picks one at random — and deterministically the
	 * wrong one on some JDK builds.
	 */
	record Decoy(String a, String b) {
		Decoy(int a, int b) {this(Integer.toString(a), Integer.toString(b));}

		Decoy(String only) {this(only, only);}
	}

	record Empty() {}

	record Boxed(List<String> items, @JsonNullable String note) {}

	record Five(String zulu, String alpha, String mike, String bravo, String yankee) {}

	record Flat(String a, int b) {}

	record Zam(String z, String a, String m) {}

	record Note(@JsonNullable String note) {}

	record StrictNote(String note) {}

	record Leaf(String s) {}

	record Outer(Leaf leaf) {}

	sealed interface Shape permits Circle {}

	record Circle(int r) implements Shape {}

	record Drawing(@JsonSubclasses({Circle.class}) Shape shape) {}

	record GenericPair<K1, V1>(K1 first, V1 second) {}

	record Holder<T>(List<T> items) {}

	record Box<T>(T value) {}

	record Node(String v, List<Node> kids) {}

	record Alpha(Beta b) {}

	record Beta(List<Alpha> as) {}

	/** Not a record, and not registered anywhere — so it has no codec until a test gives it one. */
	static final class Opaque {
		private final String text;

		Opaque(String text) {this.text = text;}

		String text() {return text;}

		@Override
		public boolean equals(Object o) {return o instanceof Opaque other && text.equals(other.text);}

		@Override
		public int hashCode() {return text.hashCode();}
	}

	record WithOpaque(Opaque opaque) {}

	@Test
	public void componentOrderIsCanonical() {
		assertArrayEquals(new String[]{"a", "b"}, componentNames(RecordJsonCodec.describe(Pair.class)));

		// canonical (declaration) order, deliberately not alphabetical — this order becomes the JSON
		// member order, so the sequence is the assertion, not the set
		assertArrayEquals(
			new String[]{"zulu", "alpha", "mike", "bravo", "yankee"},
			componentNames(RecordJsonCodec.describe(Five.class)));
	}

	@Test
	public void canonicalConstructorIsFoundByComponentTypes() throws Exception {
		RecordJsonCodec.RecordDescriptor descriptor = RecordJsonCodec.describe(Decoy.class);

		assertArrayEquals(
			new Class<?>[]{String.class, String.class},
			descriptor.canonicalConstructor().getParameterTypes());

		// positive rather than merely non-failing: the (int, int) overload would yield Decoy("0","0")
		// shaped values and cannot be confused with this
		assertEquals(new Decoy("x", "y"), descriptor.canonicalConstructor().newInstance("x", "y"));
	}

	@Test
	public void bothMembersAreAccessible() throws Exception {
		RecordJsonCodec.RecordDescriptor descriptor = RecordJsonCodec.describe(Pair.class);
		Pair instance = new Pair("x", "y");

		for (RecordJsonCodec.ComponentBinding component : descriptor.components()) {
			assertTrue(component.accessor().canAccess(instance));
		}
		assertTrue(descriptor.canonicalConstructor().canAccess(null));

		assertEquals("x", descriptor.components()[0].accessor().invoke(instance));
		assertEquals("y", descriptor.components()[1].accessor().invoke(instance));
		assertEquals(instance, descriptor.canonicalConstructor().newInstance("x", "y"));
	}

	/**
	 * Re-confirms on this classpath that {@code setAccessible} binds a non-public record with no
	 * caller {@code Lookup}.
	 * <p>
	 * Threat that travels with the result: only <b>package</b> access is exercised. This repository
	 * contains no {@code module-info.java}, so the genuinely non-exported JPMS case is reproduced
	 * nowhere. The cross-package case is covered separately.
	 */
	@Test
	public void nonPublicRecordBinds() throws Exception {
		RecordJsonCodec.RecordDescriptor descriptor = RecordJsonCodec.describe(Hidden.class);

		assertArrayEquals(new String[]{"n"}, componentNames(descriptor));
		assertEquals(7, descriptor.components()[0].accessor().invoke(new Hidden(7)));
		assertEquals(new Hidden(7), descriptor.canonicalConstructor().newInstance(7));
	}

	@Test
	public void zeroComponentRecordDescribes() throws Exception {
		RecordJsonCodec.RecordDescriptor descriptor = RecordJsonCodec.describe(Empty.class);

		assertEquals(0, descriptor.components().length);
		assertEquals(0, descriptor.canonicalConstructor().getParameterCount());
		assertEquals(new Empty(), descriptor.canonicalConstructor().newInstance());
	}

	/**
	 * Pins the <i>input</i> to the per-component scan: storing {@code getType()} or
	 * {@code getGenericType()} instead of {@code getAnnotatedType()} would drop every component
	 * annotation silently, and the failure would surface much later and far away.
	 * <p>
	 * {@code RecordComponent.getAnnotatedType()} carries only annotations applicable as
	 * {@code TYPE_USE}. {@code @JsonNullable} and {@code @JsonSubclasses} both declare it; an
	 * annotation targeting only {@code RECORD_COMPONENT} would appear on
	 * {@code component.getAnnotations()} and not here.
	 */
	@Test
	public void componentAnnotatedTypeCarriesTypeUseAnnotations() {
		RecordJsonCodec.RecordDescriptor descriptor = RecordJsonCodec.describe(Boxed.class);

		AnnotatedType items = descriptor.components()[0].annotatedType();
		assertTrue(items instanceof AnnotatedParameterizedType);
		assertEquals(
			String.class,
			((AnnotatedParameterizedType) items).getAnnotatedActualTypeArguments()[0].getType());

		AnnotatedType note = descriptor.components()[1].annotatedType();
		assertNotNull(note.getAnnotation(JsonNullable.class));
	}

	@Test
	public void describeRejectsNonRecord() {
		assertThrows(IllegalArgumentException.class, () -> RecordJsonCodec.describe(String.class));
	}

	// ---------------------------------------------------------------- assembly

	/** Codecs are hand-supplied here, which is what keeps assembly testable without a live scan. */
	@Test
	public void flatRecordRoundTrips() throws Exception {
		JsonCodec<Flat> codec = assemble(Flat.class, JsonCodecs.ofString(), JsonCodecs.ofInteger());

		assertEquals("{\"a\":\"x\",\"b\":2}", JsonUtils.toJson(codec, new Flat("x", 2)));
		assertEquals(new Flat("x", 2), JsonUtils.fromJson(codec, "{\"a\":\"x\",\"b\":2}"));
	}

	@Test
	public void memberOrderIsCanonicalNotAlphabetical() {
		JsonCodec<Zam> codec = assemble(Zam.class,
			JsonCodecs.ofString(), JsonCodecs.ofString(), JsonCodecs.ofString());

		assertEquals("{\"z\":\"1\",\"a\":\"2\",\"m\":\"3\"}", JsonUtils.toJson(codec, new Zam("1", "2", "3")));
	}

	/**
	 * Falls out with no special case: {@code BuilderArray} with no fields takes the no-default
	 * branch, and the member loop never runs because the reader short-circuits on {@code '}'}.
	 */
	@Test
	public void zeroComponentRecordEncodesToEmptyObject() throws Exception {
		JsonCodec<Empty> codec = assemble(Empty.class);

		assertEquals("{}", JsonUtils.toJson(codec, new Empty()));
		assertEquals(new Empty(), JsonUtils.fromJson(codec, "{}"));
	}

	/**
	 * Existing, unchanged behaviour: the failure surfaces at construction rather than at the member,
	 * so the exception carries no member name. Do not "improve" it — that would be a behaviour change
	 * to production code.
	 */
	@Test
	public void missingRequiredMemberFails() {
		JsonCodec<Flat> codec = assemble(Flat.class, JsonCodecs.ofString(), JsonCodecs.ofInteger());

		MalformedDataException e = assertThrows(MalformedDataException.class,
			() -> JsonUtils.fromJson(codec, "{\"a\":\"x\"}"));
		assertTrue(e.getCause() instanceof JsonValidationException);
	}

	// ---------------------------------------------------------------- per-component resolution

	/**
	 * The annotations are applied by {@code JsonCodecFactory.Builder.with}'s wrapper around the
	 * mapping, not by anything in {@code RecordJsonCodec} — resolving the component on its
	 * <b>annotated</b> type is the whole mechanism.
	 */
	@Test
	public void componentAnnotationsReachTheComponentCodec() throws Exception {
		JsonCodecFactory factory = deriving();

		JsonCodec<Note> nullable = factory.resolve(Note.class);
		assertEquals("{\"note\":null}", JsonUtils.toJson(nullable, new Note(null)));
		assertEquals(new Note(null), JsonUtils.fromJson(nullable, "{\"note\":null}"));

		JsonCodec<StrictNote> strict = factory.resolve(StrictNote.class);
		assertThrows(NullPointerException.class, () -> JsonUtils.toJson(strict, new StrictNote(null)));
	}

	/**
	 * Specificity matching selects the exact type over {@code Record}, so this needs no code in the
	 * derivation — it is asserted, not implemented.
	 */
	@Test
	public void handRegisteredComponentCodecWins() throws Exception {
		JsonCodecFactory factory = deriving(JsonCodecFactory.defaultInstance().rebuild()
			.with(Leaf.class, ctx -> JsonCodecs.ofObject(Leaf::new,
				"custom", Leaf::s, JsonCodecs.ofString())));

		JsonCodec<Outer> codec = factory.resolve(Outer.class);

		assertEquals("{\"leaf\":{\"custom\":\"x\"}}", JsonUtils.toJson(codec, new Outer(new Leaf("x"))));
		assertEquals(new Outer(new Leaf("x")), JsonUtils.fromJson(codec, "{\"leaf\":{\"custom\":\"x\"}}"));
	}

	/** A {@code record} used as a {@code @JsonSubclasses} subclass derives, with no extra mechanism. */
	@Test
	public void recordAsJsonSubclassesSubclassDerives() throws Exception {
		JsonCodec<Drawing> codec = deriving().resolve(Drawing.class);

		assertEquals("{\"shape\":{\"Circle\":{\"r\":3}}}", JsonUtils.toJson(codec, new Drawing(new Circle(3))));
		assertEquals(new Drawing(new Circle(3)), JsonUtils.fromJson(codec, "{\"shape\":{\"Circle\":{\"r\":3}}}"));
	}

	// ---------------------------------------------------------------- generic substitution

	@Test
	public void genericRecordSubstitutesEachComponent() throws Exception {
		JsonCodec<GenericPair<String, Integer>> codec =
			deriving().resolve(new TypeT<GenericPair<String, Integer>>() {});

		assertEquals("{\"first\":\"x\",\"second\":2}", JsonUtils.toJson(codec, new GenericPair<>("x", 2)));
		assertEquals(new GenericPair<>("x", 2), JsonUtils.fromJson(codec, "{\"first\":\"x\",\"second\":2}"));
	}

	/** Proves {@code bind}'s recursion into parameterized arguments rather than assuming it. */
	@Test
	public void nestedGenericArgumentSubstitutes() throws Exception {
		JsonCodec<Holder<String>> codec = deriving().resolve(new TypeT<Holder<String>>() {});

		assertEquals("{\"items\":[\"a\",\"b\"]}", JsonUtils.toJson(codec, new Holder<>(List.of("a", "b"))));
		assertEquals(new Holder<>(List.of("a", "b")), JsonUtils.fromJson(codec, "{\"items\":[\"a\",\"b\"]}"));
	}

	/** {@code bind} combines the argument's annotations into the substituted component type. */
	@Test
	public void annotatedTypeArgumentReachesTheComponent() {
		JsonCodecFactory factory = deriving();

		JsonCodec<Box<String>> nullable = factory.resolve(new TypeT<Box<@JsonNullable String>>() {});
		assertEquals("{\"value\":null}", JsonUtils.toJson(nullable, new Box<>(null)));

		JsonCodec<Box<String>> plain = factory.resolve(new TypeT<Box<String>>() {});
		assertThrows(NullPointerException.class, () -> JsonUtils.toJson(plain, new Box<>(null)));
	}

	/**
	 * A raw resolution leaves every variable unbound, and {@code bind} is called unconditionally so
	 * that it throws rather than silently producing a codec for the erasure.
	 */
	@Test
	public void rawGenericRecordFails() {
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
			() -> deriving().resolve(GenericPair.class));

		assertTrue(e.getMessage(), e.getMessage().contains(GenericPair.class.getTypeParameters()[0].getName()));
	}

	// ---------------------------------------------------------------- the memo / recursion break

	/**
	 * Without the memo this is a measured {@link StackOverflowError} at <b>derivation</b> time — the
	 * finding that makes the memo a correctness requirement rather than a cache. Do not delete this
	 * test as redundant with the memoization tests; it is the one that proves termination.
	 */
	@Test
	public void selfReferencingRecordDerivesAndRoundTrips() throws Exception {
		JsonCodec<Node> codec = deriving().resolve(Node.class);

		Node tree = new Node("a", List.of(new Node("b", List.of(new Node("c", List.of())))));
		String json = "{\"v\":\"a\",\"kids\":[{\"v\":\"b\",\"kids\":[{\"v\":\"c\",\"kids\":[]}]}]}";

		assertEquals(json, JsonUtils.toJson(codec, tree));
		assertEquals(tree, JsonUtils.fromJson(codec, json));
	}

	/** Resolved from both ends — a memo that only breaks self-reference fails the second direction. */
	@Test
	public void mutuallyRecursiveRecordsDeriveAndRoundTrip() throws Exception {
		Alpha alpha = new Alpha(new Beta(List.of(new Alpha(new Beta(List.of())))));
		String json = "{\"b\":{\"as\":[{\"b\":{\"as\":[]}}]}}";

		JsonCodecFactory alphaFirst = deriving();
		JsonCodec<Alpha> alphaCodec = alphaFirst.resolve(Alpha.class);
		assertEquals(json, JsonUtils.toJson(alphaCodec, alpha));
		assertEquals(alpha, JsonUtils.fromJson(alphaCodec, json));
		assertEquals(alpha.b(), JsonUtils.fromJson(alphaFirst.resolve(Beta.class), "{\"as\":[{\"b\":{\"as\":[]}}]}"));

		JsonCodecFactory betaFirst = deriving();
		assertEquals(alpha.b(), JsonUtils.fromJson(betaFirst.resolve(Beta.class), "{\"as\":[{\"b\":{\"as\":[]}}]}"));
		assertEquals(alpha, JsonUtils.fromJson(betaFirst.resolve(Alpha.class), json));
	}

	/**
	 * Derivation is a resolve-time cost, paid once per type. The component mapping is the observable:
	 * a second walk of the record's body would enter it again.
	 */
	@Test
	public void derivationHappensOncePerType() throws Exception {
		AtomicInteger componentScans = new AtomicInteger();
		JsonCodecFactory factory = deriving(JsonCodecFactory.defaultInstance().rebuild()
			.with(String.class, ctx -> {
				componentScans.incrementAndGet();
				return JsonCodecs.ofString();
			}));

		JsonCodec<Flat> first = factory.resolve(Flat.class);
		JsonCodec<Flat> second = factory.resolve(Flat.class);

		assertSame(first, second);
		assertEquals(1, componentScans.get());

		for (int i = 0; i < 5; i++) {
			assertEquals(new Flat("x", i), JsonUtils.fromJson(first, JsonUtils.toJson(first, new Flat("x", i))));
		}
		assertEquals(1, componentScans.get());
	}

	/**
	 * Pins the {@code finally} cleanup in {@code DerivationCache}: without it the failed derivation
	 * would leave a poisoned, unfilled placeholder behind and the retry — on the <b>same</b> cache,
	 * which is what {@code rebuild()} produces here — would hand it out.
	 */
	@Test
	public void failedDerivationLeavesNoEntry() throws Exception {
		JsonCodecFactory strict = deriving();

		IllegalArgumentException e =
			assertThrows(IllegalArgumentException.class, () -> strict.resolve(WithOpaque.class));
		assertTrue(e.getCause() instanceof UnsupportedOperationException);

		// rebuild() copies the Record mapping BY REFERENCE, so the repaired factory shares the cache
		JsonCodecFactory repaired = strict.rebuild()
			.with(Opaque.class, ctx -> JsonCodecs.transform(JsonCodecs.ofString(), Opaque::text, Opaque::new))
			.build();
		JsonCodec<WithOpaque> codec = repaired.resolve(WithOpaque.class);

		assertEquals("{\"opaque\":\"x\"}", JsonUtils.toJson(codec, new WithOpaque(new Opaque("x"))));
		assertEquals(new WithOpaque(new Opaque("x")), JsonUtils.fromJson(codec, "{\"opaque\":\"x\"}"));
	}

	private static JsonCodecFactory deriving() {
		return deriving(JsonCodecFactory.defaultInstance().rebuild());
	}

	/**
	 * Registers derivation the way Phase 2 may: with a <b>test-local</b> cache captured by the
	 * lambda. Production must not do this — {@code TypeScannerRegistry.copyOf} copies entries by
	 * reference, so a captured cache would leak across every rebuilt factory.
	 */
	private static JsonCodecFactory deriving(JsonCodecFactory.Builder builder) {
		DerivationCache cache = new DerivationCache();
		return builder
			.with(Record.class, ctx -> RecordJsonCodec.derive(ctx, cache))
			.build();
	}

	private static <T> JsonCodec<T> assemble(Class<T> recordClass, JsonCodec<?>... componentCodecs) {
		//noinspection unchecked
		return (JsonCodec<T>) RecordJsonCodec.assemble(RecordJsonCodec.describe(recordClass), componentCodecs);
	}

	private static String[] componentNames(RecordJsonCodec.RecordDescriptor descriptor) {
		List<String> names = new ArrayList<>();
		for (RecordJsonCodec.ComponentBinding component : descriptor.components()) names.add(component.name());
		return names.toArray(String[]::new);
	}
}

/** Package-private and top-level on purpose — see {@code nonPublicRecordBinds}. */
record Hidden(int n) {}
