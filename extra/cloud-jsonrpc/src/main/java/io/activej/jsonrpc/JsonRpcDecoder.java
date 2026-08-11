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

import com.dslplatform.json.DslJson;
import com.dslplatform.json.JsonReader;
import com.dslplatform.json.runtime.Settings;
import io.activej.common.exception.MalformedDataException;
import io.activej.json.JsonCodec;
import io.activej.json.JsonCodecs;
import io.activej.json.JsonUtils;
import io.activej.jsonrpc.impl.JsonDepthScanner;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Decodes one complete JSON-RPC 2.0 document from a <b>contiguous</b> {@code byte[]}.
 *
 * <h2>The contiguous-array contract (FR-030) — an obligation on every transport</h2>
 * The array handed to {@link #decode(byte[])} <b>must hold the whole document</b>. This decoder does not
 * accept a stream, a channel, or a sequence of fragments, and that is not an omission to be filled in later:
 * the undecoded-payload representation is an index pair into <i>this exact array</i>
 * ({@link JsonRpcPayload.Raw}), and dsl-json's index space does not survive a buffer refill. Driving a
 * {@code JsonReader} over an {@code InputStream} calls {@code prepareNextBlock()} when the buffer runs out,
 * which shifts the buffer contents and resets the current index — after which a previously captured
 * {@code [start, end)} pair is not merely stale, it is meaningless.
 * <p>
 * <b>Every transport must therefore join before calling.</b> HTTP (feature 04), WebSocket (06) and framed TCP
 * (07) each accumulate a complete body and hand over one array. The size bound that makes accumulating safe
 * is {@code JsonRpcLimits.MAX_BODY_SIZE}, and applying it <i>during</i> accumulation — before a full array
 * exists — is the transport's obligation, not this decoder's (FR-053).
 *
 * <h2>Total on all inputs (FR-080)</h2>
 * {@code decode} never throws for malformed input. A failure is a <b>returned</b> {@link JsonRpcMalformed},
 * because a malformed envelope is expected traffic on this path rather than an exceptional condition, and
 * because a batch needs one outcome per element — which no exception can carry. The only exceptions that
 * escape are the two a caller can only cause by a programming error: a {@code null} array, and an
 * offset/length pair outside it.
 *
 * <h2>What is decoded, and what is not (FR-031)</h2>
 * {@code jsonrpc}, {@code id}, {@code method}, and an error object's {@code code} and {@code message} are
 * decoded. {@code params}, {@code result} and {@code error.data} are left as {@link JsonRpcPayload.Raw} byte
 * ranges — the caller decodes them through its own {@link JsonCodec} once the method has resolved.
 * <p>
 * Decoding is <b>independent of member order</b> (FR-032). That is the whole point: JSON-RPC 2.0 does not
 * constrain it, so {@code params} may legally precede {@code method}, and no codec for {@code params} exists
 * until {@code method} has been read. A single-pass decoder that decoded {@code params} in place would be
 * invalid on that ordering.
 *
 * <h2>Unknown members (FR-033)</h2>
 * Members this implementation does not define are ignored, and <b>every defined member after an ignored one
 * is still read</b>. See {@link #walkObject} for why that is structural here rather than a special case.
 *
 * <h2>No ordering relationship in the answer (FR-046)</h2>
 * Nothing in this decoder establishes an order between a batch's requests and the responses a caller builds.
 * Correlation is by {@code id} alone.
 */
public final class JsonRpcDecoder {
	private JsonRpcDecoder() {}

	private static final String VERSION = "2.0";
	private static final byte[] VERSION_LITERAL = "\"2.0\"".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
	private static final byte[] NULL_LITERAL = "null".getBytes(java.nio.charset.StandardCharsets.US_ASCII);

	private static final JsonCodec<String> STRING_CODEC = JsonCodecs.ofString();

	/**
	 * This module's own reader pool, mirroring {@link JsonUtils}'s. It cannot be shared with {@code JsonUtils}
	 * — that class exposes no reader — and it must not be the <i>same</i> instance, because a payload decode
	 * through {@code JsonUtils.fromJsonBytes} may happen while a walk is in progress.
	 * <p>
	 * This is a pooled reader, not retained state: {@code process(...)} resets it at the start of every
	 * decode, and no value produced by a decode holds a reference to it (FR-022).
	 */
	private static final DslJson<Object> DSL_JSON =
		new DslJson<>(Settings.<Object>withRuntime().includeServiceLoader());
	private static final ThreadLocal<JsonReader<Object>> READERS = ThreadLocal.withInitial(DSL_JSON::newReader);

	/**
	 * Decodes one complete JSON-RPC document.
	 *
	 * @param envelope the whole document, as one contiguous array. Retained by any {@link JsonRpcPayload.Raw}
	 *                 in the result and never written to; a caller that mutates it afterwards invalidates
	 *                 every payload derived from it
	 * @return a {@link JsonRpcDecoded} for a single document, or a {@link JsonRpcBatch} for a top-level array.
	 * A malformed document is a returned {@link JsonRpcMalformed}, never a thrown exception
	 */
	public static JsonRpcInput decode(byte[] envelope) {
		//noinspection ConstantValue - a null array is a programming error, and the NPE says so at the call site
		if (envelope == null) throw new NullPointerException("envelope");
		return decode(envelope, 0, envelope.length);
	}

	/**
	 * Decodes one complete JSON-RPC document from {@code envelope[offset, offset + length)}.
	 * <p>
	 * A non-zero {@code offset} costs <b>one copy</b>: dsl-json's reader always indexes from zero, so the
	 * region is copied out and the resulting payload ranges point into that copy rather than into
	 * {@code envelope}. A transport that can hand over a zero-offset array avoids the copy.
	 *
	 * @param envelope the buffer holding the document; only {@code [offset, offset + length)} is read
	 * @param offset   the index of the document's first byte
	 * @param length   the document's length in bytes — this, not {@code envelope.length}, is what
	 *                 {@code JsonRpcLimits.MAX_BODY_SIZE} bounds
	 * @return a {@link JsonRpcDecoded} for a single document, or a {@link JsonRpcBatch} for a top-level
	 * array. A malformed document is a returned {@link JsonRpcMalformed}, never a thrown exception
	 * @throws IllegalArgumentException unless {@code 0 <= offset} and {@code 0 <= length} and
	 *                                  {@code offset + length <= envelope.length}
	 */
	public static JsonRpcInput decode(byte[] envelope, int offset, int length) {
		//noinspection ConstantValue
		if (envelope == null) throw new NullPointerException("envelope");
		if (offset < 0 || length < 0 || offset > envelope.length - length) {
			throw new IllegalArgumentException(
				"region [" + offset + ", " + offset + " + " + length + ") is out of bounds for an array of " +
				envelope.length);
		}
		// FR-050: the cheapest refusal first, on the region the caller actually asked to decode, before a
		// copy, before the UTF-8 scan, before the depth scan and before the parser. Note this is the LAST
		// line of defence, not the first: a transport must apply the same bound DURING accumulation, since
		// bounding an array that has already been allocated has paid most of the cost already (FR-053)
		if (length > JsonRpcLimits.MAX_BODY_SIZE.toLong()) {
			return malformed(JsonRpcId.NULL, JsonRpcErrors.REQUEST_TOO_LARGE);
		}
		byte[] bytes = offset == 0 && length == envelope.length ?
			envelope :
			Arrays.copyOfRange(envelope, offset, offset + length);
		try {
			return decodeDocument(bytes);
		} catch (Exception e) {
			// FR-080 + FR-089: nothing escapes, and nothing of the offending input is carried outward. The
			// exception JsonUtils/dsl-json builds embeds the remaining bytes in its own message by
			// construction, so it is dropped here rather than mapped into the error object.
			return malformed(JsonRpcId.NULL, JsonRpcErrors.PARSE_ERROR);
		}
	}

	private static JsonRpcInput decodeDocument(byte[] bytes) throws Exception {
		// FR-052: the depth bound is enforced BEFORE the parser ever sees the array, because
		// JsonReader.skip() recurses one frame per level and would exhaust the stack inside itself long
		// before any in-parse check could run. See JsonDepthScanner for the disassembly this rests on.
		//
		// It runs before the UTF-8 scan as well, so that an over-deep document is refused as -32003 whatever
		// else is also wrong with it — the scan is safe on unvalidated bytes, since UTF-8 is
		// self-synchronising and no byte of a multi-byte sequence can collide with '{', '[', '"' or '\'.
		if (JsonDepthScanner.exceedsDepth(bytes, JsonRpcLimits.MAX_JSON_DEPTH)) {
			return malformed(JsonRpcId.NULL, JsonRpcErrors.NESTING_TOO_DEEP);
		}
		if (!isWellFormedUtf8(bytes)) return malformed(JsonRpcId.NULL, JsonRpcErrors.PARSE_ERROR);

		JsonReader<Object> reader = READERS.get().process(bytes, bytes.length);
		byte first = reader.getNextToken();

		// the array path applies the trailing-data check itself, because one of its outcomes stops reading
		// on purpose and must not then be reported as trailing data — see decodeArray
		if (first == '[') return decodeArray(reader, bytes);
		if (first != '{') {
			// not an object and not an array: a bare literal, a leading UTF-8 BOM, or not JSON at all
			return malformed(JsonRpcId.NULL, JsonRpcErrors.PARSE_ERROR);
		}
		JsonRpcDecoded decoded = decodeEnvelope(reader, bytes);
		return trailingDataFollows(reader, bytes) ? malformed(JsonRpcId.NULL, JsonRpcErrors.PARSE_ERROR) : decoded;
	}

	/**
	 * Decodes a top-level JSON array — a batch (§6) — from a reader whose {@code '['} has just been consumed.
	 *
	 * <h4>Every element is decoded independently (FR-038)</h4>
	 * An element that is not a JSON object is not a Request object, so it yields {@code -32600} <b>for that
	 * position only</b> and its siblings are unaffected. That covers a number, a string, a boolean,
	 * {@code null}, and a nested array — which is why {@code [[…]]} is a batch containing one invalid element
	 * rather than a batch of batches, and why {@link JsonRpcBatch} is deliberately not a
	 * {@link JsonRpcMessage}.
	 * <p>
	 * A failure that breaks the <b>array's own structure</b> is different in kind: past the break there are no
	 * element boundaries left to report against, so the exception propagates and the whole document becomes a
	 * single {@code -32700}. That is exactly §7's "rpc call Batch, invalid JSON" example, which answers with
	 * one object rather than an array.
	 *
	 * <h4>The separator is taken from two different places, deliberately</h4>
	 * The object branch and the skip branch leave the cursor in <b>different</b> states, and using the wrong
	 * one for either is the mirror image of the defect the member walk avoids:
	 * <ul>
	 *     <li>after {@link #decodeEnvelope}, the element's closing <code>'}'</code> has been consumed by the member
	 *     walk's final {@code skip()} but the following {@code ','} or {@code ']'} has <b>not</b> — so it is
	 *     read with {@code getNextToken()};</li>
	 *     <li>{@code skip()} on a non-object element consumes the following separator and <b>returns</b> it —
	 *     so calling {@code getNextToken()} as well would swallow the next element's first byte.</li>
	 * </ul>
	 * Both branches end with the cursor just past the separator, which is what lets the loop stay uniform.
	 *
	 * @return a {@link JsonRpcBatch} of one or more elements, or — for an <b>empty</b> array — a single
	 * {@link JsonRpcMalformed}, never an empty batch (FR-039)
	 */
	private static JsonRpcInput decodeArray(JsonReader<Object> reader, byte[] bytes) throws Exception {
		byte token = reader.getNextToken();             // the first element's first byte, or ']'

		if (token == ']') {
			// FR-039: an empty array is a single -32600 and NOT a batch, so the caller renders one object
			// rather than an array. `[]` is itself an Invalid Request on the wire (§6), which is also why
			// JsonRpcBatch refuses an empty list at construction — the wrong answer is unconstructible, not
			// merely untaken
			return trailingDataFollows(reader, bytes) ?
				malformed(JsonRpcId.NULL, JsonRpcErrors.PARSE_ERROR) :
				malformed(JsonRpcId.NULL, JsonRpcErrors.INVALID_REQUEST);
		}

		List<JsonRpcDecoded> elements = new ArrayList<>();
		while (true) {
			// FR-054: refused ON the element that would exceed the bound — before that element and every
			// element after it is decoded or retained. Counting the elements first and checking afterwards
			// would have already paid the cost the bound exists to prevent. One document for the whole
			// batch, not one error per excess element.
			if (elements.size() == JsonRpcLimits.MAX_BATCH_SIZE) {
				// NB no trailing-data check on this path: the rest of the document is left deliberately
				// unread, which is the whole point of refusing here rather than after the list is built.
				// Checking it would report -32700 for input we chose not to look at.
				return malformed(JsonRpcId.NULL, JsonRpcErrors.BATCH_TOO_LARGE);
			}
			byte separator;
			if (token == '{') {
				elements.add(decodeEnvelope(reader, bytes));
				separator = reader.getNextToken();
			} else {
				// not an object, so not a Request object — refused for this position alone (§7)
				elements.add(malformed(JsonRpcId.NULL, JsonRpcErrors.INVALID_REQUEST));
				separator = reader.skip();
			}

			if (separator == ']') break;
			if (separator != ',') {
				// the array's own structure is broken, so no per-element outcome is trustworthy from here
				return malformed(JsonRpcId.NULL, JsonRpcErrors.PARSE_ERROR);
			}
			token = reader.getNextToken();              // the next element's first byte
		}
		return trailingDataFollows(reader, bytes) ?
			malformed(JsonRpcId.NULL, JsonRpcErrors.PARSE_ERROR) :
			new JsonRpcBatch(elements);
	}

	// ---------------------------------------------------------------------------------------------------
	// The member walk.
	// ---------------------------------------------------------------------------------------------------

	/**
	 * Walks one JSON object, handing every member's key and the <b>undecoded byte range of its value</b> to
	 * {@code sink}. Decodes nothing.
	 *
	 * <h4>The capture rule</h4>
	 * Transcribed from {@code DeferredDecodingTest.captureMembers} in {@code extra/util-json}'s tests, where
	 * each of its four facts was derived from a <i>failing</i> assertion against an 18-fixture corpus rather
	 * than from reading dsl-json's source. Do not re-derive them:
	 * <ul>
	 *     <li>{@link JsonReader#readKey()} consumes the key, the {@code ':'} and the whitespace after it, and
	 *     leaves {@code last()} on the value's <b>first</b> byte. So {@code start = getCurrentIndex() - 1},
	 *     with <b>no</b> left-trim.</li>
	 *     <li>{@link JsonReader#skip()} skips one whole value <b>and then consumes the following separator</b>,
	 *     returning it. Every subsequent decision must use that <b>returned</b> byte. Calling
	 *     {@code getNextToken()} again to "find" the separator consumes the next key's opening quote instead,
	 *     and silently drops every member after the first — that is exactly the defect
	 *     {@code AbstractMapJsonCodec.read} carries in its {@code skip(); continue;} branch, and the reason
	 *     this walk is bespoke rather than built on that template (FR-081, research Decision 2).</li>
	 *     <li>{@code end = getCurrentIndex() - 1} is one past the value <b>plus any whitespace</b> that sat
	 *     between the value and the separator, so it needs a <b>right-trim</b> of {@code ' '}, {@code '\t'},
	 *     {@code '\n'}, {@code '\r'}. For <code>{ "params" : [ 1 , 2 ] }</code> the raw slice is
	 *     {@code "[ 1 , 2 ] "} and the trimmed one is {@code "[ 1 , 2 ]"}. Without the trim the sub-range
	 *     decode leaves trailing data unconsumed, which {@code JsonUtils.fromJsonBytes} reports as
	 *     {@code MalformedDataException}.</li>
	 *     <li>An <b>empty object</b> {@code {}} has no members and {@code readKey()} throws on it, so the loop
	 *     is guarded by <code>if (getNextToken() != '}')</code>.</li>
	 * </ul>
	 *
	 * <h4>Why FR-033 is structural here</h4>
	 * A member this implementation does not define takes <b>exactly the same path</b> as one it does: the
	 * value is skipped and the separator is taken from {@code skip()}'s return, whether or not {@code sink}
	 * cares about the key. There is no "skip branch" to get wrong, which is what makes "every defined member
	 * after an ignored one is still read" a property of the loop's shape rather than a case to remember.
	 *
	 * @param reader positioned so that the <b>next</b> token is the first key's opening quote, or the closing
	 *               brace of an empty object — that is, the object's <code>'{'</code> has just been consumed
	 * @param bytes  the very array the reader was {@code process(...)}ed with; the offsets index into it
	 */
	private static void walkObject(JsonReader<Object> reader, byte[] bytes, MemberSink sink) throws Exception {
		if (reader.getNextToken() != '}') {             // an empty object has no members; readKey() would throw
			while (true) {
				String key = reader.readKey();          // consumes the key, the ':' and the whitespace after it
				int start = reader.getCurrentIndex() - 1;   // last() is already the value's first byte
				byte next = reader.skip();              // skips the value AND consumes the following ',' or '}'
				int end = rtrimJsonWhitespace(bytes, start, reader.getCurrentIndex() - 1);
				sink.member(key, start, end);
				if (next != ',') break;
				reader.getNextToken();                  // position on the '"' of the next key — ONCE, not twice
			}
		}
	}

	/** Receives one member's key and the {@code [start, end)} range of its still-undecoded value. */
	private interface MemberSink {
		void member(String key, int start, int end);
	}

	/**
	 * Trims trailing JSON whitespace off a captured range. Load-bearing, not cosmetic — see the third fact of
	 * the capture rule on {@link #walkObject}.
	 */
	private static int rtrimJsonWhitespace(byte[] bytes, int start, int end) {
		int e = end;
		while (e > start) {
			byte b = bytes[e - 1];
			if (b != ' ' && b != '\t' && b != '\n' && b != '\r') break;
			e--;
		}
		return e;
	}

	// ---------------------------------------------------------------------------------------------------
	// Envelope classification.
	// ---------------------------------------------------------------------------------------------------

	/**
	 * The captured ranges of the six defined envelope members.
	 * <p>
	 * Slots are <b>write-once</b>: a repeated member sets {@link #duplicate} instead of overwriting, so "the
	 * last occurrence wins" — the behaviour that lets two implementations read one document differently —
	 * is unreachable by construction rather than merely untaken (FR-034).
	 */
	private static final class Envelope implements MemberSink {
		static final int JSONRPC = 0, ID = 1, METHOD = 2, PARAMS = 3, RESULT = 4, ERROR = 5;

		final int[] starts = {-1, -1, -1, -1, -1, -1};
		final int[] ends = new int[6];

		/** Any defined member arrived more than once. */
		boolean duplicate;
		/** The repeated member was {@code id} itself, so no identifier can be trusted (FR-037). */
		boolean idAmbiguous;

		@Override
		public void member(String key, int start, int end) {
			int slot = switch (key) {
				case "jsonrpc" -> JSONRPC;
				case "id" -> ID;
				case "method" -> METHOD;
				case "params" -> PARAMS;
				case "result" -> RESULT;
				case "error" -> ERROR;
				// an unknown member: its value was already skipped by the walk, so there is nothing to do.
				// This branch existing and doing nothing IS the FR-033 behaviour, and a repeated unknown
				// member stays as ignorable as a single one
				default -> -1;
			};
			if (slot < 0) return;
			if (starts[slot] >= 0) {
				duplicate = true;
				if (slot == ID) idAmbiguous = true;
				return;
			}
			starts[slot] = start;
			ends[slot] = end;
		}

		boolean has(int slot) {return starts[slot] >= 0;}
	}

	/**
	 * Decodes <b>one envelope object</b>, from a reader whose <code>'{'</code> has just been consumed.
	 * <p>
	 * This is the per-element entry point: a top-level array decodes each of its elements through here, so
	 * every rule below applies per element rather than per document.
	 */
	private static JsonRpcDecoded decodeEnvelope(JsonReader<Object> reader, byte[] bytes) throws Exception {
		Envelope members = new Envelope();
		walkObject(reader, bytes, members);
		return classify(bytes, members);
	}

	/**
	 * Turns a captured set of envelope members into a message, or into the failure that says why it is not
	 * one. Decodes no payload.
	 */
	private static JsonRpcDecoded classify(byte[] bytes, Envelope members) throws Exception {
		if (members.duplicate) {
			// neither occurrence is authoritative, so the document is refused rather than resolved (FR-034).
			// When it is `id` that repeated, echoing either value would invite exactly the mis-correlation
			// the refusal exists to prevent, so no identifier is recovered
			return malformed(members.idAmbiguous ? JsonRpcId.NULL : recoverId(bytes, members),
				JsonRpcErrors.INVALID_REQUEST);
		}

		if (!isVersion2(bytes, members, Envelope.JSONRPC)) {
			return malformed(recoverId(bytes, members), JsonRpcErrors.INVALID_REQUEST);
		}

		JsonRpcId id = null;
		if (members.has(Envelope.ID)) {
			id = parseId(bytes, members.starts[Envelope.ID], members.ends[Envelope.ID]);
			// the malformed member IS the identifier, so there is nothing to recover (FR-036, FR-037)
			if (id == null) return malformed(JsonRpcId.NULL, JsonRpcErrors.INVALID_REQUEST);
		}

		// `method` is what makes a document a Request or a Notification (§4); everything else with a version
		// member is read as an attempted Response
		return members.has(Envelope.METHOD) ?
			asRequestOrNotification(bytes, members, id) :
			asResponse(bytes, members, id);
	}

	/** @param id the decoded identifier, or {@code null} when the envelope carried no {@code id} member */
	private static JsonRpcDecoded asRequestOrNotification(byte[] bytes, Envelope members, @Nullable JsonRpcId id) {
		JsonRpcId recovered = id == null ? JsonRpcId.NULL : id;

		String method = readJsonString(bytes, members.starts[Envelope.METHOD], members.ends[Envelope.METHOD]);
		// an empty method is refused exactly like a non-string one: the value model forbids it, and a
		// document that cannot become a message must become an error rather than an exception
		if (method == null || method.isEmpty()) {
			return malformed(recovered, JsonRpcErrors.INVALID_REQUEST);
		}
		if (!isStructuredOrNull(bytes, members, Envelope.PARAMS)) {
			return malformed(recovered, JsonRpcErrors.INVALID_REQUEST);
		}

		JsonRpcPayload params = payload(bytes, members, Envelope.PARAMS);
		// no id member at all is what makes it a notification — that is the whole of FR-011
		return id == null ? new JsonRpcNotification(method, params) : new JsonRpcRequest(id, method, params);
	}

	/** @param id the decoded identifier, or {@code null} when the envelope carried no {@code id} member */
	private static JsonRpcDecoded asResponse(byte[] bytes, Envelope members, @Nullable JsonRpcId id)
		throws Exception {
		boolean hasResult = members.has(Envelope.RESULT);
		boolean hasError = members.has(Envelope.ERROR);

		if (hasResult == hasError) {
			// both, or neither: a peer's Response object violating §5 is not our internal error (FR-013).
			// `result` deliberately carries NO structural restriction here — §5 permits any JSON value, and
			// that asymmetry with `params` is intentional (FR-086)
			return malformed(id == null ? JsonRpcId.NULL : id, JsonRpcErrors.INVALID_RESPONSE);
		}
		if (id == null) {
			// §5 makes id required on a Response; its absence is the peer's fault, not a notification
			return malformed(JsonRpcId.NULL, JsonRpcErrors.INVALID_RESPONSE);
		}
		if (hasResult) {
			return JsonRpcResponse.ofResult(id, payload(bytes, members, Envelope.RESULT));
		}

		JsonRpcError error =
			decodeErrorObject(bytes, members.starts[Envelope.ERROR], members.ends[Envelope.ERROR]);
		return error == null ?
			malformed(id, JsonRpcErrors.INVALID_RESPONSE) :
			JsonRpcResponse.ofError(id, error);
	}

	/** The captured ranges of an error object's three members. Write-once, for the same reason as {@link Envelope}. */
	private static final class ErrorObject implements MemberSink {
		static final int CODE = 0, MESSAGE = 1, DATA = 2;

		final int[] starts = {-1, -1, -1};
		final int[] ends = new int[3];
		boolean duplicate;

		@Override
		public void member(String key, int start, int end) {
			int slot = switch (key) {
				case "code" -> CODE;
				case "message" -> MESSAGE;
				case "data" -> DATA;
				default -> -1;              // unknown error-object member: skipped by the walk, nothing to do
			};
			if (slot < 0) return;
			if (starts[slot] >= 0) {
				duplicate = true;
				return;
			}
			starts[slot] = start;
			ends[slot] = end;
		}

		boolean has(int slot) {return starts[slot] >= 0;}
	}

	/**
	 * Decodes an error object out of its captured slice, leaving {@code data} undecoded (FR-031).
	 * <p>
	 * A <b>second pass</b> over a copy of the slice, rather than a descent during the outer walk: dsl-json's
	 * reader always indexes from zero, so a nested walk needs its own array, and keeping the outer loop
	 * uniform is what makes the capture rule above the only place the cursor contract is relied on. The copy
	 * costs one allocation per error response and becomes the array a {@code data} payload points into — the
	 * decoder owns it and never writes to it, so the payload's retention contract still holds.
	 *
	 * @return {@code null} if the slice is not a well-formed error object
	 */
	private static @Nullable JsonRpcError decodeErrorObject(byte[] bytes, int start, int end) throws Exception {
		byte[] slice = Arrays.copyOfRange(bytes, start, end);
		JsonReader<Object> reader = DSL_JSON.newReader().process(slice, slice.length);
		if (reader.getNextToken() != '{') return null;

		ErrorObject members = new ErrorObject();
		walkObject(reader, slice, members);
		if (members.duplicate) return null;
		if (!members.has(ErrorObject.CODE) || !members.has(ErrorObject.MESSAGE)) return null;

		Integer code = readJsonInt(slice, members.starts[ErrorObject.CODE], members.ends[ErrorObject.CODE]);
		String message = readJsonString(slice, members.starts[ErrorObject.MESSAGE], members.ends[ErrorObject.MESSAGE]);
		if (code == null || message == null) return null;

		// ofAny, not of: a peer's reserved-range code is information the peer meant to convey (FR-016)
		return JsonRpcErrors.ofAny(code, message, payload(slice, members, ErrorObject.DATA));
	}

	// ---------------------------------------------------------------------------------------------------
	// Member values.
	// ---------------------------------------------------------------------------------------------------

	private static JsonRpcPayload payload(byte[] bytes, Envelope members, int slot) {
		return members.has(slot) ?
			new JsonRpcPayload.Raw(bytes, members.starts[slot], members.ends[slot]) :
			JsonRpcPayload.absent();
	}

	private static JsonRpcPayload payload(byte[] bytes, ErrorObject members, int slot) {
		return members.has(slot) ?
			new JsonRpcPayload.Raw(bytes, members.starts[slot], members.ends[slot]) :
			JsonRpcPayload.absent();
	}

	/** Whether the {@code jsonrpc} member is present and is exactly the string {@code "2.0"} (FR-035). */
	private static boolean isVersion2(byte[] bytes, Envelope members, int slot) {
		if (!members.has(slot)) return false;
		int start = members.starts[slot], end = members.ends[slot];
		if (Arrays.equals(bytes, start, end, VERSION_LITERAL, 0, VERSION_LITERAL.length)) return true;
		// an escaped spelling such as "2\u002E0" is the same JSON string, so fall back to a real decode
		return VERSION.equals(readJsonString(bytes, start, end));
	}

	/**
	 * Whether {@code params} may stand as written: absent, the JSON literal {@code null}, an array, or an
	 * object (§4.2, FR-086).
	 * <p>
	 * A bare literal — {@code 42}, {@code "x"}, {@code true} — is refused. <b>There is deliberately no
	 * equivalent check on {@code result}</b>: §5 permits any JSON value there, and the asymmetry is a
	 * decision, not an omission. Do not "tidy" the two into symmetry.
	 */
	private static boolean isStructuredOrNull(byte[] bytes, Envelope members, int slot) {
		if (!members.has(slot)) return true;
		int start = members.starts[slot], end = members.ends[slot];
		if (end <= start) return false;
		byte first = bytes[start];
		if (first == '[' || first == '{') return true;
		return Arrays.equals(bytes, start, end, NULL_LITERAL, 0, NULL_LITERAL.length);
	}

	/**
	 * Parses an {@code id} member's captured range into one of the three forms of {@link JsonRpcId}.
	 *
	 * @return {@code null} if the value is not a legal identifier — fractional, boolean, structured, or an
	 * integral number outside 64-bit signed range (FR-036)
	 */
	private static @Nullable JsonRpcId parseId(byte[] bytes, int start, int end) {
		if (end <= start) return null;
		byte first = bytes[start];
		if (first == '"') {
			String value = readJsonString(bytes, start, end);
			return value == null ? null : new JsonRpcId.Str(value);
		}
		if (Arrays.equals(bytes, start, end, NULL_LITERAL, 0, NULL_LITERAL.length)) return JsonRpcId.NULL;
		if (first != '-' && (first < '0' || first > '9')) return null;   // true, false, '{', '[' — all refused
		for (int i = start; i < end; i++) {
			byte b = bytes[i];
			// a fraction or an exponent makes it a non-integral number, which §4 does not allow as an id
			if (b == '.' || b == 'e' || b == 'E') return null;
		}
		try {
			return new JsonRpcId.Num(Long.parseLong(new String(bytes, start, end - start,
				java.nio.charset.StandardCharsets.US_ASCII)));
		} catch (NumberFormatException e) {
			return null;                                 // out of 64-bit signed range, or not a number at all
		}
	}

	/**
	 * The identifier recovered before a failure, or {@link JsonRpcId#NULL} when none could be (FR-037).
	 * Phase 4 (US2) is where this earns its keep.
	 */
	private static JsonRpcId recoverId(byte[] bytes, Envelope members) {
		if (!members.has(Envelope.ID)) return JsonRpcId.NULL;
		JsonRpcId id = parseId(bytes, members.starts[Envelope.ID], members.ends[Envelope.ID]);
		return id == null ? JsonRpcId.NULL : id;
	}

	/**
	 * @return the decoded string, or {@code null} if the range is not a well-formed JSON string
	 * <p>
	 * The caught exception is <b>discarded, never inspected</b>: {@code JsonUtils} builds its message as
	 * {@code "Unexpected JSON data: " + <the remaining input bytes>}, so anything derived from it would carry
	 * peer input into whatever error this failure becomes (FR-089). {@code RuntimeException} is caught
	 * alongside the checked one because dsl-json does not fail uniformly — a byte it cannot read may surface
	 * as an {@code ArrayIndexOutOfBoundsException}. The UTF-8 pre-scan makes that unreachable from here, but
	 * a member that cannot be read is a {@code -32600}, and letting it escape to the catch-all would misreport
	 * it as {@code -32700}.
	 */
	private static @Nullable String readJsonString(byte[] bytes, int start, int end) {
		if (end - start < 2 || bytes[start] != '"') return null;
		try {
			return JsonUtils.fromJsonBytes(STRING_CODEC, Arrays.copyOfRange(bytes, start, end));
		} catch (MalformedDataException | RuntimeException e) {
			return null;
		}
	}

	/** @return the decoded integer, or {@code null} if the range is not a well-formed JSON integer */
	private static @Nullable Integer readJsonInt(byte[] bytes, int start, int end) {
		if (end <= start) return null;
		try {
			return Integer.parseInt(new String(bytes, start, end - start,
				java.nio.charset.StandardCharsets.US_ASCII));
		} catch (NumberFormatException e) {
			return null;
		}
	}

	/**
	 * Whether {@code bytes} is well-formed UTF-8, per RFC 3629 — rejecting overlong encodings, encoded
	 * UTF-16 surrogates, and anything above U+10FFFF (FR-084).
	 *
	 * <h4>Why this cannot be delegated to the parser</h4>
	 * Established by probing dsl-json 1.10.0, not assumed. {@code JsonReader.skip()} validates <b>no</b>
	 * UTF-8 at all, so a payload this layer never decodes carries whatever bytes it likes; and where
	 * dsl-json <i>does</i> decode a string it is inconsistent — the <b>overlong</b> two-byte encoding of
	 * {@code '/'} ({@code C0 AF}) silently yields {@code "/"}, and {@code ED A0 80} yields a lone UTF-16
	 * surrogate. Two different byte sequences producing one string is exactly the encoding confusion this
	 * requirement exists to stop: a filter that inspects the decoded form and a peer that reads the raw
	 * bytes disagree about the same document. (A bare {@code FF} does not even fail cleanly — it surfaces
	 * as an {@code ArrayIndexOutOfBoundsException}.)
	 *
	 * <h4>Why the whole array</h4>
	 * JSON outside a string literal is ASCII, so a non-ASCII byte there is malformed JSON anyway. Scanning
	 * the whole array is simpler than scanning only string interiors, strictly stronger, and allocates
	 * nothing. It costs one linear pass, which the depth pre-scan of FR-052 will also need — the two can be
	 * fused if that ever matters.
	 * <p>
	 * A leading byte-order mark is <b>not</b> caught here: {@code EF BB BF} is well-formed UTF-8 for
	 * U+FEFF. It is refused by {@link #decodeDocument} instead, which requires the first token to be
	 * <code>'{'</code> or {@code '['} — so a BOM is rejected rather than stripped, with no special case.
	 */
	private static boolean isWellFormedUtf8(byte[] bytes) {
		int i = 0;
		while (i < bytes.length) {
			int lead = bytes[i] & 0xFF;
			if (lead < 0x80) {
				i++;
				continue;
			}
			int trailing;
			int secondLow, secondHigh;      // the first continuation byte is the one that pins the edge cases
			if (lead >= 0xC2 && lead <= 0xDF) {
				trailing = 1; secondLow = 0x80; secondHigh = 0xBF;
			} else if (lead == 0xE0) {
				trailing = 2; secondLow = 0xA0; secondHigh = 0xBF;      // no 3-byte overlong
			} else if (lead >= 0xE1 && lead <= 0xEC || lead == 0xEE || lead == 0xEF) {
				trailing = 2; secondLow = 0x80; secondHigh = 0xBF;
			} else if (lead == 0xED) {
				trailing = 2; secondLow = 0x80; secondHigh = 0x9F;      // no encoded UTF-16 surrogate
			} else if (lead == 0xF0) {
				trailing = 3; secondLow = 0x90; secondHigh = 0xBF;      // no 4-byte overlong
			} else if (lead >= 0xF1 && lead <= 0xF3) {
				trailing = 3; secondLow = 0x80; secondHigh = 0xBF;
			} else if (lead == 0xF4) {
				trailing = 3; secondLow = 0x80; secondHigh = 0x8F;      // nothing above U+10FFFF
			} else {
				return false;               // 0x80…0xC1 and 0xF5…0xFF are never lead bytes
			}
			if (i + trailing >= bytes.length) return false;             // truncated at the end of the array
			int second = bytes[i + 1] & 0xFF;
			if (second < secondLow || second > secondHigh) return false;
			for (int k = 2; k <= trailing; k++) {
				int continuation = bytes[i + k] & 0xFF;
				if (continuation < 0x80 || continuation > 0xBF) return false;
			}
			i += trailing + 1;
		}
		return true;
	}

	/** Whether anything other than JSON whitespace follows the document the reader has just consumed. */
	private static boolean trailingDataFollows(JsonReader<Object> reader, byte[] bytes) {
		for (int i = reader.getCurrentIndex(); i < bytes.length; i++) {
			byte b = bytes[i];
			if (b != ' ' && b != '\t' && b != '\n' && b != '\r') return true;
		}
		return false;
	}

	private static JsonRpcMalformed malformed(JsonRpcId id, JsonRpcError error) {
		return new JsonRpcMalformed(id, error);
	}
}
