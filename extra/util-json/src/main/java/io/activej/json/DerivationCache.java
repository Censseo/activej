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

import com.dslplatform.json.JsonReader;
import com.dslplatform.json.JsonWriter;
import io.activej.types.AnnotatedTypes;

import static io.activej.common.Checks.checkNotNull;

import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * The per-{@link JsonCodecFactory} memo of derived codecs, keyed by a resolved type together with
 * every annotation on it and on its type arguments.
 *
 * <p><b>Why this exists.</b> It is not an optimization. Without it a self-referencing record —
 * {@code record Node(String v, List<Node> kids)} — raises a {@link StackOverflowError} during
 * derivation, because resolving {@code Node} resolves {@code List<Node>} which resolves
 * {@code Node} again. The measurement is recorded in {@code specs/011-record-codec-derivation}
 * research §0 q5. Terminating that recursion is what this class is for; not paying for a second
 * derivation is a side effect.
 *
 * <p><b>Retention.</b> Unbounded by design, bounded in fact by the application's compile-time type
 * set: one entry per resolved type for the owning factory's lifetime, and <b>no wire input can add
 * a key</b> — the key is a Java type, which a payload cannot influence. Nothing is evicted, because
 * there is nothing an adversary could make grow.
 *
 * <p><b>Classloader retention.</b> A {@link CacheKey#type} is a {@link Type}, which for any resolved
 * record holds a live reference to its {@code Class} and therefore to its defining
 * {@code ClassLoader}. A cache reached through {@link JsonCodecFactory#defaultInstance()} lives for
 * the JVM's lifetime, so resolving a record from a dynamically loaded classloader — a plugin, a
 * hot-redeployed module — through that shared instance pins the classloader for good. A host that
 * unloads classloaders at runtime should resolve those records through a scoped, non-static
 * {@link JsonCodecFactory} instead (one per deployment), so its cache — and everything it retains —
 * becomes collectible when that instance does.
 *
 * <p><b>Why the key is shaped this way.</b> A bare {@link Type} cannot serve, because annotations do
 * not live on it and {@code Box<@JsonNullable String>} and {@code Box<String>} share one
 * {@code Type} while needing different codecs; an {@link AnnotatedType} cannot serve either, because
 * {@code AnnotatedTypes.AnnotatedTypeImpl} declares neither {@code equals} nor {@code hashCode} and
 * is therefore identity-compared. So the key pairs the {@code Type} — whose implementations, the
 * JDK's and ActiveJ's {@code Types.ParameterizedTypeImpl}, are mutually {@code equals}-compatible
 * and hash identically — with a purpose-built value object over the annotations.
 *
 * <p><b>Failed derivations leave no trace.</b> Neither structure retains an entry for a key whose
 * derivation threw, so a caller that registers the missing codec and retries gets a clean
 * derivation on a factory that has already failed once.
 */
final class DerivationCache {
	/**
	 * Completed codecs only. In-progress placeholders are thread-confined — see
	 * {@link #lookupOrDerive}.
	 */
	private final ConcurrentHashMap<CacheKey, JsonCodec<?>> completed = new ConcurrentHashMap<>();

	/**
	 * Placeholders for derivations currently running <b>on this thread</b>. Deliberately a plain
	 * {@link HashMap}: it is confined by construction, and a concurrent map here would suggest to a
	 * reader that it is shared.
	 */
	private final ThreadLocal<Map<CacheKey, PlaceholderJsonCodec<?>>> inProgress = ThreadLocal.withInitial(HashMap::new);

	/**
	 * A memo key: the resolved type plus the annotations on it and on its type arguments.
	 *
	 * <p>Wildcard and type-variable bounds are deliberately outside the key —
	 * {@link AnnotatedTypes#getTypeArguments} does not walk them and no mapping in
	 * {@link JsonCodecFactory} reads them. After type-variable substitution no unbound variable
	 * survives into a key anyway.
	 *
	 * <p><b>Known cost, accepted:</b> because annotations are folded into the key, a self-referencing
	 * record whose recursive occurrence carries a type-use annotation the root {@code resolve(...)}
	 * call site lacks (e.g. {@code record Node(int v, @JsonNullable Node next)}) derives <i>twice</i>
	 * instead of once — the root's annotation-free key and the component's annotated key never match,
	 * so the in-progress recursion break at {@link #lookupOrDerive} misses on first re-entry. Output
	 * stays correct (the second derivation's own recursion terminates normally); the cost is one
	 * redundant memo entry per distinct annotation shape, paid once at eager resolve time. See
	 * {@link RecordJsonCodec#derive} for the full reasoning.
	 */
	record CacheKey(Type type, AnnotationsKey annotations) {}

	/**
	 * The annotation half of a {@link CacheKey}, as a tree mirroring the annotated type's own.
	 *
	 * <p>Annotation array order is declaration order and is <b>not</b> canonicalised: {@code @A @B
	 * String} and {@code @B @A String} key differently. That is a redundant entry, never a wrong
	 * codec, and it cannot break recursion since each key derives its own placeholder.
	 */
	record AnnotationsKey(List<Annotation> annotations, List<AnnotationsKey> arguments) {
		/**
		 * Recurses over a <b>syntactic</b> tree and therefore always terminates, even for a type
		 * that is itself recursive: {@code Node}'s annotated type is just the {@code Class}.
		 */
		static AnnotationsKey of(AnnotatedType annotatedType) {
			// covers AnnotatedParameterizedType (arguments) and AnnotatedArrayType (component) alike
			AnnotatedType[] arguments = AnnotatedTypes.getTypeArguments(annotatedType);
			List<AnnotationsKey> children = new ArrayList<>(arguments.length);
			for (AnnotatedType argument : arguments) children.add(of(argument));
			// List.of copies; AnnotatedTypeImpl.getAnnotations() hands out its internal array
			return new AnnotationsKey(List.of(annotatedType.getAnnotations()), List.copyOf(children));
		}
	}

	static CacheKey keyOf(AnnotatedType annotatedType) {
		return new CacheKey(annotatedType.getType(), AnnotationsKey.of(annotatedType));
	}

	/**
	 * A codec whose delegate is set after it has been published, so that a derivation which
	 * re-enters the cache for the key it is itself deriving gets a usable handle instead of
	 * recursing forever.
	 *
	 * <p>{@code ForwardingJsonCodec} cannot serve here: its {@code codec} field is {@code final} and
	 * assigned by the constructor, whereas a placeholder must exist <i>before</i> the codec it
	 * forwards to does.
	 */
	static final class PlaceholderJsonCodec<T> implements JsonCodec<T> {
		// volatile is load-bearing: a derived codec is an ordinary object consumers hand between
		// threads freely, and the ConcurrentHashMap publish only covers callers that reach it there
		private volatile JsonCodec<T> delegate;

		void setDelegate(JsonCodec<T> delegate) {
			this.delegate = delegate;
		}

		@Override
		public T read(JsonReader<?> reader) throws IOException {
			return delegate().read(reader);
		}

		@Override
		public void write(JsonWriter writer, T value) {
			delegate().write(writer, value);
		}

		private JsonCodec<T> delegate() {
			JsonCodec<T> delegate = this.delegate;
			// cannot happen on any path this module creates; kept because a bare NullPointerException
			// from inside dsl-json would be undiagnosable
			if (delegate == null) throw new IllegalStateException("Codec used before its derivation completed");
			return delegate;
		}
	}

	/**
	 * Returns the codec for {@code key}, deriving it if this is the first time it is asked for.
	 *
	 * <p>The placeholder is published with an explicit put <b>outside</b> any mapping function.
	 * Never nest {@link ConcurrentHashMap#computeIfAbsent} here or anywhere below: derivation
	 * recurses by construction, and a nested {@code computeIfAbsent} on one map throws
	 * {@code IllegalStateException("Recursive update")} or livelocks on Java 9+.
	 *
	 * <p>Two threads racing on one unseen type each build a codec; the first to finish wins the
	 * shared entry, both callers get a <i>working</i> codec and the loser's graph is garbage after
	 * use. The alternatives were worse: blocking the second thread on the first's placeholder
	 * deadlocks on mutually recursive types derived in opposite orders, and handing the second
	 * thread an unfilled placeholder hands out a codec that throws on first use.
	 */
	JsonCodec<?> lookupOrDerive(CacheKey key, Supplier<JsonCodec<?>> derivation) {
		JsonCodec<?> done = completed.get(key);
		if (done != null) return done;

		Map<CacheKey, PlaceholderJsonCodec<?>> pending = inProgress.get();
		PlaceholderJsonCodec<?> placeholder = pending.get(key);
		if (placeholder != null) return placeholder;                        // the recursion break

		placeholder = new PlaceholderJsonCodec<>();
		pending.put(key, placeholder);                                      // publish before recursing
		try {
			// checked here, not left to ConcurrentHashMap#putIfAbsent's own null rejection: a raw NPE
			// out of that call would leave the placeholder already set to a delegate that isn't there,
			// so later readers see a misleading "used before its derivation completed" instead of this
			JsonCodec<?> codec = checkNotNull(derivation.get(), "Derivation must not return null");
			//noinspection unchecked
			((PlaceholderJsonCodec<Object>) placeholder).setDelegate((JsonCodec<Object>) codec);
			completed.putIfAbsent(key, codec);                              // first publisher wins; never overwrite
			return codec;
		} finally {
			// must run when the derivation throws too, or a poisoned unfilled placeholder would be
			// handed to a later retry on this thread
			pending.remove(key);
			if (pending.isEmpty()) inProgress.remove();
		}
	}

	/** Completed entries. Test observability only. */
	int size() {
		return completed.size();
	}
}
