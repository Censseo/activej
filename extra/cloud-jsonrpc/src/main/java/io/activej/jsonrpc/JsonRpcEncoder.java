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

import com.dslplatform.json.JsonReader;
import com.dslplatform.json.JsonWriter;
import io.activej.json.JsonCodec;
import io.activej.json.JsonUtils;
import io.activej.jsonrpc.impl.RawPayloadView;

import static java.nio.charset.StandardCharsets.US_ASCII;

/**
 * Renders JSON-RPC 2.0 documents.
 *
 * <h2>Shape: a {@link JsonCodec}, not a parallel entry point (FR-081)</h2>
 * Encoding is expressed as three {@code JsonCodec}s — {@link #OUTPUT_CODEC}, {@link #MESSAGE_CODEC} and
 * {@link #ERROR_CODEC} — which {@link JsonUtils#toJsonBytes} drives exactly as it drives every other codec in
 * the repository. {@link #encode(JsonRpcOutput)} and {@link #encode(JsonRpcMessage)} are thin conveniences
 * over that, not a second implementation. A consumer that already holds a {@code JsonWriter} (feature 03
 * embedding a response inside a larger document, for instance) uses the codec directly.
 * <p>
 * The codecs are <b>encode-only</b>: {@code read} throws {@link UnsupportedOperationException}. Decoding
 * cannot be a codec — it must defer payloads, detect duplicate members and yield one outcome per batch
 * element, none of which a {@code JsonCodec} can express — so it is a bespoke reader walk in
 * {@code JsonRpcDecoder} instead.
 *
 * <h2>What the output guarantees</h2>
 * <ul>
 *     <li>{@code "jsonrpc":"2.0"} on every envelope (FR-040).</li>
 *     <li>A fixed member order per document kind, so output is deterministic and byte-comparable (FR-041):
 *     <table border="1">
 *         <caption>member order</caption>
 *         <tr><th>Document</th><th>Order</th></tr>
 *         <tr><td>request</td><td>{@code jsonrpc}, {@code id}, {@code method}, {@code params}</td></tr>
 *         <tr><td>notification</td><td>{@code jsonrpc}, {@code method}, {@code params}</td></tr>
 *         <tr><td>response (result)</td><td>{@code jsonrpc}, {@code id}, {@code result}</td></tr>
 *         <tr><td>response (error)</td><td>{@code jsonrpc}, {@code id}, {@code error}</td></tr>
 *         <tr><td>error object</td><td>{@code code}, {@code message}, {@code data}</td></tr>
 *     </table></li>
 *     <li>An absent optional member is <b>omitted entirely</b>, never emitted as {@code null} (FR-041).</li>
 *     <li>A notification has <b>no {@code id} member at all</b> — not {@code "id":null} (FR-042).</li>
 *     <li>A batch renders as a JSON array <b>even at size one</b> (FR-043).</li>
 *     <li>{@link JsonRpcOutput#NONE} renders as <b>zero bytes</b> — not {@code []}, not {@code {}} (FR-044).</li>
 *     <li>A {@link JsonRpcPayload.Raw} payload is re-emitted <b>byte-identically</b> to its captured slice,
 *     with no decode/re-encode round trip (FR-024, FR-082), via
 *     {@link JsonWriter#writeRaw(byte[], int, int)}.</li>
 * </ul>
 *
 * <h2>No ordering relationship (FR-046)</h2>
 * A batch's responses carry no ordering relationship to the requests they answer. Correlation is by
 * {@code id} alone; this encoder writes the list it is given, in the order it is given, and asserts nothing
 * about it.
 *
 * <h2>The {@code ThreadLocal} hazard (FR-045)</h2>
 * {@code JsonUtils} keeps its {@code JsonWriter} in a {@code ThreadLocal} and {@code reset()}s that same
 * instance on the next call from the same thread. Two consequences are load-bearing here:
 * <ol>
 *     <li>This class returns an <b>owned</b> {@code byte[]} and never a {@code JsonWriter}, because a
 *     returned writer is invalidated by the very next encode on the thread.</li>
 *     <li>A {@link JsonRpcPayload.Encoded} payload is written through {@link JsonRpcPayload.Encoded#codec()}
 *     straight into the current writer — <b>never</b> through {@code toByteArray()} or {@code decode(...)}.
 *     Going through {@code JsonUtils} mid-write would {@code reset()} the writer this encoder is already
 *     filling and silently truncate everything written so far.</li>
 * </ol>
 */
public final class JsonRpcEncoder {
	private JsonRpcEncoder() {}

	private static final byte[] VERSION_MEMBER = "\"jsonrpc\":\"2.0\"".getBytes(US_ASCII);
	private static final byte[] ID_MEMBER = ",\"id\":".getBytes(US_ASCII);
	private static final byte[] METHOD_MEMBER = ",\"method\":".getBytes(US_ASCII);
	private static final byte[] PARAMS_MEMBER = ",\"params\":".getBytes(US_ASCII);
	private static final byte[] RESULT_MEMBER = ",\"result\":".getBytes(US_ASCII);
	private static final byte[] ERROR_MEMBER = ",\"error\":".getBytes(US_ASCII);
	private static final byte[] CODE_MEMBER = "\"code\":".getBytes(US_ASCII);
	private static final byte[] MESSAGE_MEMBER = ",\"message\":".getBytes(US_ASCII);
	private static final byte[] DATA_MEMBER = ",\"data\":".getBytes(US_ASCII);

	/** The error object of §5.1: {@code code}, {@code message}, then {@code data} when present. Encode-only. */
	public static final JsonCodec<JsonRpcError> ERROR_CODEC = new EncodeOnlyCodec<>() {
		@Override
		public void write(JsonWriter writer, JsonRpcError error) {
			writeError(writer, error);
		}
	};

	/** One envelope — request, notification or response. Encode-only. */
	public static final JsonCodec<JsonRpcMessage> MESSAGE_CODEC = new EncodeOnlyCodec<>() {
		@Override
		public void write(JsonWriter writer, JsonRpcMessage message) {
			writeMessage(writer, message);
		}
	};

	/** A whole outgoing document, including the zero-byte {@link JsonRpcOutput.None} case. Encode-only. */
	public static final JsonCodec<JsonRpcOutput> OUTPUT_CODEC = new EncodeOnlyCodec<>() {
		@Override
		public void write(JsonWriter writer, JsonRpcOutput output) {
			writeOutput(writer, output);
		}
	};

	/**
	 * Renders a document.
	 *
	 * @return an owned array — a <b>zero-length</b> one for {@link JsonRpcOutput#NONE} (FR-044)
	 */
	public static byte[] encode(JsonRpcOutput output) {
		return JsonUtils.toJsonBytes(OUTPUT_CODEC, output);
	}

	/** Renders one envelope. The convenience for the single-document case. */
	public static byte[] encode(JsonRpcMessage message) {
		return JsonUtils.toJsonBytes(MESSAGE_CODEC, message);
	}

	private static void writeOutput(JsonWriter writer, JsonRpcOutput output) {
		switch (output) {
			case JsonRpcOutput.None ignored -> {
				// nothing at all: zero bytes, which is neither "[]" nor "{}" (FR-044)
			}
			case JsonRpcOutput.Single single -> writeMessage(writer, single.message());
			case JsonRpcOutput.Batch batch -> {
				writer.writeByte(JsonWriter.ARRAY_START);
				boolean first = true;
				for (JsonRpcMessage message : batch.messages()) {
					if (!first) writer.writeByte(JsonWriter.COMMA);
					first = false;
					writeMessage(writer, message);
				}
				writer.writeByte(JsonWriter.ARRAY_END);
			}
		}
	}

	private static void writeMessage(JsonWriter writer, JsonRpcMessage message) {
		writer.writeByte(JsonWriter.OBJECT_START);
		writer.writeRaw(VERSION_MEMBER, 0, VERSION_MEMBER.length);
		switch (message) {
			case JsonRpcRequest request -> {
				writer.writeRaw(ID_MEMBER, 0, ID_MEMBER.length);
				writeId(writer, request.id());
				writer.writeRaw(METHOD_MEMBER, 0, METHOD_MEMBER.length);
				writer.writeString(request.method());
				writeOptionalMember(writer, PARAMS_MEMBER, request.params());
			}
			case JsonRpcNotification notification -> {
				// no id member at all — not "id":null (FR-042)
				writer.writeRaw(METHOD_MEMBER, 0, METHOD_MEMBER.length);
				writer.writeString(notification.method());
				writeOptionalMember(writer, PARAMS_MEMBER, notification.params());
			}
			case JsonRpcResponse response -> {
				writer.writeRaw(ID_MEMBER, 0, ID_MEMBER.length);
				writeId(writer, response.id());
				JsonRpcError error = response.error();
				if (error != null) {
					writer.writeRaw(ERROR_MEMBER, 0, ERROR_MEMBER.length);
					writeError(writer, error);
				} else {
					// a result is always present here — the constructor refused "neither" — and a result that
					// is the JSON literal null is a present value, rendered as null (§5), not omitted
					writer.writeRaw(RESULT_MEMBER, 0, RESULT_MEMBER.length);
					writePayload(writer, response.result());
				}
			}
		}
		writer.writeByte(JsonWriter.OBJECT_END);
	}

	private static void writeError(JsonWriter writer, JsonRpcError error) {
		writer.writeByte(JsonWriter.OBJECT_START);
		writer.writeRaw(CODE_MEMBER, 0, CODE_MEMBER.length);
		writer.writeAscii(Integer.toString(error.code()));
		writer.writeRaw(MESSAGE_MEMBER, 0, MESSAGE_MEMBER.length);
		writer.writeString(error.message());
		writeOptionalMember(writer, DATA_MEMBER, error.data());
		writer.writeByte(JsonWriter.OBJECT_END);
	}

	private static void writeId(JsonWriter writer, JsonRpcId id) {
		switch (id) {
			case JsonRpcId.Str str -> writer.writeString(str.value());
			case JsonRpcId.Num num -> writer.writeAscii(Long.toString(num.value()));
			case JsonRpcId.Null ignored -> writer.writeNull();
		}
	}

	/** Writes {@code member} followed by the payload, or nothing at all when the payload is absent (FR-041). */
	private static void writeOptionalMember(JsonWriter writer, byte[] member, JsonRpcPayload payload) {
		if (payload.isAbsent()) return;
		writer.writeRaw(member, 0, member.length);
		writePayload(writer, payload);
	}

	private static void writePayload(JsonWriter writer, JsonRpcPayload payload) {
		switch (payload) {
			case JsonRpcPayload.Raw raw -> {
				// byte-identical re-emission of the captured slice: no decode, no intermediate array
				// (FR-024, FR-082)
				RawPayloadView view = raw.view();
				writer.writeRaw(view.array(), view.start(), view.size());
			}
			// through the payload's OWN codec, straight into this writer. Never toByteArray()/decode(...):
			// those go through JsonUtils, which would reset() the very writer being filled
			case JsonRpcPayload.Encoded<?> encoded -> writeEncoded(writer, encoded);
			case JsonRpcPayload.Absent ignored ->
				throw new IllegalStateException("an absent payload has no rendering; the member is omitted");
		}
	}

	private static <T> void writeEncoded(JsonWriter writer, JsonRpcPayload.Encoded<T> encoded) {
		encoded.codec().write(writer, encoded.value());
	}

	/**
	 * A {@link JsonCodec} that only encodes. {@code JsonCodec} is the shape {@link JsonUtils} drives, and
	 * FR-081 requires composing with it rather than adding a parallel entry point — but decoding a JSON-RPC
	 * document is not expressible as a codec, so {@code read} refuses instead of half-working.
	 */
	private abstract static class EncodeOnlyCodec<T> implements JsonCodec<T> {
		@Override
		public final T read(JsonReader<?> reader) {
			throw new UnsupportedOperationException(
				"this codec only encodes; decoding a JSON-RPC document is a bespoke reader walk (FR-081) " +
				"and lives in JsonRpcDecoder");
		}
	}
}
