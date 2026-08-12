package io.activej.json;

import io.activej.common.builder.AbstractBuilder;
import io.activej.common.builder.Rebuildable;
import io.activej.json.annotations.JsonNullable;
import io.activej.json.annotations.JsonSubclasses;
import io.activej.types.TypeT;
import io.activej.types.Types;
import io.activej.types.scanner.TypeScannerRegistry;

import java.lang.reflect.AnnotatedType;
import java.lang.reflect.Array;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.*;
import java.util.*;

import static io.activej.common.Checks.checkArgument;
import static io.activej.types.Utils.getAnnotation;
import static io.activej.types.Utils.hasAnnotation;

public class JsonCodecFactory implements Rebuildable<JsonCodecFactory, JsonCodecFactory.Builder> {
	private static final JsonCodecFactory DEFAULT_INSTANCE = JsonCodecFactory.builder().build();

	private final TypeScannerRegistry<JsonCodec<?>> registry;

	/**
	 * This factory's own memo of derived record codecs, handed to the {@code Record} mapping inside a
	 * {@link RecordJsonCodec.DerivationContext} as the scanner's context value rather than captured by
	 * it — see {@link #rebuild()} and {@link RecordJsonCodec#derive(TypeScannerRegistry.Context)}.
	 */
	private final DerivationCache derivationCache;

	private JsonCodecFactory(TypeScannerRegistry<JsonCodec<?>> registry, DerivationCache derivationCache) {
		this.registry = registry;
		this.derivationCache = derivationCache;
	}

	/**
	 * A shared, process-lifetime factory. Resolving a record through it pins that record's
	 * classloader for the JVM's lifetime — see {@link DerivationCache}'s "Classloader retention"
	 * note. A host that unloads classloaders at runtime (plugins, hot redeploy) should keep a
	 * scoped instance from {@link #builder()} instead, one per deployment.
	 */
	public static JsonCodecFactory defaultInstance() {
		return DEFAULT_INSTANCE;
	}

	public static Builder builder() {
		JsonCodecFactory factory = new JsonCodecFactory(TypeScannerRegistry.create(), new DerivationCache());
		//noinspection unchecked,rawtypes
		return factory.new Builder()
			.with(String.class, ctx -> JsonCodecs.ofString())

			.with(byte.class, ctx -> JsonCodecs.ofByte())
			.with(short.class, ctx -> JsonCodecs.ofShort())
			.with(int.class, ctx -> JsonCodecs.ofInteger())
			.with(long.class, ctx -> JsonCodecs.ofLong())
			.with(float.class, ctx -> JsonCodecs.ofFloat())
			.with(double.class, ctx -> JsonCodecs.ofDouble())
			.with(boolean.class, ctx -> JsonCodecs.ofBoolean())
			.with(char.class, ctx -> JsonCodecs.ofCharacter())

			.with(Byte.class, ctx -> JsonCodecs.ofByte())
			.with(Short.class, ctx -> JsonCodecs.ofShort())
			.with(Integer.class, ctx -> JsonCodecs.ofInteger())
			.with(Long.class, ctx -> JsonCodecs.ofLong())
			.with(Float.class, ctx -> JsonCodecs.ofFloat())
			.with(Double.class, ctx -> JsonCodecs.ofDouble())
			.with(Boolean.class, ctx -> JsonCodecs.ofBoolean())
			.with(Character.class, ctx -> JsonCodecs.ofCharacter())

			// Leaf value types. Position among these entries is chosen for readability and is NOT
			// load-bearing: none of them is assignable to Record, List, Set, Map or Object[], so match()
			// has a single most-specific candidate in every case and its only competitor is the
			// Object.class fallback, which loses.
			.with(LocalDate.class, ctx -> JsonCodecs.ofLocalDate())
			.with(LocalTime.class, ctx -> JsonCodecs.ofLocalTime())
			.with(LocalDateTime.class, ctx -> JsonCodecs.ofLocalDateTime())
			.with(Instant.class, ctx -> JsonCodecs.ofInstant())
			.with(Duration.class, ctx -> JsonCodecs.ofDuration())

			.with(UUID.class, ctx -> JsonCodecs.ofUuid())
			.with(BigDecimal.class, ctx -> JsonCodecs.ofBigDecimal())
			.with(BigInteger.class, ctx -> JsonCodecs.ofBigInteger())

			.with(Enum.class, ctx -> JsonCodecs.ofEnum((Class<Enum>) ctx.getRawType()))

			// Deliberately before List and Map: TypeScannerRegistry.match keeps the FIRST of two entries
			// neither of which is assignable to the other, so a record that also implements List, Set or
			// Map resolves by its components rather than as a collection.
			.with(Record.class, RecordJsonCodec::derive)

			.with(List.class, ctx -> JsonCodecs.ofList(ctx.scanTypeArgument(0)))
			.with(Set.class, ctx -> JsonCodecs.ofSet(ctx.scanTypeArgument(0)))

			// Reference arrays only: int[]/long[]/... are NOT assignable to Object[] and deliberately
			// fall through to the Object fallback (primitive arrays are out of scope).
			// Built on ofList rather than JsonCodecs.ofArray: that factory's Supplier<T[]> IS its
			// accumulator and it requires accumulator.length == the element count, so it can only decode
			// an array whose length is known before parsing. See task-plans/T041.
			.with(Object[].class, ctx -> {
				// the argument's raw type, not getRawType().getComponentType(): the latter needs the
				// array class to exist as a Class, which it does not for a generic component
				Class<?> componentClass = Types.getRawType(ctx.getTypeArgument(0).getType());
				JsonCodec<Object> componentCodec = ctx.scanTypeArgument(0);
				// toArray(IntFunction) copies with the generator's array as the target, so the decoded
				// array has the DECLARED component type - which is what a record's canonical constructor
				// requires. A bare toArray() would hand it an Object[] and fail there.
				return JsonCodecs.ofList(componentCodec).<Object[]>transform(
					Arrays::asList,
					list -> list.toArray(size -> (Object[]) Array.newInstance(componentClass, size)));
			})

			.with(Optional.class, ctx -> JsonCodecs.ofOptional(ctx.scanTypeArgument(0)))

			.with(Map.class, ctx -> {
				AnnotatedType keyAnnotatedType = ctx.getTypeArgument(0);
				// getRawType, never a (Class<?>) cast: a key type that is not a Class - Map<List<K>,V>,
				// Map<? extends Number,V> - would otherwise fail as a raw ClassCastException out of the
				// factory, before any of the branches below and before the named refusal at the end
				Class<?> keyType = Types.getRawType(keyAnnotatedType.getType());
				if (keyAnnotatedType.getAnnotations().length == 0 && keyType == String.class) {
					//noinspection unchecked
					return JsonCodecs.ofMap((JsonCodec<Object>) ctx.scanTypeArgument(1));
				}
				if (keyType == String.class) {
					// reached only with an annotation present - the unannotated case already returned above
					throw new IllegalArgumentException(
						"Cannot resolve a JSON codec for map type " + ctx.getType() +
						": a String map key does not support annotations (found " +
						Arrays.toString(keyAnnotatedType.getAnnotations()) + "), since a JSON object key is " +
						"always a plain string with no room to carry one. Remove the annotation from the key type.");
				}
				if (Number.class.isAssignableFrom(keyType)) {
					//noinspection unchecked
					JsonKeyCodec<?> keyCodec = JsonKeyCodec.ofNumberKey((Class<Number>) keyType);
					return JsonCodecs.ofMap(keyCodec, ctx.scanTypeArgument(1));
				}
				if (Enum.class.isAssignableFrom(keyType)) {
					//noinspection unchecked
					JsonKeyCodec<?> keyCodec = JsonKeyCodec.ofEnumKey((Class<Enum>) keyType);
					return JsonCodecs.ofMap(keyCodec, ctx.scanTypeArgument(1));
				}
				if (keyType == UUID.class) {
					return JsonCodecs.ofMap(JsonKeyCodec.ofUuidKey(), ctx.scanTypeArgument(1));
				}
				// Deliberately not a second registry: a consumer needing another key type registers a
				// JsonCodec<Map<K,V>> through Builder.with and resolves the value with scanTypeArgument(1).
				throw new IllegalArgumentException(
					"Cannot resolve a JSON codec for map type " + ctx.getType() +
					": unsupported key type " + keyType.getName() +
					". Supported key types are String, Number subclasses, Enum and UUID (see JsonKeyCodec). " +
					"For any other key type, register a JsonCodec<Map<K,V>> for this map type through " +
					"JsonCodecFactory.Builder.with(Type, Mapping) and resolve the value type with " +
					"ctx.scanTypeArgument(1)");
			})

			.with(Object.class, ctx -> {
				throw new UnsupportedOperationException();
			})
			;
	}

	public final class Builder extends AbstractBuilder<Builder, JsonCodecFactory> {
		private Builder() {}

		public Builder with(TypeT<?> typeT, TypeScannerRegistry.Mapping<JsonCodec<?>> fn) {
			checkNotBuilt(this);
			return with(typeT.getType(), fn);
		}

		public Builder with(Type type, TypeScannerRegistry.Mapping<JsonCodec<?>> fn) {
			checkNotBuilt(this);
			registry.with(type, ctx -> {
				JsonCodec<?> jsonCodec;

				JsonSubclasses annotation;
				if ((annotation = getAnnotation(ctx.getAnnotations(), JsonSubclasses.class)) != null) {
					SubclassJsonCodec<Object>.Builder subclassBuilder = SubclassJsonCodec.builder();
					Class<?>[] subclasses = annotation.value();
					String[] tags = annotation.tags();
					checkArgument(tags.length == 0 || tags.length == subclasses.length);
					for (int i = 0; i < subclasses.length; i++) {
						//noinspection unchecked
						Class<Object> subclass = (Class<Object>) subclasses[i];
						//noinspection unchecked
						JsonCodec<Object> codec = (JsonCodec<Object>) ctx.scan(subclass);
						if (tags.length != 0) {
							subclassBuilder.with(subclass, tags[i], codec);
						} else {
							subclassBuilder.with(subclass, codec);
						}
					}
					jsonCodec = subclassBuilder.build();
				} else {
					jsonCodec = fn.apply(ctx);
				}

				if (hasAnnotation(ctx.getAnnotations(), JsonNullable.class)) {
					if (!(jsonCodec instanceof JsonCodecs.NullableJsonCodec)) {
						jsonCodec = jsonCodec.nullable();
					}
				}

				return jsonCodec;
			});
			return this;
		}

		@Override
		protected JsonCodecFactory doBuild() {
			return JsonCodecFactory.this;
		}
	}

	/**
	 * The new factory gets a <b>fresh, empty</b> memo, never this one's. {@code copyOf} copies registry
	 * entries by reference, so the rebuilt factory shares this factory's {@code Record} mapping — but a
	 * codec derived here was derived against <i>these</i> registrations, and a consumer rebuilds
	 * precisely to change them. Sharing the memo would let a rebuilt factory hand out a record codec
	 * memoized against the component codecs it just replaced.
	 */
	@Override
	public Builder rebuild() {
		return new JsonCodecFactory(TypeScannerRegistry.copyOf(this.registry), new DerivationCache()).new Builder();
	}

	public <T> JsonCodec<T> resolve(Class<T> type) {
		//noinspection unchecked
		return (JsonCodec<T>) registry.scanner(derivationContext()).scan(type);
	}

	public <T> JsonCodec<T> resolve(Type type) {
		//noinspection unchecked
		return (JsonCodec<T>) registry.scanner(derivationContext()).scan(type);
	}

	public <T> JsonCodec<T> resolve(TypeT<T> type) {
		//noinspection unchecked
		return (JsonCodec<T>) registry.scanner(derivationContext()).scan(type.getAnnotatedType());
	}

	/**
	 * The scanner's context value: this factory's memo, plus an empty derivation path.
	 *
	 * <p>The path is deliberately <b>not</b> seeded from the resolved type's name — for
	 * {@code resolve(new TypeT<List<Order>>(){})} that would render a failure inside {@code Order} as
	 * {@code List.id}. The root segment must be the outermost <i>record</i>, which is why
	 * {@code RecordJsonCodec.build} seeds it when the incoming path is empty.
	 */
	private RecordJsonCodec.DerivationContext derivationContext() {
		return RecordJsonCodec.DerivationContext.root(derivationCache);
	}

}
