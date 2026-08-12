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

import io.activej.json.annotations.JsonNullable;
import io.activej.types.AnnotatedTypes;
import io.activej.types.TypeT;
import org.junit.Test;

import java.lang.reflect.AnnotatedType;
import java.lang.reflect.TypeVariable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.Assert.*;

/**
 * Pins the memo that makes derivation terminate on a recursive type graph and that keeps two
 * resolutions which need different codecs on different entries.
 * <p>
 * Bare JUnit 4 by design: {@code activej-test} is not on this module's classpath, and nothing here
 * touches a {@code ByteBuf}, a {@code Promise} or a reactor, so no rule from
 * {@code io.activej.test.rules} is applicable.
 */
public class DerivationCacheTest {

	record Box<T>(T value) {}

	record Leaf(String s) {}

	/** Only used to obtain a {@code Box<T>} annotated type whose {@code T} can be bound. */
	record Holder<T>(Box<T> boxed) {}

	@Test
	public void sameKeyDerivesOnce() {
		DerivationCache cache = new DerivationCache();
		AtomicInteger derivations = new AtomicInteger();
		Supplier<JsonCodec<?>> derivation = counting(derivations, JsonCodecs.ofString());

		DerivationCache.CacheKey key = DerivationCache.keyOf(AnnotatedTypes.annotatedTypeOf(Leaf.class));

		JsonCodec<?> first = cache.lookupOrDerive(key, derivation);
		JsonCodec<?> second = cache.lookupOrDerive(key, derivation);

		assertEquals(1, derivations.get());
		assertSame(first, second);
		assertEquals(1, cache.size());
	}

	/**
	 * The memo-hit proof. A key built from a user's {@link TypeT} carries the <b>JDK</b>'s
	 * {@code ParameterizedTypeImpl}; a key built from {@link AnnotatedTypes#bind} — the route every
	 * derived component takes — carries ActiveJ's {@code Types.ParameterizedTypeImpl}. The two must
	 * land on one entry, or a generic record would derive twice per type.
	 */
	@Test
	public void structurallyEqualKeysCollide() {
		DerivationCache.CacheKey fromTypeT = DerivationCache.keyOf(new TypeT<Box<String>>() {}.getAnnotatedType());
		DerivationCache.CacheKey fromBind = DerivationCache.keyOf(boxOfString());

		assertEquals(fromTypeT, fromBind);
		assertEquals(fromTypeT.hashCode(), fromBind.hashCode());
	}

	/**
	 * FR-042. This cannot be satisfied by the {@code Type} alone: annotations do not live on a
	 * {@code Type}, and {@code Box<@JsonNullable String>} and {@code Box<String>} resolve to one and
	 * the same {@code Box<String>} {@code Type}.
	 */
	@Test
	public void annotatedTypeArgumentsProduceDistinctEntries() {
		AnnotatedType nullableArgument = new TypeT<Box<@JsonNullable String>>() {}.getAnnotatedType();
		AnnotatedType plainArgument = new TypeT<Box<String>>() {}.getAnnotatedType();

		assertEquals(nullableArgument.getType(), plainArgument.getType());

		DerivationCache.CacheKey nullableKey = DerivationCache.keyOf(nullableArgument);
		DerivationCache.CacheKey plainKey = DerivationCache.keyOf(plainArgument);

		assertNotEquals(nullableKey, plainKey);

		DerivationCache cache = new DerivationCache();
		AtomicInteger derivations = new AtomicInteger();
		cache.lookupOrDerive(nullableKey, counting(derivations, JsonCodecs.ofString()));
		cache.lookupOrDerive(plainKey, counting(derivations, JsonCodecs.ofString()));

		assertEquals(2, derivations.get());
		assertEquals(2, cache.size());
	}

	/**
	 * FR-015/FR-008 — the recursion break. A derivation that re-enters the cache for the key it is
	 * itself deriving must get a usable handle back rather than deadlock, throw, or re-derive.
	 */
	@Test
	public void reentrantLookupReturnsAUsablePlaceholder() {
		DerivationCache cache = new DerivationCache();
		DerivationCache.CacheKey key = DerivationCache.keyOf(AnnotatedTypes.annotatedTypeOf(Leaf.class));

		AtomicInteger failingSupplierCalls = new AtomicInteger();
		List<JsonCodec<?>> reentrant = new ArrayList<>();

		JsonCodec<?> outer = cache.lookupOrDerive(key, () -> {
			reentrant.add(cache.lookupOrDerive(key, () -> {
				failingSupplierCalls.incrementAndGet();
				throw new AssertionError("the re-entrant lookup must not derive again");
			}));
			return JsonCodecs.ofString();
		});

		assertEquals(0, failingSupplierCalls.get());
		assertEquals(1, reentrant.size());
		assertNotNull(reentrant.get(0));

		// the placeholder's delegate is set once the recursion has returned, so it is usable now
		assertEquals(json(outer, "x"), json(reentrant.get(0), "x"));
	}

	/**
	 * FR-014. The assertion is "every codec handed to a caller is usable <b>at the moment it is
	 * handed over</b>", not "the map holds one entry": a design where a losing thread derives its own
	 * equal copy is correct, while a design that hands a second thread an unfilled placeholder is
	 * not, and only the usability assertion separates the two.
	 * <p>
	 * Mildly timing-dependent by construction — a pass is weak evidence, a failure is a real defect.
	 */
	@Test
	public void concurrentResolutionIsSafe() throws Exception {
		for (int iteration = 0; iteration < 20; iteration++) {
			DerivationCache cache = new DerivationCache();
			DerivationCache.CacheKey key = DerivationCache.keyOf(AnnotatedTypes.annotatedTypeOf(Leaf.class));

			int threadCount = 8;
			CyclicBarrier barrier = new CyclicBarrier(threadCount);
			List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());
			List<String> encodings = Collections.synchronizedList(new ArrayList<>());
			List<Thread> threads = new ArrayList<>(threadCount);

			for (int i = 0; i < threadCount; i++) {
				Thread thread = new Thread(() -> {
					try {
						barrier.await();
						JsonCodec<?> codec = cache.lookupOrDerive(key, () -> {
							try {
								Thread.sleep(20);
							} catch (InterruptedException e) {
								Thread.currentThread().interrupt();
								throw new AssertionError(e);
							}
							return JsonCodecs.ofString();
						});
						encodings.add(json(codec, "x"));
					} catch (Throwable e) {
						failures.add(e);
					}
				});
				threads.add(thread);
				thread.start();
			}
			for (Thread thread : threads) thread.join();

			assertEquals(List.of(), failures);
			assertEquals(threadCount, encodings.size());
			for (String encoding : encodings) assertEquals("\"x\"", encoding);
		}
	}

	private static Supplier<JsonCodec<?>> counting(AtomicInteger counter, JsonCodec<?> codec) {
		return () -> {
			counter.incrementAndGet();
			return codec;
		};
	}

	private static String json(JsonCodec<?> codec, String value) {
		//noinspection unchecked
		return JsonUtils.toJson((JsonCodec<String>) codec, value);
	}

	/**
	 * {@code Box<String>} carried by ActiveJ's own {@code Types.ParameterizedTypeImpl}, produced the
	 * way a derived component is: {@code bind} over a component's annotated type.
	 */
	private static AnnotatedType boxOfString() {
		AnnotatedType boxOfT = Holder.class.getRecordComponents()[0].getAnnotatedType();
		TypeVariable<?> t = Holder.class.getTypeParameters()[0];
		Map<TypeVariable<?>, AnnotatedType> bindings = Map.of(t, AnnotatedTypes.annotatedTypeOf(String.class));
		return AnnotatedTypes.bind(boxOfT, bindings::get);
	}
}
