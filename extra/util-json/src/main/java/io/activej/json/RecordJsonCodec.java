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

import io.activej.types.AnnotatedTypes;
import io.activej.types.Types;
import io.activej.types.scanner.TypeScannerRegistry;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.*;
import java.util.Arrays;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * Derives an {@link ObjectJsonCodec} for a {@code record} by reflection; <b>it is not itself a
 * {@link JsonCodec}</b>, despite the name.
 *
 * <p>Reflection — {@link Method#invoke} for the accessors, {@link Constructor#newInstance} for the
 * canonical constructor — is the binder by decision, not by default: measured throughput does not
 * separate it from {@code LambdaMetafactory}, while construction cost separates them heavily in
 * reflection's favour, and reflection binds a non-public record with no caller {@code Lookup}.
 * No bytecode is generated anywhere in this class.
 *
 * <p>A compact constructor that rejects its arguments surfaces as a {@link JsonValidationException},
 * which is the module's documented type for a semantic decode failure and is what
 * {@link JsonUtils} wraps into a {@code MalformedDataException} at the boundary. That is a
 * placement choice, not a relaxation of the "decode behaviour is unchanged" requirement.
 */
@SuppressWarnings("unchecked")
final class RecordJsonCodec {

	private RecordJsonCodec() {}

	/**
	 * The derivation entry point: one memo lookup per resolved type, and the recursion break.
	 *
	 * <p>Derivation is entered once per (type + annotations) per factory, eagerly at resolve time and
	 * never per encode or decode. The memo is simultaneously what terminates a recursive type graph,
	 * what makes a repeated resolution derive once, and what keeps one factory's derivations out of
	 * another's.
	 *
	 * <p>Keyed on {@code ctx.getAnnotatedType()} — the resolved annotated type after the caller's
	 * substitution — so {@code Pair<String,Integer>} and {@code Pair<Integer,String>} are distinct
	 * entries, and so are {@code Box<@JsonNullable String>} and {@code Box<String>}. Root annotations
	 * are included even though {@code JsonCodecFactory.Builder.with} applies root
	 * {@code @JsonNullable}/{@code @JsonSubclasses} outside the mapping: excluding them would require
	 * arguing that no future mapping reads a root annotation, while including them costs at most one
	 * redundant entry holding the same bare derived codec.
	 *
	 * <p><b>The cache is an explicit parameter on purpose, and production must not capture one in the
	 * registered lambda.</b> {@code TypeScannerRegistry.copyOf} — which {@code rebuild()} uses —
	 * copies registry entries by reference, so a captured cache would make every rebuilt factory
	 * share {@code defaultInstance()}'s memo: a consumer who rebuilt with their own component codec
	 * could then be handed a codec memoized against the default one. The production hand-off is the
	 * scanner's context-value seam, {@code registry.scanner(context)} read back as
	 * {@code ctx.getContextValue()}. A test may safely capture a test-local cache.
	 *
	 * <p>Since the failure-reporting work the context value is a {@link DerivationContext} rather than
	 * a bare cache: it carries the same memo plus the path from the root record to the position being
	 * resolved. The hand-off rule above is unchanged and applies to the whole carrier.
	 */
	static JsonCodec<?> derive(TypeScannerRegistry.Context<JsonCodec<?>> ctx) {
		return derive(ctx, (DerivationContext) ctx.getContextValue());
	}

	/**
	 * The test-harness entry point, kept so that {@code RecordJsonCodecTest}'s registration compiles
	 * unchanged.
	 *
	 * <p>It <b>re-roots the path</b>: every record derived through it reports a path starting at
	 * itself, whatever it was reached from. That is harmless there because that class asserts no
	 * paths, and it must stay that way — a path assertion driven through this overload would be an
	 * assertion about the harness. Path assertions belong in {@code RecordDerivationFailureTest},
	 * which drives the shipped factory.
	 */
	static JsonCodec<?> derive(TypeScannerRegistry.Context<JsonCodec<?>> ctx, DerivationCache cache) {
		return derive(ctx, DerivationContext.root(cache));
	}

	private static JsonCodec<?> derive(TypeScannerRegistry.Context<JsonCodec<?>> ctx, DerivationContext dctx) {
		AnnotatedType resolved = ctx.getAnnotatedType();
		DerivationCache.CacheKey key = DerivationCache.keyOf(resolved);
		// everything hazardous lives inside lookupOrDerive: the placeholder is published before build
		// runs, outside any mapping function, and its delegate is set once build returns
		return dctx.cache().lookupOrDerive(key, () -> build(ctx, resolved, dctx));
	}

	/**
	 * What a derivation carries down into its components: the factory's memo, and the human-readable
	 * path from the root record to the position being resolved.
	 *
	 * <p>Immutable, and passed through the scanner's context-value seam rather than through a
	 * {@code ThreadLocal}: derivation is concurrent by contract, and a thread-local accumulator would
	 * have to be unwound on exactly the exception path this exists to serve. Passing it down instead
	 * gives correct scoping for free — siblings each derive their own child from the same base, the
	 * parent's context is never mutated, and nothing has to be popped when a subtree throws.
	 *
	 * <p>Each record frame knows exactly one step — its own component's name, plus {@code []} if that
	 * component is a sequence — so no frame ever needs to know its depth. {@code Context.push} and
	 * {@code pushArgument} copy the context value, which is what carries a step across the
	 * intermediate frames (the {@code List} mapping, the array mapping, the {@code @JsonSubclasses}
	 * wrapper) that know nothing about derivation.
	 *
	 * <p><b>The path must never enter {@link DerivationCache.CacheKey}.</b> That is not merely
	 * wasteful: the key is built from the annotated type alone, and if the path joined it, the second
	 * encounter of a self-referencing record would miss the memo, the recursion would never break, and
	 * {@code record Node(String v, List<Node> kids)} would {@code StackOverflowError} again. It would
	 * also break the once-per-type derivation guarantee.
	 *
	 * <p>The path is diagnostic only: nothing reads it except a failure message.
	 */
	record DerivationContext(DerivationCache cache, String path) {
		static DerivationContext root(DerivationCache cache) {return new DerivationContext(cache, "");}

		DerivationContext at(String path) {return new DerivationContext(cache, path);}
	}

	/**
	 * Derives the codec for the record named by {@code resolved}, resolving every component through
	 * the factory's own context.
	 *
	 * <p>Every component is scanned on its <b>annotated</b> type, and eagerly, before assembly: this
	 * is what carries {@code @JsonNullable}, {@code @JsonSubclasses} and hand-registered codecs into
	 * a derived record, and it is what makes derivation fail when the codec is resolved rather than
	 * on first use. Never {@code ctx.scan(Type)} — that routes through {@code annotatedTypeOf} and
	 * produces an annotated type with no annotations, silently dropping every component annotation.
	 * Never {@code ctx.scanTypeArgument(n)} either: that is for the current type's arguments, which
	 * a record's components are not.
	 *
	 * <p>The annotations themselves are applied by the wrapper {@code JsonCodecFactory.Builder.with}
	 * puts around every registered mapping — it reads {@code @JsonSubclasses} and
	 * {@code @JsonNullable} off the context and wraps whatever the mapping returns. Nothing in this
	 * class reads them, and nothing here should: a second mechanism would conflict with that one.
	 *
	 * <p>This method is also the <b>only</b> place a derivation failure is located and re-raised. The
	 * three {@code try} blocks below are the whole mechanism, and they are deliberately confined to a
	 * record frame: a type that is simply not registered and is not reached while deriving a record
	 * keeps failing with the untouched {@code Object.class} fallback's
	 * {@link UnsupportedOperationException}, so "not registered" and "deriving a record failed" stay
	 * two distinguishable signals. There is no {@code try} in {@code derive} — which is also the memo
	 * hit path — and none in {@code JsonCodecFactory}.
	 */
	static JsonCodec<?> build(
		TypeScannerRegistry.Context<JsonCodec<?>> ctx, AnnotatedType resolved, DerivationContext dctx
	) {
		Class<?> recordClass = Types.getRawType(resolved.getType());
		// an empty incoming path means this record IS the root of the derivation
		String base = dctx.path().isEmpty() ? recordClass.getSimpleName() : dctx.path();

		RecordDescriptor descriptor;
		Map<TypeVariable<?>, AnnotatedType> bindings;
		try {
			descriptor = describe(recordClass);
			// computed once per derivation, from the resolved annotated type, not once per component
			bindings = AnnotatedTypes.getTypeBindings(resolved);
		} catch (RuntimeException e) {
			// Only the ROOT frame reports its own reflective prelude. When this record is a component
			// of another, the enclosing frame reports the very same failure with strictly more
			// information - the enclosing record, the component name and the component's declared type
			// - which is what FR-019 asks for; wrapping here would win the race and lose all three.
			if (!dctx.path().isEmpty()) throw e;
			throw failure(base, recordClass, null, null, e);
		}

		ComponentBinding[] components = descriptor.components();
		JsonCodec<?>[] componentCodecs = new JsonCodec<?>[components.length];
		Object[] defaults = new Object[components.length];
		for (int i = 0; i < components.length; i++) {
			ComponentBinding component = components[i];
			AnnotatedType componentType;
			try {
				componentType = componentAnnotatedType(component, bindings);
			} catch (RuntimeException e) {
				// an unbound type variable in a raw resolution; there is no component type to name yet
				throw failure(base + "." + component.name(), recordClass, component.name(), null, e);
			}
			String step = base + "." + component.name() + (rendersAsSequence(componentType) ? "[]" : "");
			try {
				componentCodecs[i] = ctx.withContextValue(dctx.at(step)).scan(componentType);
			} catch (DerivationFailure e) {
				// a nested record frame already located it, and the path was threaded DOWN - so the
				// innermost report is the complete one. Exactly one wrap, and its cause is the original
				throw e;
			} catch (RuntimeException e) {
				// deliberately not Exception and not Throwable: a StackOverflowError out of a
				// pathological type graph must not be swallowed into an IllegalArgumentException
				throw failure(step, recordClass, component.name(), componentType, e);
			}
			// Optional is detected on the POST-substitution type: record Box<T>(T v) resolved at
			// Box<Optional<String>> must be seen as Optional too, and only bind() knows that
			if (Types.getRawType(componentType.getType()) == Optional.class) defaults[i] = Optional.empty();
		}
		return assemble(descriptor, componentCodecs, defaults);
	}

	/**
	 * Whether a component's path step is decorated with {@code []}.
	 *
	 * <p>The decoration is contributed by the <b>enclosing record</b>, never by the {@code List},
	 * {@code Set} or array mapping — those live in {@code JsonCodecFactory}, are shared with every
	 * non-record resolution and know nothing about derivation or paths. Collections and arrays only:
	 * a {@code Map}'s failing value is not "an element of", and neither is an {@code Optional}'s.
	 */
	private static boolean rendersAsSequence(AnnotatedType componentType) {
		Class<?> raw = Types.getRawType(componentType.getType());
		return raw.isArray() || Collection.class.isAssignableFrom(raw);
	}

	/**
	 * A derivation failure that has already been located and formatted. An enclosing record frame
	 * rethrows it untouched, which is what keeps the wrap count at exactly one and keeps the original
	 * failure as {@code getCause()}.
	 *
	 * <p>A consumer only ever sees an {@link IllegalArgumentException}; the subclass is an
	 * implementation detail no one outside this file names.
	 */
	private static final class DerivationFailure extends IllegalArgumentException {
		DerivationFailure(String message, Throwable cause) {super(message, cause);}
	}

	private static DerivationFailure failure(
		String path, Class<?> recordClass, @Nullable String component,
		@Nullable AnnotatedType componentType, RuntimeException cause
	) {
		StringBuilder sb = new StringBuilder("Cannot derive a JSON codec at ").append(path)
			.append(": record ").append(recordClass.getName());
		if (component != null) sb.append(", component '").append(component).append('\'');
		// the TYPE, never the AnnotatedType: AnnotatedTypeImpl.toString() renders the annotations too,
		// and an annotation can carry consumer-supplied text
		if (componentType != null) sb.append(" of type ").append(componentType.getType().getTypeName());
		return new DerivationFailure(sb.append(": ").append(detail(componentType, cause)).toString(), cause);
	}

	/**
	 * Classifies an already-thrown failure. Nothing here is a pre-scan gate: a consumer who registers
	 * a codec for {@code byte[]} resolves normally and never reaches this method, which is what keeps
	 * the primitive-array sentence a diagnosis rather than a refusal.
	 *
	 * <p>Known limit: {@code byte[][]} is a reference array whose <i>element</i> is a primitive array,
	 * so it fails one level deeper and gets the generic wording. This classifies the component it
	 * holds, not the whole subtree.
	 */
	private static String detail(@Nullable AnnotatedType componentType, RuntimeException cause) {
		if (componentType != null) {
			Class<?> raw = Types.getRawType(componentType.getType());
			if (raw.isArray() && raw.getComponentType().isPrimitive()) {
				return
					"primitive arrays are out of scope; model it as a List, or register a codec for " +
					raw.getTypeName() + " by hand";
			}
		}
		// the Object.class fallback carries no message at all, so the offending type has to be printed
		// by us - that is what the 'of type ...' clause above is for
		if (cause instanceof UnsupportedOperationException) {
			return
				"no codec is registered for it; register one with " +
				"JsonCodecFactory.rebuild().with(Type, Mapping).build()";
		}
		return cause.getMessage() != null ? cause.getMessage() : cause.getClass().getName();
	}

	/**
	 * Substitutes the record's type variables. There is deliberately <b>no substitution logic
	 * here</b>: {@code AnnotatedTypes.bind} already recurses into parameterized arguments and
	 * combines annotations, so {@code List<A>} becomes {@code List<String>} with the argument's
	 * annotations carried, and a component's own annotations win over the argument's — which is what
	 * makes {@code record Box<T>(@JsonNullable T value)} nullable at {@code Box<String>}. Adding a
	 * second mechanism here would duplicate both.
	 *
	 * <p>{@code bind} is called <b>unconditionally</b>, empty bindings included. A non-generic
	 * component short-circuits and is returned identically, annotations intact, at no cost; a
	 * component that is a type variable in a raw resolution must reach {@code bind}'s "Type not
	 * found" throw rather than silently produce a codec for the erasure.
	 *
	 * <p>The edge case "a record nested inside a generic outer class whose component mentions the
	 * outer's type variable" cannot arise: nested and local records are implicitly {@code static},
	 * so such a component is a compile error, not a runtime path.
	 */
	private static AnnotatedType componentAnnotatedType(
		ComponentBinding component, Map<TypeVariable<?>, AnnotatedType> bindings
	) {
		return AnnotatedTypes.bind(component.annotatedType(), bindings::get);
	}

	/** One record component: its JSON key, its <b>annotated</b> generic type, and its accessor. */
	record ComponentBinding(String name, AnnotatedType annotatedType, Method accessor) {}

	record RecordDescriptor(Class<?> recordClass, Constructor<?> canonicalConstructor, ComponentBinding[] components) {}

	/**
	 * Reads the reflective shape of a {@code record}: its components in canonical order, and its
	 * canonical constructor, both made accessible.
	 */
	static RecordDescriptor describe(Class<?> recordClass) {
		// getRecordComponents() returns null for a non-record and a zero-length array for record X() {}
		RecordComponent[] recordComponents = recordClass.getRecordComponents();
		if (recordComponents == null) {
			throw new IllegalArgumentException("Not a record: " + recordClass.getName());
		}

		// The erased component type is required here and only here: constructor parameter types are
		// erased, so getGenericType() would never match. Everywhere else the ANNOTATED type is the
		// right one - confusing the two produces a NoSuchMethodException nobody can explain.
		Class<?>[] componentTypes = new Class<?>[recordComponents.length];
		for (int i = 0; i < recordComponents.length; i++) {
			componentTypes[i] = recordComponents[i].getType();
		}

		Constructor<?> canonicalConstructor;
		try {
			// never scan getDeclaredConstructors() for a matching arity: a record may declare other
			// same-arity constructors and the array has no specified order
			canonicalConstructor = recordClass.getDeclaredConstructor(componentTypes);
		} catch (NoSuchMethodException e) {
			throw new IllegalArgumentException(
				"Cannot locate the canonical constructor of record " + recordClass.getName() +
				" with component types " + Arrays.toString(componentTypes), e);
		}
		try {
			canonicalConstructor.setAccessible(true);
		} catch (InaccessibleObjectException | SecurityException e) {
			throw new IllegalArgumentException(
				"Cannot access the canonical constructor of record " + recordClass.getName() + ": " + e.getMessage(), e);
		}

		ComponentBinding[] components = new ComponentBinding[recordComponents.length];
		for (int i = 0; i < recordComponents.length; i++) {
			RecordComponent recordComponent = recordComponents[i];
			Method accessor = recordComponent.getAccessor();
			try {
				accessor.setAccessible(true);
			} catch (InaccessibleObjectException | SecurityException e) {
				throw new IllegalArgumentException(
					"Cannot access component '" + recordComponent.getName() + "' of record " +
					recordClass.getName() + ": " + e.getMessage(), e);
			}
			components[i] = new ComponentBinding(recordComponent.getName(), recordComponent.getAnnotatedType(), accessor);
		}

		return new RecordDescriptor(recordClass, canonicalConstructor, components);
	}

	/**
	 * Fills {@link ObjectJsonCodec.BuilderArray} — one entry per component, in canonical component
	 * order, with the component's name as its JSON key <b>verbatim</b>: no case change, no separator
	 * change, no naming strategy.
	 *
	 * <p>Order matters and must never be changed, not alphabetically and not "nulls last":
	 * {@code BuilderArray} assigns each field {@code index = fields.size()} at {@code with(...)} time
	 * and the encoder walks the fields in that order, so this loop <i>is</i> the JSON member order.
	 *
	 * <p>The {@code Object[]} accumulator is sized once, here, at derivation: {@code doBuild()}
	 * closes over the prototype length, which is the component count. A hostile payload cannot make
	 * the accumulator larger, however many members it sends.
	 */
	static JsonCodec<?> assemble(RecordDescriptor descriptor, JsonCodec<?>[] componentCodecs) {
		return assemble(descriptor, componentCodecs, new Object[componentCodecs.length]);
	}

	/**
	 * @param defaults one entry per component; a {@code null} entry means <b>no default</b>, which is
	 *                 unambiguous only because the sole default this class ever supplies is
	 *                 {@code Optional.empty()}
	 */
	static JsonCodec<?> assemble(RecordDescriptor descriptor, JsonCodec<?>[] componentCodecs, Object[] defaults) {
		// a fresh, one-shot builder per derivation; it is stateful and must not be reused
		ObjectJsonCodec.BuilderArray<Object> builder = ObjectJsonCodec.builder(constructorOf(descriptor));
		ComponentBinding[] components = descriptor.components();
		for (int i = 0; i < components.length; i++) {
			ComponentBinding component = components[i];
			// both overloads assign index = fields.size() at call time, so canonical component order -
			// and therefore every frozen JSON literal - is unaffected by which branch a component takes
			if (defaults[i] != null) {
				builder.with(component.name(), accessorOf(component), (JsonCodec<Object>) componentCodecs[i], defaults[i]);
			} else {
				builder.with(component.name(), accessorOf(component), (JsonCodec<Object>) componentCodecs[i]);
			}
		}
		return builder.build();
	}

	/**
	 * The encode-side binder. {@link Function#apply} declares no checked exception and neither does
	 * {@link JsonCodec#write}, so the two reflective failures are wrapped. A record accessor is a
	 * field read and cannot fail; the catch exists so that a future non-trivial accessor does not
	 * produce an unattributable failure.
	 */
	private static Function<Object, Object> accessorOf(ComponentBinding component) {
		Method accessor = component.accessor();
		return item -> {
			try {
				return accessor.invoke(item);
			} catch (IllegalAccessException e) {
				throw new AssertionError(e);
			} catch (InvocationTargetException e) {
				Throwable cause = e.getCause();
				if (cause instanceof RuntimeException runtimeException) throw runtimeException;
				throw new IllegalStateException("Cannot read component '" + component.name() + "'", cause);
			}
		};
	}

	/** The decode-side binder — the finaliser {@code ObjectJsonCodec.BuilderArray} calls. */
	private static JsonConstructorN<Object> constructorOf(RecordDescriptor descriptor) {
		Constructor<?> canonicalConstructor = descriptor.canonicalConstructor();
		String recordName = descriptor.recordClass().getName();
		return params -> {
			try {
				// newInstance is varargs; the Object[] spreads as the argument array
				return canonicalConstructor.newInstance(params);
			} catch (InvocationTargetException e) {
				throw new JsonValidationException("Cannot construct " + recordName + ": " + e.getCause(), e.getCause());
			} catch (InstantiationException | IllegalAccessException e) {
				throw new AssertionError(e);
			}
		};
	}
}
