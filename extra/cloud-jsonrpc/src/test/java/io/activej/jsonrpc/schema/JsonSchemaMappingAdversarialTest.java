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
import io.activej.json.JsonCodec;
import io.activej.json.JsonCodecFactory;
import io.activej.json.JsonUtils;
import io.activej.jsonrpc.JsonRpcLimits;
import io.activej.jsonrpc.schema.JsonSchemaFragment.Any;
import io.activej.jsonrpc.schema.JsonSchemaFragment.Typed;
import io.activej.jsonrpc.schema.fixtures.ScaleTypes;
import io.activej.types.TypeT;
import io.activej.types.Types;
import org.junit.Test;

import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.Period;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Adversarial coverage of {@link JsonSchemaMapping} — pathological Java {@link Type}s, hostile
 * {@code Type} implementations, and shapes at a scale no hand-written fixture reaches. The oracle is
 * the feature contract (FR-021, FR-022, FR-023, data-model.md's mapping table), never "what the code
 * does today"; where the two coincide on a case the contract does not speak to, the test says so in
 * as many words and is named a <b>characterization</b>.
 *
 * <p>What is already pinned by {@link JsonSchemaMappingTest} is referenced rather than repeated: the
 * scalar/container/record rows, the direct self-reference {@code record Node(String, Node)},
 * {@code Map} with a non-{@code String} key, {@code UUID}/{@code Instant}/{@code Optional<String>},
 * raw {@code List}/{@code Map}, primitive arrays, and the codec round trip. Everything below is a
 * shape that suite does not have.
 *
 * <h2>Four findings this class records, none of them a defect</h2>
 * <ol>
 *     <li><b>A cycle closing inside a container does not collapse the record</b> — the container's own
 *     marker absorbs it, so {@code record Tree(String name, List<Tree> children)} is a fully described
 *     object whose {@code children} is {@code {"type":"array","items":true}}, where
 *     {@code record Node(String, Node)} is the bare boolean. That is rules 1 and 2 composing correctly,
 *     but {@link JsonSchemaMapping}'s rule 2 used to be stated as the unqualified "a recursive record
 *     falls back", which is only true of the bare-component case. The sentence was tightened; nothing
 *     about the behaviour was. See
 *     {@link #aCycleClosingInsideAContainerIsAbsorbedByThatContainersOwnMarker()}.</li>
 *     <li><b>The cycle guard is keyed by raw {@code Class}, so it is conservative</b>:
 *     {@code Wrap<Wrap<String>>} — finite, acyclic, entirely inside the subset — falls back, because
 *     the raw class {@code Wrap} is already on the walk's path when the inner one is reached. That is
 *     the price of termination, and the price is provably necessary: see
 *     {@link #aGenericRecordNestedInItselfIsRefusedAsACycleThoughItIsNotOne()}.</li>
 *     <li><b>There is no depth bound of any kind on this walk</b> — see
 *     {@link #theWalkHasNoDepthBoundAndTheWireLevelMaxJsonDepthDoesNotGovernIt()}. Unlike
 *     {@link JsonRpcLimits#MAX_JSON_DEPTH}, which bounds a <i>wire document</i> arriving from a peer,
 *     the input here is the application's own declared types, fixed at compile time and read once at
 *     {@code build()}. Deep enough, it overflows the stack; the shallowest overflow measured on a
 *     default stack was in the <i>writer</i>, not the walk, at roughly 2000 nested containers. The
 *     residual is recorded rather than fixed, and it is recorded rather than omitted.</li>
 *     <li><b>The pinned subset is deliberately narrower than {@code JsonCodecFactory}'s resolution
 *     set</b>: {@code Duration}, {@code LocalDate}, {@code Instant} and {@code UUID} all encode
 *     perfectly and are all described as {@code true} — see
 *     {@link #aTypeThatEncodesPerfectlyIsStillUndescribedWhenTheSubsetHasNoRowForIt()}. A schema is a
 *     claim about structure; a codec is behaviour (FR-023).</li>
 * </ol>
 *
 * <h2>No rules, deliberately</h2>
 * The whole {@code io.activej.jsonrpc.schema} package is synchronous and reactor-free — a property
 * {@code ModuleBoundaryTest} establishes by scanning {@code src/main} as text. Nothing here allocates
 * a {@code ByteBuf} or drives a {@code Promise}, so an {@code EventloopRule} or a {@code ByteBufRule}
 * here would assert nothing and would be the first line suggesting otherwise.
 */
public final class JsonSchemaMappingAdversarialTest {

	// ---------------------------------------------------------------------------------------------
	// (a) Cycles the direct self-reference does not cover.
	//
	// JsonSchemaMappingTest pins `record Node(String value, Node next)` - one hop, one component, one
	// answer. Every cycle below is longer, indirect, or reached through a container: the shapes where a
	// guard written for the one-hop case silently stops working, and where the failure mode is a
	// StackOverflowError at build() rather than a wrong document.
	//
	// Two different answers live in this section, and the difference is the point. A cycle closing on a
	// bare component collapses the record; a cycle closing inside a container does not, because the
	// container describes itself and marks its element. Both terminate, which is the property that
	// actually matters.
	// ---------------------------------------------------------------------------------------------

	public record MutualA(String label, MutualB b) {}

	public record MutualB(int size, MutualA a) {}

	public record RingA(RingB b) {}

	public record RingB(RingC c) {}

	public record RingC(RingA a) {}

	/** The cycle hides inside a {@code List}, not in a component's own type. */
	public record Tree(String name, List<Tree> children) {}

	/** The cycle hides inside a reference array. */
	public record Chain(String name, Chain[] next) {}

	/** The cycle hides inside a {@code Map} value. */
	public record Registry(String name, Map<String, Registry> entries) {}

	/** The cycle hides two container levels down. */
	public record Grid(String name, List<List<Grid>> grid) {}

	/** Reaches a self-referential record from the outside, without being part of the cycle. */
	public record Forest(Tree tree) {}

	/** One clean component, one cyclic one: the record falls back <b>as a whole</b> all the same. */
	public record HalfClean(int ordinal, HalfClean parent) {}

	public record Leaf(String text) {}

	public record Wrapper(Leaf leaf) {}

	/**
	 * The same record type reached at two different depths of two <i>sibling</i> branches. Not a cycle
	 * by any reading, and the guard must not mistake it for one — which it cannot, because the path set
	 * is copied per component rather than mutated.
	 */
	public record Fork(Leaf direct, Wrapper indirect) {}

	@Test
	public void twoRecordsThatReferToEachOtherBothFallBack() {
		// the guard has to survive a cycle it only closes on the second hop. Both entry points are
		// asserted: a guard that happened to work from one end and recursed forever from the other would
		// pass a single-direction test and hang the build on the day someone declared the pair the other
		// way round
		assertFallback(MutualA.class);
		assertFallback(MutualB.class);
	}

	@Test
	public void aThreeRecordRingFallsBackFromEveryEntryPoint() {
		assertFallback(RingA.class);
		assertFallback(RingB.class);
		assertFallback(RingC.class);
	}

	@Test
	public void aCycleClosingInsideAContainerIsAbsorbedByThatContainersOwnMarker() {
		// CHARACTERIZATION, and the one place where this class's findings touch the shipped Javadoc.
		//
		// JsonSchemaMapping's class documentation states rule 2 as "A recursive record falls back". That is
		// exact for a cycle closing on a bare component - `record Node(String, Node)` is the boolean, and
		// JsonSchemaMappingTest pins it - but it is over-broad for a cycle whose back-edge sits inside a
		// container, which is what every self-referential shape in real service code looks like: a tree of
		// children, a graph of neighbours, a map of sub-registries.
		//
		// What actually happens is rules 1 and 2 composing, and the composition is the right answer:
		//   - the back-edge maps to the fallback, exactly as rule 2 says;
		//   - its enclosing CONTAINER is described and its element visibly marked, exactly as rule 1's
		//     "a container is different" clause says, because the array-ness IS completely described;
		//   - the container fragment is therefore a described `Typed`, so the record's all-components-
		//     described check passes and the record is emitted in full.
		//
		// The emitted document is honest under FR-022: `children` is an array whose element is explicitly
		// undescribed. Collapsing the whole record to `true` instead would tell a consumer strictly less
		// while claiming no more. The Javadoc sentence was tightened to say this; the behaviour was not
		// touched, and this test is what stops it drifting either way.
		assertJson("{\"type\":\"object\",\"properties\":{\"name\":{\"type\":\"string\"}," +
				   "\"children\":{\"type\":\"array\",\"items\":true}},\"required\":[\"name\",\"children\"]}",
			Tree.class);
		assertJson("{\"type\":\"object\",\"properties\":{\"name\":{\"type\":\"string\"}," +
				   "\"next\":{\"type\":\"array\",\"items\":true}},\"required\":[\"name\",\"next\"]}",
			Chain.class);
		assertJson("{\"type\":\"object\",\"properties\":{\"name\":{\"type\":\"string\"}," +
				   "\"entries\":{\"type\":\"object\",\"additionalProperties\":true}},\"required\":[\"name\",\"entries\"]}",
			Registry.class);

		// the contrast, on one line: the same cycle with no container between the record and itself
		assertFallback(HalfClean.class);
	}

	@Test
	public void theMarkerLandsOnTheInnermostContainerAndNoHigher() {
		// a two-level container over the back-edge: both levels of array-ness are described and only the
		// element is marked. A guard that propagated the fallback outward by one level - or that gave up
		// at the first container it could not fully resolve - produces a different, weaker document
		assertJson("{\"type\":\"object\",\"properties\":{\"name\":{\"type\":\"string\"}," +
				   "\"grid\":{\"type\":\"array\",\"items\":{\"type\":\"array\",\"items\":true}}}," +
				   "\"required\":[\"name\",\"grid\"]}", Grid.class);

		// and a record REACHING a self-referential record describes it exactly as it describes itself:
		// the path is per-branch, so entering Tree from outside changes nothing about Tree
		Typed forest = typed(JsonSchemaMapping.map(Forest.class));
		assertEquals(JsonSchemaMapping.map(Tree.class), forest.properties().get("tree"));
	}

	@Test
	public void oneCyclicComponentCollapsesTheWholeRecordAndNotJustThatMember() {
		// the FR-022 rule that makes a partially-described object forbidden: `ordinal` is a perfectly
		// describable int, and the answer is still the bare boolean
		assertFallback(HalfClean.class);
	}

	@Test
	public void theSameRecordOnTwoSiblingBranchesIsNotACycle() {
		Typed fork = typed(JsonSchemaMapping.map(Fork.class));

		assertEquals(List.of("direct", "indirect"), List.copyOf(fork.properties().keySet()));
		// both branches resolved in full - the path is per-branch, so `direct` does not poison `indirect`
		assertEquals(JsonSchemaMapping.map(Leaf.class), fork.properties().get("direct"));
		assertEquals(JsonSchemaMapping.map(Wrapper.class), fork.properties().get("indirect"));
		assertEquals(JsonSchemaMapping.map(Leaf.class),
			typed(fork.properties().get("indirect")).properties().get("leaf"));
	}

	// ---------------------------------------------------------------------------------------------
	// (b) The cycle guard is keyed by raw Class. CHARACTERIZATION - the pinned subset does not say
	// what happens to a generic record nested inside itself, and this is what happens.
	// ---------------------------------------------------------------------------------------------

	public record Wrap<T>(String label, T value) {}

	/**
	 * <b>Polymorphic recursion</b>: every level binds {@code T} one {@code List} deeper, so the chain of
	 * <i>bound types</i> {@code Weird<String>}, {@code Weird<List<String>>},
	 * {@code Weird<List<List<String>>>}… never repeats. Only the raw class does.
	 */
	public record Weird<T>(T head, Weird<List<T>> next) {}

	@Test
	public void aGenericRecordNestedInItselfIsRefusedAsACycleThoughItIsNotOne() {
		// Wrap<String> is described in full ...
		assertJson("{\"type\":\"object\",\"properties\":{\"label\":{\"type\":\"string\"}," +
				   "\"value\":{\"type\":\"string\"}},\"required\":[\"label\",\"value\"]}",
			Types.parameterizedType(Wrap.class, String.class));

		// ... and Wrap<Wrap<String>> is not, though it is finite, acyclic and every leaf of it is inside
		// the subset. The guard is a Set<Class<?>>, and the raw class Wrap is on the path when the inner
		// Wrap is reached.
		assertFallback(Types.parameterizedType(Wrap.class, Types.parameterizedType(Wrap.class, String.class)));

		// This is a conservative answer, not a wrong one: `true` is a valid draft-07 schema that
		// constrains nothing, which is exactly FR-022's marked-partial contract. A consumer is told the
		// shape is not described, never told something false about it.
	}

	@Test
	public void keyingTheGuardOnTheBoundTypeInsteadWouldNotTerminate() {
		// The reason the conservatism above is not worth removing. A guard keyed on the BOUND type -
		// which would describe Wrap<Wrap<String>> correctly - never closes on this record: the bound type
		// grows one List per level and no two levels are equal. The raw-Class guard closes it at depth
		// two, and the whole thing is the honest boolean.
		assertFallback(Types.parameterizedType(Weird.class, String.class));

		// and it closes just as fast whatever the leaf is
		assertFallback(Types.parameterizedType(Weird.class, Types.parameterizedType(List.class, Integer.class)));
	}

	// ---------------------------------------------------------------------------------------------
	// (c) Depth. Deep is not the same as cyclic, and the walk must not confuse the two.
	// ---------------------------------------------------------------------------------------------

	@Test
	public void aFiftyDeepChainOfDistinctRecordsResolvesInFullToItsLeaf() throws MalformedDataException {
		JsonSchemaFragment root = JsonSchemaMapping.map(ScaleTypes.L49.class);

		// walk the whole nest: every level must be an object with exactly one required property `next`,
		// except the leaf, which carries `leaf: string`
		JsonSchemaFragment cursor = root;
		for (int level = ScaleTypes.CHAIN_DEPTH - 1; level > 0; level--) {
			Typed typed = typed(cursor);
			assertEquals("level " + level, "object", typed.type());
			assertEquals("level " + level, List.of("next"), List.copyOf(typed.properties().keySet()));
			assertEquals("level " + level, List.of("next"), typed.required());
			cursor = typed.properties().get("next");
		}
		assertEquals("{\"type\":\"object\",\"properties\":{\"leaf\":{\"type\":\"string\"}},\"required\":[\"leaf\"]}",
			write(cursor));

		// and the whole thing serialises and reads back equal at that depth - the codec recurses through
		// `properties` exactly as the walk did
		String json = write(root);
		assertEquals(json, root, JsonUtils.fromJson(JsonSchemaMapping.SCHEMA_CODEC, json));
	}

	@Test
	public void theWalkHasNoDepthBoundAndTheWireLevelMaxJsonDepthDoesNotGovernIt() throws MalformedDataException {
		// THE RESIDUAL, pinned rather than omitted. JsonRpcLimits.MAX_JSON_DEPTH bounds a document
		// arriving from a peer, by a pre-parse scan, because that input is hostile. This walk's input is
		// the application's own declared types - fixed at compile time, read once at build() - so it has
		// no bound at all, and a type nested deeply enough overflows the stack instead of being refused.
		// Nothing here asserts where that happens: the threshold is a function of -Xss and of the JVM's
		// frame layout, and an assertion about it would be a flake, not a contract.
		int depth = 4 * JsonRpcLimits.MAX_JSON_DEPTH;
		assertTrue("the fixture must be deeper than the wire-level bound", depth > JsonRpcLimits.MAX_JSON_DEPTH);

		Type nested = String.class;
		for (int i = 0; i < depth; i++) nested = Types.parameterizedType(List.class, nested);

		JsonSchemaFragment fragment = JsonSchemaMapping.map(nested);
		int measured = 0;
		JsonSchemaFragment cursor = fragment;
		while (cursor instanceof Typed typed && typed.items() != null) {
			measured++;
			cursor = typed.items();
		}
		assertEquals(depth, measured);
		assertEquals("{\"type\":\"string\"}", write(cursor));

		String json = write(fragment);
		assertEquals(fragment, JsonUtils.fromJson(JsonSchemaMapping.SCHEMA_CODEC, json));
	}

	// ---------------------------------------------------------------------------------------------
	// (d) Scale. Nothing here is a benchmark - the bounds are loose enough that only a change of
	// COMPLEXITY class trips them, which is the only thing a test can honestly assert about time.
	// ---------------------------------------------------------------------------------------------

	@Test
	public void aThousandConstantEnumKeepsDeclarationOrderAndIsNotSorted() {
		Typed typed = typed(JsonSchemaMapping.map(ScaleTypes.Thousand.class));

		assertEquals("string", typed.type());
		assertEquals(ScaleTypes.CONSTANT_COUNT, typed.enumValues().size());

		// the fixture is declared in DESCENDING order precisely so that "declaration order" and "any
		// order a container might impose" cannot agree by accident
		List<String> expected = new ArrayList<>(ScaleTypes.CONSTANT_COUNT);
		for (int n = ScaleTypes.CONSTANT_COUNT - 1; n >= 0; n--) expected.add(String.format("C%04d", n));
		assertEquals(expected, typed.enumValues());

		List<String> sorted = typed.enumValues().stream().sorted().toList();
		assertNotEquals("the fixture stopped being adversarial: its declaration order IS sorted order",
			sorted, typed.enumValues());

		// and the emitted array is that same order, byte for byte
		String json = write(typed);
		assertTrue(json, json.startsWith("{\"type\":\"string\",\"enum\":[\"C0999\",\"C0998\","));
		assertTrue(json, json.endsWith("\"C0001\",\"C0000\"]}"));
	}

	@Test
	public void aTwoHundredComponentRecordKeepsCanonicalConstructorOrderInBothMembers() {
		Typed typed = typed(JsonSchemaMapping.map(ScaleTypes.Wide.class));

		assertEquals(ScaleTypes.COMPONENT_COUNT, typed.properties().size());
		assertEquals(ScaleTypes.COMPONENT_COUNT, typed.required().size());
		// `required` is not merely the same SET as `properties`: it is the same ORDER, because both are
		// the canonical-constructor order and the frozen comparison is byte-exact (rule M10)
		assertEquals(List.copyOf(typed.properties().keySet()), typed.required());

		List<String> expected = new ArrayList<>(ScaleTypes.COMPONENT_COUNT);
		for (int n = ScaleTypes.COMPONENT_COUNT - 1; n >= 0; n--) expected.add(String.format("c%03d", n));
		assertEquals(expected, typed.required());

		// the four scalar rows the fixture cycles through, each landing where it was declared
		assertEquals(Typed.of("string"), typed.properties().get("c199"));    // Character
		assertEquals(Typed.of("boolean"), typed.properties().get("c198"));   // boolean
		assertEquals(Typed.of("integer"), typed.properties().get("c197"));   // int
		assertEquals(Typed.of("string"), typed.properties().get("c196"));    // String
	}

	@Test
	public void mappingTheScaleShapesIsCheapEnoughThatOnlyAComplexityChangeCouldTripThis() {
		// a deliberately loose ceiling: the three shapes together map in single-digit milliseconds on any
		// machine that can run this suite at all. It exists to catch an accidental quadratic - a cache
		// keyed on something that re-walks, a defensive copy per level - not to measure anything
		long start = System.nanoTime();
		for (int i = 0; i < 20; i++) {
			write(JsonSchemaMapping.map(ScaleTypes.Thousand.class));
			write(JsonSchemaMapping.map(ScaleTypes.Wide.class));
			write(JsonSchemaMapping.map(ScaleTypes.L49.class));
		}
		long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

		assertTrue("20 rounds over the scale fixtures took " + elapsedMillis + "ms", elapsedMillis < 10_000);
	}

	// ---------------------------------------------------------------------------------------------
	// (e) The fallback row, beyond the three types the existing suite names.
	// ---------------------------------------------------------------------------------------------

	@Test
	public void aTypeThatEncodesPerfectlyIsStillUndescribedWhenTheSubsetHasNoRowForIt() {
		// The boundary FR-023 draws, stated as two assertions side by side rather than as a comment: the
		// codec factory RESOLVES every one of these - a service may take a Duration parameter and it will
		// travel the wire correctly - and the schema for every one of them is the bare boolean, because
		// the pinned subset has no row that describes their JSON shape without inventing one.
		JsonCodecFactory factory = JsonCodecFactory.defaultInstance();
		for (Class<?> type : List.of(Duration.class, LocalDate.class, LocalDateTime.class, LocalTime.class,
			UUID.class, Instant.class)
		) {
			JsonCodec<?> codec = factory.resolve(type);
			assertNotNull(type + " must still be encodable: this test's whole point is that it is", codec);
			assertFallback(type);
		}
	}

	@Test
	public void typesWithNoRowAndNoCodecEitherFallBackJustTheSame() {
		// totality does not depend on the codec factory having an opinion - the mapping never asks it, and
		// nothing below resolves to a codec either
		assertFallback(Period.class);
		assertFallback(URI.class);
		assertFallback(Class.class);
		assertFallback(StringBuilder.class);
		assertFallback(Runnable.class);
		assertFallback(Thread.class);
		assertFallback(Set.class);            // raw, like the raw List/Map already pinned
		assertFallback(new TypeT<Iterable<String>>() {}.getType());   // a container the subset has no row for
	}

	@Test
	public void aNestedPrimitiveArrayDescribesItsOuterDimensionAndMarksTheInnerOne() {
		// int[] is the fallback (already pinned) because base64-versus-numeric is undecided. int[][] is
		// NOT the fallback: its outer array-ness is completely described, and the element - the int[] -
		// is visibly marked, which is the container rule, not the record rule
		assertJson("{\"type\":\"array\",\"items\":true}", int[][].class);
		assertJson("{\"type\":\"array\",\"items\":{\"type\":\"array\",\"items\":true}}", int[][][].class);

		// the reference-array counterpart resolves all the way down, at any dimension
		assertJson("{\"type\":\"array\",\"items\":{\"type\":\"array\",\"items\":{\"type\":\"array\"," +
				   "\"items\":{\"type\":\"string\"}}}}", String[][][].class);
	}

	@Test
	public void aWildcardAnywhereIsAnUnboundTypeAndIsMarkedWhereItStands() {
		// a wildcard cannot be described: there is no single shape it names. Inside a container it marks
		// the element; inside a record it collapses the record (FR-022's two halves, on one input each)
		assertJson("{\"type\":\"object\",\"additionalProperties\":true}",
			Types.parameterizedType(Map.class, String.class, Types.wildcardTypeAny()));
		assertJson("{\"type\":\"array\",\"items\":true}",
			Types.parameterizedType(List.class, Types.wildcardTypeExtends(Number.class)));
		assertJson("{\"type\":\"array\",\"items\":true}",
			Types.parameterizedType(Set.class, Types.wildcardTypeSuper(BigDecimal.class)));

		assertFallback(Types.parameterizedType(Wrap.class, Types.wildcardTypeAny()));
	}

	@Test
	public void aRawGenericRecordFallsBackRatherThanThrowing() {
		// Wrap.class with no type arguments: the component `value` is an unbound T, and binding it throws
		// inside io.activej.types. mapRecord catches it, because being unable to describe something is a
		// documented outcome of this class and never a failure (FR-022)
		assertFallback(Wrap.class);
		assertFallback(Weird.class);
	}

	// ---------------------------------------------------------------------------------------------
	// (f) Hostile Type implementations. Nothing in this feature constructs these - JsonRpcParamDescriptor
	// carries types that came out of java.lang.reflect - but `map` is public, total and takes an
	// interface anyone can implement, and "total" has to mean total.
	// ---------------------------------------------------------------------------------------------

	@Test
	public void aTypeOfNoneOfTheFourKindsIsTheFallback() {
		Type foreign = new Type() {
			@Override
			public String getTypeName() {
				return "a type from some other reflection universe";
			}
		};

		assertSame(Any.INSTANCE, JsonSchemaMapping.map(foreign));
	}

	@Test
	public void aParameterizedTypeWhoseRawTypeIsNotAClassIsTheFallback() {
		// getRawType() is declared to return Type, not Class - so a conforming implementation may hand
		// back a TypeVariable, and the walk must not cast blindly
		Type hostile = Types.parameterizedType(null, Wrap.class.getTypeParameters()[0], new Type[]{String.class});

		assertSame(Any.INSTANCE, JsonSchemaMapping.map(hostile));
	}

	@Test
	public void aNullTypeArgumentIsMarkedRatherThanThrown() {
		// map(null) at the top level is a programmer error and throws (already pinned). A null NESTED
		// inside a hostile container is not the caller's mistake to catch, and the walk answers it the
		// way it answers anything it cannot describe.
		//
		// Note the assertions do not go through assertJson: rendering these types for a failure message
		// is itself a NullPointerException inside io.activej.types, which says something about how far
		// outside the contract they are - and about why `map` is the wrong place to be strict
		assertEquals("{\"type\":\"array\",\"items\":true}",
			write(JsonSchemaMapping.map(Types.parameterizedType(List.class, new Type[]{null}))));
		assertEquals("{\"type\":\"object\",\"additionalProperties\":true}",
			write(JsonSchemaMapping.map(Types.parameterizedType(Map.class, new Type[]{String.class, null}))));
	}

	@Test
	public void aRecordParameterizedWithTheWrongNumberOfArgumentsFallsBack() {
		// Wrap declares one type parameter; this hands it two. io.activej.types throws walking the
		// mismatch, and mapRecord's catch turns that into the documented fallback instead of propagating
		// a reflection error out of build()
		assertFallback(Types.parameterizedType(Wrap.class, String.class, String.class));
		assertFallback(Types.parameterizedType(Wrap.class, new Type[0]));
	}

	@Test
	public void aGenericArrayOfAPrimitiveDescribesItsElementWhereTheClassFormWouldNot() {
		// CHARACTERIZATION of an asymmetry, and of the fact that it is unreachable.
		//
		// int[].class is the fallback: mapClass refuses a primitive component, mirroring JsonCodecFactory.
		// A GenericArrayType whose component is int.class takes the other branch and describes it. Real
		// reflection never produces that type - a Java type variable cannot bind to a primitive, so `T[]`
		// bound is always an array of a reference - and JsonRpcParamDescriptor only ever carries types
		// java.lang.reflect produced. It is pinned so that if a future binding step ever DOES synthesise
		// one, the difference is a failing test rather than two rows of the mapping table disagreeing.
		assertFallback(int[].class);
		assertJson("{\"type\":\"array\",\"items\":{\"type\":\"integer\"}}", Types.genericArrayType(int.class));

		// the reference case agrees with the Class form, as it must
		assertEquals(JsonSchemaMapping.map(String[].class),
			JsonSchemaMapping.map(Types.genericArrayType(String.class)));
	}

	// ---------------------------------------------------------------------------------------------
	// (g) Enums that are not the textbook shape.
	// ---------------------------------------------------------------------------------------------

	/** Every constant has a class body, so every constant's {@code getClass()} is an anonymous subclass. */
	public enum Bodied {
		CIRCLE {
			@Override
			public int sides() {return 0;}
		},
		TRIANGLE {
			@Override
			public int sides() {return 3;}
		};

		public abstract int sides();
	}

	/** Legal Java, and a schema that admits nothing — which is exactly what it should admit. */
	public enum Uninhabited {}

	@Test
	public void anEnumConstantsAnonymousSubclassDescribesTheEnumItBelongsTo() {
		// isEnum() is false for the anonymous subclass a constant body compiles to. A DECLARED type is
		// never one of those, so this branch is reachable only by a caller passing `SOME.CONSTANT
		// .getClass()` - and it answers with the enum, not with the fallback
		assertFalse(Bodied.CIRCLE.getClass().isEnum());
		assertNotEquals(Bodied.class, Bodied.CIRCLE.getClass());

		assertJson("{\"type\":\"string\",\"enum\":[\"CIRCLE\",\"TRIANGLE\"]}", Bodied.class);
		assertJson("{\"type\":\"string\",\"enum\":[\"CIRCLE\",\"TRIANGLE\"]}", Bodied.CIRCLE.getClass());
		assertEquals(JsonSchemaMapping.map(Bodied.class), JsonSchemaMapping.map(Bodied.TRIANGLE.getClass()));
	}

	@Test
	public void anEnumWithNoConstantsEmitsAnEmptyEnumArray() {
		// CHARACTERIZATION. draft-07 §6.1.2 says an `enum` array SHOULD have at least one element, and
		// this one has none - but the schema is exactly right: an uninhabited enum admits no value, and
		// {"type":"string","enum":[]} is the schema that validates nothing. Emitting `true` instead would
		// claim the opposite of the truth, and emitting nothing would claim any string is acceptable
		assertJson("{\"type\":\"string\",\"enum\":[]}", Uninhabited.class);
		assertEquals(List.of(), typed(JsonSchemaMapping.map(Uninhabited.class)).enumValues());
	}

	// ---------------------------------------------------------------------------------------------
	// (h) A record that is also a container. The dispatch order is load-bearing, so it is asserted
	// rather than trusted to the comment that documents it.
	// ---------------------------------------------------------------------------------------------

	/** A {@code record} that is also a {@link Set}. The mapping must choose one row, and it chooses the record. */
	public record Bag<T>(Set<T> delegate) implements Set<T> {
		@Override public int size() {return delegate.size();}

		@Override public boolean isEmpty() {return delegate.isEmpty();}

		@Override public boolean contains(Object o) {return delegate.contains(o);}

		@Override public Iterator<T> iterator() {return delegate.iterator();}

		@Override public Object[] toArray() {return delegate.toArray();}

		@Override public <U> U[] toArray(U[] a) {return delegate.toArray(a);}

		@Override public boolean add(T t) {return delegate.add(t);}

		@Override public boolean remove(Object o) {return delegate.remove(o);}

		@Override public boolean containsAll(Collection<?> c) {return delegate.containsAll(c);}

		@Override public boolean addAll(Collection<? extends T> c) {return delegate.addAll(c);}

		@Override public boolean retainAll(Collection<?> c) {return delegate.retainAll(c);}

		@Override public boolean removeAll(Collection<?> c) {return delegate.removeAll(c);}

		@Override public void clear() {delegate.clear();}
	}

	@Test
	public void aRecordThatIsAlsoASetDescribesItselfByItsComponents() {
		// mirroring JsonCodecFactory's Record-before-List/Set/Map registration order, so a type describes
		// itself the way it encodes itself. The array row would have been just as "valid" a schema and
		// would have described the wrong document
		assertJson("{\"type\":\"object\",\"properties\":{\"delegate\":{\"type\":\"array\"," +
				   "\"items\":{\"type\":\"string\"}}},\"required\":[\"delegate\"]}",
			Types.parameterizedType(Bag.class, String.class));

		Typed typed = typed(JsonSchemaMapping.map(Types.parameterizedType(Bag.class, String.class)));
		assertEquals("object", typed.type());
		assertNull("described as an array, the record's own component set would be lost", typed.items());
	}

	// ---------------------------------------------------------------------------------------------

	private static void assertJson(String expected, Type type) {
		JsonSchemaFragment fragment = JsonSchemaMapping.map(type);
		assertTrue("expected a described fragment for " + type + ", got the fallback",
			fragment instanceof Typed);
		assertEquals(type.getTypeName(), expected, write(fragment));
	}

	private static void assertFallback(Type type) {
		JsonSchemaFragment fragment = JsonSchemaMapping.map(type);
		if (!(fragment instanceof Any)) fail("expected the boolean-true fallback for " + type + ", got " + write(fragment));
		assertSame(Any.INSTANCE, fragment);
		assertEquals("true", write(fragment));
	}

	private static Typed typed(JsonSchemaFragment fragment) {
		assertTrue("expected a described fragment, got " + fragment, fragment instanceof Typed);
		return (Typed) fragment;
	}

	private static String write(JsonSchemaFragment fragment) {
		return JsonUtils.toJson(JsonSchemaMapping.SCHEMA_CODEC, fragment);
	}
}
