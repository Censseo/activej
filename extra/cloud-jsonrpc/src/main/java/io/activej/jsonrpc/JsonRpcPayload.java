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

package io.activej.jsonrpc;

import io.activej.common.exception.MalformedDataException;
import io.activej.json.JsonCodec;
import io.activej.json.JsonUtils;
import io.activej.jsonrpc.impl.RawPayloadView;

import java.util.Arrays;
import java.util.Objects;

/**
 * The vehicle for the three JSON-RPC members whose type this layer cannot know: {@code params},
 * {@code result} and {@code error.data}.
 * <p>
 * Exactly three states (FR-020):
 * <table border="1">
 *     <caption>payload states</caption>
 *     <tr><th>State</th><th>Carries</th><th>Produced by</th></tr>
 *     <tr><td>{@link Absent}</td><td>nothing</td><td>decoding a message whose member was omitted</td></tr>
 *     <tr><td>{@link Raw}</td><td>the envelope array and a {@code [start, end)} byte range</td><td>decoding</td></tr>
 *     <tr><td>{@link Encoded}</td><td>a value and the codec that writes it</td>
 *         <td>a caller building an outgoing message</td></tr>
 * </table>
 * Decoding never produces {@link Encoded}; encoding accepts all three.
 *
 * <h2>Why deferral, and why a byte range</h2>
 * JSON-RPC 2.0 does not constrain envelope member order, so {@code params} may legally precede
 * {@code method} — and until {@code method} is known, no codec for {@code params} exists. The justification
 * is <b>semantic</b>, not a throughput claim: phase-0 verdict 00-A measured deferral as
 * <i>feasible but costly</i> and withdrew the performance argument.
 * <p>
 * The representation verdict 00-A fixed is a reference to the <b>contiguous envelope array</b> plus an index
 * pair — never a generic JSON tree, and <b>never a retained {@code JsonReader}</b> (FR-022). A reader's index
 * space does not survive a buffer refill: the moment the reader is driven over more input, or is handed back
 * to the {@code ThreadLocal} pool {@code JsonUtils} keeps it in, any captured offset stops meaning anything.
 * A reader is also not owned past the decode call that produced it, so retaining one would alias state
 * another caller is about to reset.
 *
 * <h2>Retention (FR-025)</h2>
 * A {@link Raw} payload does <b>not</b> copy on construction — it holds the caller's array. The consequence
 * is that retaining one keeps the <i>whole envelope</i> reachable, however small the payload is.
 * {@link #toByteArray()} is the escape hatch: it produces an independent copy, after which the envelope may
 * be released.
 * <p>
 * A payload is immutable given an immutable array. This layer never writes to the envelope; a caller that
 * mutates it after decoding invalidates every {@link Raw} derived from it. That is documented, not defended.
 *
 * @see io.activej.jsonrpc.impl.RawPayloadView the {@code @ExposedInternals} accessor for the index pair,
 * which is deliberately not part of the supported surface (FR-026)
 */
public sealed interface JsonRpcPayload permits JsonRpcPayload.Absent, JsonRpcPayload.Raw, JsonRpcPayload.Encoded {
	/**
	 * The payload of a member that was not present at all — distinct from a payload over the JSON literal
	 * {@code null}, which is a present value.
	 */
	static JsonRpcPayload absent() {
		return Absent.INSTANCE;
	}

	/**
	 * A payload to be written through {@code codec} when the message is encoded — the outgoing counterpart
	 * of {@link Raw}, which only decoding produces.
	 *
	 * @param codec the codec that renders {@code value}
	 * @param value the value to render
	 * @param <T>   the value's type, inferred from {@code codec}
	 */
	static <T> Encoded<T> encoded(JsonCodec<T> codec, T value) {
		return new Encoded<>(codec, value);
	}

	/**
	 * A payload over {@code array[start, end)}, captured <b>without copying</b>.
	 *
	 * @throws IllegalArgumentException unless {@code 0 <= start <= end <= array.length}
	 */
	static Raw raw(byte[] array, int start, int end) {
		return new Raw(array, start, end);
	}

	/** Whether the member this payload stands for was absent from the document. Total on all states. */
	boolean isAbsent();

	/**
	 * Decodes this payload through a caller-supplied codec.
	 * <p>
	 * A {@link Raw} payload is decoded from its captured slice with the same completeness check
	 * {@link JsonUtils#fromJsonBytes} applies — trailing data inside the slice is a failure, not a silent
	 * success. An {@link Encoded} payload hands back its value when {@code codec} is the codec it was built
	 * with. An {@link Absent} payload always fails: "no value" cannot produce a {@code T}, and answering
	 * {@code null} would only move the failure to the caller's caller.
	 *
	 * @throws MalformedDataException if the bytes do not form a {@code T}, or the payload is absent, or the
	 *                                codec is not the one an {@link Encoded} payload carries
	 */
	<T> T decode(JsonCodec<T> codec) throws MalformedDataException;

	/**
	 * An independent copy of this payload's bytes — the escape hatch that lets a consumer outlive the
	 * envelope array without pinning it (FR-025).
	 *
	 * @throws IllegalStateException if the payload is {@link Absent}: there are no bytes to copy
	 */
	byte[] toByteArray();

	/**
	 * The number of JSON bytes this payload occupies, so a caller can bound work before decoding.
	 *
	 * @throws UnsupportedOperationException for an {@link Encoded} payload, whose byte length is not known
	 *                                       until its codec has run
	 */
	int size();

	/** The absent state: the member was not present in the document. */
	record Absent() implements JsonRpcPayload {
		private static final Absent INSTANCE = new Absent();

		@Override
		public boolean isAbsent() {
			return true;
		}

		@Override
		public <T> T decode(JsonCodec<T> codec) throws MalformedDataException {
			throw new MalformedDataException("the payload is absent, so it cannot be decoded");
		}

		@Override
		public byte[] toByteArray() {
			throw new IllegalStateException("the payload is absent, so it has no bytes");
		}

		@Override
		public int size() {
			return 0;
		}

		@Override
		public String toString() {
			return "JsonRpcPayload.Absent";
		}
	}

	/**
	 * The undecoded state: a byte range of the contiguous envelope array, captured during decoding and not
	 * yet interpreted.
	 * <p>
	 * Deliberately <b>not</b> a {@code record}: a record would publish {@code array()}, {@code start()} and
	 * {@code end()} as supported API, which FR-026 forbids. The index pair is reachable only through
	 * {@link #view()}, whose return type is marked {@code @ExposedInternals}.
	 * <p>
	 * Equality is by <b>slice content</b>: two raw payloads are equal when their captured bytes are equal,
	 * whichever envelope each points into. That is what makes an envelope with {@code params} before
	 * {@code method} compare equal to the same envelope with them the other way round (SC-008).
	 */
	final class Raw implements JsonRpcPayload {
		private final byte[] array;
		private final int start;
		private final int end;

		/**
		 * Captures {@code array[start, end)} <b>without copying</b>.
		 * <p>
		 * The bound check is <b>unconditional</b> — it is not gated behind {@code Checks} (FR-021). An index
		 * pair derived from a hostile envelope is untrusted input, not a programming error, and verdict 00-A
		 * threat 10 is exactly the observation that a capture rule correct on trusted fixtures is not correct
		 * by default on anything else. A payload that read arbitrary bytes of the caller's array would be a
		 * disclosure bug, and production runs with checks off.
		 *
		 * @param array the whole contiguous envelope array; retained, never copied, never written to
		 * @param start the index of the payload's first byte, inclusive
		 * @param end   the index one past the payload's last byte, exclusive
		 * @throws IllegalArgumentException unless {@code 0 <= start <= end <= array.length}
		 */
		public Raw(byte[] array, int start, int end) {
			if (array == null) throw new NullPointerException("array");
			if (start < 0 || end < start || end > array.length) {
				throw new IllegalArgumentException(
					"payload range [" + start + ", " + end + ") is out of bounds for an array of " + array.length);
			}
			this.array = array;
			this.start = start;
			this.end = end;
		}

		@Override
		public boolean isAbsent() {
			return false;
		}

		@Override
		public <T> T decode(JsonCodec<T> codec) throws MalformedDataException {
			Objects.requireNonNull(codec, "codec");
			try {
				return JsonUtils.fromJsonBytes(codec, toByteArray());
			} catch (RuntimeException e) {
				// dsl-json does not validate UTF-8 and does not fail uniformly on bytes it cannot read: a lone
				// continuation byte inside a string surfaces as an ArrayIndexOutOfBoundsException rather than a
				// parse failure (verified against 1.10.0). This method is documented to report a decode failure
				// as MalformedDataException, and a caller decoding a peer's payload is entitled to rely on that
				// for EVERY byte sequence, not only the ones the parser happens to reject cleanly.
				//
				// The message is a fixed string: a caller that funnels getMessage() into an outgoing document
				// must not thereby echo peer input (FR-089). The cause is attached, so the detail is still
				// reachable on purpose — through getCause(), never by accident.
				throw new MalformedDataException("the payload could not be decoded", e);
			}
		}

		@Override
		public byte[] toByteArray() {
			return Arrays.copyOfRange(array, start, end);
		}

		@Override
		public int size() {
			return end - start;
		}

		/**
		 * The {@code @ExposedInternals} view of this payload: the envelope array and the {@code [start, end)}
		 * pair, with no copy.
		 * <p>
		 * <b>Not part of the supported API surface</b> (FR-026). It exists for the transports and the
		 * dispatcher of this idea; application code uses {@link #decode} and {@link #toByteArray()}.
		 */
		public RawPayloadView view() {
			return new RawPayloadView(array, start, end);
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (!(o instanceof Raw other)) return false;
			return Arrays.equals(this.array, this.start, this.end, other.array, other.start, other.end);
		}

		@Override
		public int hashCode() {
			int hash = 1;
			for (int i = start; i < end; i++) {
				hash = 31 * hash + array[i];
			}
			return hash;
		}

		/**
		 * Reports the byte length only. The captured bytes are a peer's input and may carry its secrets, so
		 * they are deliberately not rendered into a string that could reach a log (SI-6). Use
		 * {@link #toByteArray()} when the content is what you need.
		 */
		@Override
		public String toString() {
			return "JsonRpcPayload.Raw[" + size() + " bytes]";
		}
	}

	/**
	 * The encodable state: a value together with the codec that writes it. Built by a caller assembling an
	 * outgoing message; never produced by decoding.
	 *
	 * @param codec the codec that renders {@code value}; never {@code null}
	 * @param value the value to render; never {@code null}
	 */
	record Encoded<T>(JsonCodec<T> codec, T value) implements JsonRpcPayload {
		/** @throws NullPointerException if either component is {@code null} */
		public Encoded {
			Objects.requireNonNull(codec, "codec");
			Objects.requireNonNull(value, "value");
		}

		@Override
		public boolean isAbsent() {
			return false;
		}

		@SuppressWarnings("unchecked")
		@Override
		public <V> V decode(JsonCodec<V> codec) throws MalformedDataException {
			if (codec != this.codec) {
				throw new MalformedDataException("this payload was built with a different codec");
			}
			return (V) value;
		}

		@Override
		public byte[] toByteArray() {
			return JsonUtils.toJsonBytes(codec, value);
		}

		@Override
		public int size() {
			throw new UnsupportedOperationException(
				"the size of an encodable payload is not known until its codec has run; " +
				"use toByteArray().length if the cost is acceptable");
		}
	}
}
