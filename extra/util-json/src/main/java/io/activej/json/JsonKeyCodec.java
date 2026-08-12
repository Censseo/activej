package io.activej.json;

import java.util.UUID;

public interface JsonKeyCodec<T> extends JsonKeyEncoder<T>, JsonKeyDecoder<T> {
	@Override
	String encode(T value);

	@Override
	T decode(String string) throws JsonValidationException;

	static <T> JsonKeyCodec<T> of(JsonKeyEncoder<T> encoder, JsonKeyDecoder<T> decoder) {
		return new JsonKeyCodec<>() {
			@Override
			public String encode(T value) {
				return encoder.encode(value);
			}

			@Override
			public T decode(String string) throws JsonValidationException {
				return decoder.decode(string);
			}
		};
	}

	static JsonKeyCodec<String> ofStringKey() {
		return new JsonKeyCodec<>() {
			@Override
			public String encode(String value) {
				return value;
			}

			@Override
			public String decode(String string) throws JsonValidationException {
				return string;
			}
		};
	}

	static <T extends Number> JsonKeyCodec<T> ofNumberKey(Class<T> type) {
		return new JsonKeyCodec<>() {
			private interface NumberParser<T extends Number> {
				T parse(String string) throws NumberFormatException;
			}

			private final NumberParser<?> parser;

			{
				if (type == Byte.class) {
					this.parser = Byte::parseByte;
				} else if (type == Short.class) {
					this.parser = Short::parseShort;
				} else if (type == Integer.class) {
					this.parser = Integer::parseInt;
				} else if (type == Long.class) {
					this.parser = Long::parseLong;
				} else if (type == Float.class) {
					this.parser = Float::parseFloat;
				} else if (type == Double.class) {
					this.parser = Double::parseDouble;
				} else
					// an INSTANCE initialiser, so this propagates out of the constructor as-is - no
					// ExceptionInInitializerError is involved. Reachable from the shipped factory:
					// Number.isAssignableFrom(BigDecimal.class) routes Map<BigDecimal,V> here
					throw new IllegalArgumentException(
						"Unsupported number key type: " + type.getName() +
						"; supported: Byte, Short, Integer, Long, Float, Double");
			}

			@Override
			public String encode(Number value) {
				return value.toString();
			}

			@Override
			public T decode(String string) throws JsonValidationException {
				try {
					//noinspection unchecked
					return (T) parser.parse(string);
				} catch (NumberFormatException e) {
					// getSimpleName() here and getName() in ofEnumKey, on purpose: these are java.lang
					// wrappers where the package adds nothing, whereas a consumer's enum is only
					// identifiable fully qualified
					throw new JsonValidationException("Malformed " + type.getSimpleName() + " key: " + string, e);
				}
			}
		};
	}

	/** {@code Enum.name()}, verbatim — never {@code ordinal()} and never {@code toString()}. */
	static <E extends Enum<E>> JsonKeyCodec<E> ofEnumKey(Class<E> type) {
		return of(
			Enum::name,
			string -> {
				try {
					return Enum.valueOf(type, string);
				} catch (IllegalArgumentException e) {
					// Enum.valueOf also throws NPE for a null name; a JSON key is never null
					throw new JsonValidationException(
						"Not a constant of enum " + type.getName() + ": " + string, e);
				}
			});
	}

	/** The canonical lower-case {@code UUID.toString()}; decoding is case-insensitive, as {@code UUID.fromString} is. */
	static JsonKeyCodec<UUID> ofUuidKey() {
		return of(
			UUID::toString,
			string -> {
				try {
					return UUID.fromString(string);
				} catch (IllegalArgumentException e) {
					throw new JsonValidationException("Malformed UUID key: " + string, e);
				}
			});
	}

}
