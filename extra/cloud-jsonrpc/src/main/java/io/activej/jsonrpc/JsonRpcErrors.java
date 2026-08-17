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

import io.activej.common.annotation.StaticFactories;

import java.util.List;

/**
 * The named error codes of JSON-RPC 2.0 §5.1, the four this implementation allocates, the two range
 * predicates, and the two construction paths (FR-015, FR-016).
 *
 * <h2>The nine constants</h2>
 * <table border="1">
 *     <caption>named error codes</caption>
 *     <tr><th>Constant</th><th>Code</th><th>Message</th><th>Raised by</th></tr>
 *     <tr><td>{@link #PARSE_ERROR}</td><td>{@code -32700}</td>
 *         <td>{@code Parse error}</td><td>the decoder</td></tr>
 *     <tr><td>{@link #INVALID_REQUEST}</td><td>{@code -32600}</td>
 *         <td>{@code Invalid Request}</td><td>the decoder</td></tr>
 *     <tr><td>{@link #METHOD_NOT_FOUND}</td><td>{@code -32601}</td>
 *         <td>{@code Method not found}</td><td>the dispatcher</td></tr>
 *     <tr><td>{@link #INVALID_PARAMS}</td><td>{@code -32602}</td>
 *         <td>{@code Invalid params}</td><td>the dispatcher</td></tr>
 *     <tr><td>{@link #INTERNAL_ERROR}</td><td>{@code -32603}</td>
 *         <td>{@code Internal error}</td><td>the dispatcher</td></tr>
 *     <tr><td>{@link #REQUEST_TOO_LARGE}</td><td>{@code -32001}</td>
 *         <td>{@code Request too large}</td><td>the decoder and the transports</td></tr>
 *     <tr><td>{@link #BATCH_TOO_LARGE}</td><td>{@code -32002}</td>
 *         <td>{@code Batch too large}</td><td>the decoder</td></tr>
 *     <tr><td>{@link #NESTING_TOO_DEEP}</td><td>{@code -32003}</td>
 *         <td>{@code Nesting too deep}</td><td>the decoder</td></tr>
 *     <tr><td>{@link #INVALID_RESPONSE}</td><td>{@code -32004}</td>
 *         <td>{@code Invalid response}</td><td>the decoder, client side</td></tr>
 * </table>
 * The last four sit inside the {@code -32099 … -32000} range §5.1 reserves for implementation-defined server
 * errors. <b>They are published contract</b>: changing the meaning of one later is a breaking change and
 * needs a {@code CHANGELOG.md} entry. A peer that receives {@code -32600} cannot tell "your envelope was
 * malformed" from "your envelope was too big", and the two have different remedies — which is why they are
 * distinct codes rather than one.
 *
 * <h2>Why the two factories differ (research Decision 12)</h2>
 * {@link #of} is application-facing and <b>refuses</b> a reserved code; {@link #ofAny} accepts any code and is
 * how a peer's error is rebuilt during decoding. The two directions have opposite failure modes. Locally, an
 * application that picks {@code -32601} for its own "user not found" produces a document every client will
 * read as "method not found" — worth refusing at the source. Remotely, refusing a peer's reserved-range error
 * would discard exactly the information the peer meant to convey, and turn a well-formed error response into
 * a second error.
 */
@StaticFactories(JsonRpcError.class)
public final class JsonRpcErrors {
	private JsonRpcErrors() {}

	/** The lowest code of the range reserved by JSON-RPC 2.0 §5.1, inclusive. */
	public static final int RESERVED_MIN = -32768;
	/** The highest code of the range reserved by JSON-RPC 2.0 §5.1, inclusive. */
	public static final int RESERVED_MAX = -32000;
	/** The lowest code of the implementation-defined server-error sub-range, inclusive. */
	public static final int SERVER_ERROR_MIN = -32099;
	/** The highest code of the implementation-defined server-error sub-range, inclusive. */
	public static final int SERVER_ERROR_MAX = -32000;

	/** {@code -32700} — invalid JSON was received; the document could not be parsed. */
	public static final JsonRpcError PARSE_ERROR = predefined(-32700, "Parse error");
	/** {@code -32600} — the document is valid JSON but not a valid Request object. */
	public static final JsonRpcError INVALID_REQUEST = predefined(-32600, "Invalid Request");
	/** {@code -32601} — the method does not exist, or is not available. */
	public static final JsonRpcError METHOD_NOT_FOUND = predefined(-32601, "Method not found");
	/** {@code -32602} — the method's parameters are invalid. */
	public static final JsonRpcError INVALID_PARAMS = predefined(-32602, "Invalid params");
	/** {@code -32603} — an internal JSON-RPC error. */
	public static final JsonRpcError INTERNAL_ERROR = predefined(-32603, "Internal error");

	/** {@code -32001} — the envelope exceeded {@code JsonRpcLimits.maxBodySize}. */
	public static final JsonRpcError REQUEST_TOO_LARGE = predefined(-32001, "Request too large");
	/** {@code -32002} — the batch exceeded {@code JsonRpcLimits.maxBatchSize}. */
	public static final JsonRpcError BATCH_TOO_LARGE = predefined(-32002, "Batch too large");
	/** {@code -32003} — the envelope exceeded {@code JsonRpcLimits.maxJsonDepth}. */
	public static final JsonRpcError NESTING_TOO_DEEP = predefined(-32003, "Nesting too deep");
	/** {@code -32004} — a peer's Response object violates §5 (both or neither of {@code result}/{@code error}). */
	public static final JsonRpcError INVALID_RESPONSE = predefined(-32004, "Invalid response");

	/** The nine named codes, in declaration order — the closed key set of a per-method error breakdown. */
	public static List<JsonRpcError> named() {
		return List.of(
			PARSE_ERROR, INVALID_REQUEST, METHOD_NOT_FOUND, INVALID_PARAMS, INTERNAL_ERROR,
			REQUEST_TOO_LARGE, BATCH_TOO_LARGE, NESTING_TOO_DEEP, INVALID_RESPONSE);
	}

	/** Whether {@code code} is inside the range {@code -32768 … -32000} reserved by JSON-RPC 2.0 §5.1. */
	public static boolean isReserved(int code) {
		return code >= RESERVED_MIN && code <= RESERVED_MAX;
	}

	/**
	 * Whether {@code code} is inside {@code -32099 … -32000}, the sub-range §5.1 reserves for
	 * implementation-defined server errors. Every server error is also {@linkplain #isReserved reserved}.
	 */
	public static boolean isServerError(int code) {
		return code >= SERVER_ERROR_MIN && code <= SERVER_ERROR_MAX;
	}

	/**
	 * Builds an <b>application</b> error object.
	 *
	 * @throws IllegalArgumentException if {@code code} is inside the reserved range {@code -32768 … -32000},
	 *                                  whose meanings the specification and this implementation own (FR-016)
	 */
	public static JsonRpcError of(int code, String message) {
		return of(code, message, JsonRpcPayload.absent());
	}

	/**
	 * Builds an <b>application</b> error object carrying a {@code data} payload.
	 *
	 * @throws IllegalArgumentException if {@code code} is inside the reserved range {@code -32768 … -32000}
	 */
	public static JsonRpcError of(int code, String message, JsonRpcPayload data) {
		if (isReserved(code)) {
			throw new IllegalArgumentException(
				"code " + code + " is inside the range " + RESERVED_MIN + " … " + RESERVED_MAX +
				" reserved by JSON-RPC 2.0 §5.1; use a code outside it, or JsonRpcErrors.ofAny when rebuilding " +
				"a peer's error");
		}
		return new JsonRpcError(code, message, data);
	}

	/**
	 * Builds an error object with <b>any</b> code, reserved ones included.
	 * <p>
	 * <b>Not part of the supported application-facing surface.</b> This is the decode path — the way a peer's
	 * error object is rebuilt verbatim — and the only way to construct one of the predefined errors with a
	 * {@code data} payload. Application code uses {@link #of}, which refuses the reserved range.
	 * ({@code @ExposedInternals} in intent; the annotation targets types, so this is stated here instead.)
	 */
	public static JsonRpcError ofAny(int code, String message, JsonRpcPayload data) {
		return new JsonRpcError(code, message, data);
	}

	private static JsonRpcError predefined(int code, String message) {
		return new JsonRpcError(code, message, JsonRpcPayload.absent());
	}
}
