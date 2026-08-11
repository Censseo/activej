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

import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * A JSON-RPC 2.0 Response object (§5): the single answer to a {@link JsonRpcRequest}, carrying <b>exactly
 * one</b> of {@code result} and {@code error}.
 * <p>
 * Rendered as <code>{"jsonrpc":"2.0","id":&lt;id&gt;,"result":&lt;value&gt;}</code> or
 * <code>{"jsonrpc":"2.0","id":&lt;id&gt;,"error":{…}}</code>, in that member order (FR-041).
 *
 * <h2>Exactly one of result / error (FR-013)</h2>
 * The canonical constructor is the <b>validator</b>, not the ergonomic entry point — use
 * {@link #ofResult} and {@link #ofError}. Its check is <b>unconditional</b>, not gated behind
 * {@code Checks}: on the client side a response is built from data a peer sent, so the invariant guards
 * untrusted input rather than a programming error. (A peer's response that violates §5 never reaches this
 * constructor at all — the decoder classifies it as {@code -32004 Invalid response} and returns it.)
 * <p>
 * "Result present" means the payload is not {@link JsonRpcPayload#absent()}. A result that is the JSON
 * literal {@code null} <b>is</b> present: §5 permits any JSON value, and conflating the two would turn a
 * legal response into a refusal.
 *
 * @param id     the identifier echoed from the request; never {@code null}. {@link JsonRpcId#NULL} for a
 *               failure whose request identifier could not be recovered (FR-037)
 * @param result the result payload, or {@link JsonRpcPayload#absent()} when this is an error response; never
 *               {@code null}
 * @param error  the error object, or {@code null} when this is a successful response
 */
public record JsonRpcResponse(JsonRpcId id, JsonRpcPayload result, @Nullable JsonRpcError error)
	implements JsonRpcMessage {

	/**
	 * The validator: a response carries <b>exactly one</b> of {@code result} and {@code error} (FR-013).
	 * Unconditional, because on the client side this guards a peer's decoded data, not a programming error.
	 *
	 * @throws IllegalArgumentException if both are present, or neither is
	 */
	public JsonRpcResponse {
		Objects.requireNonNull(id, "id must be a JsonRpcId; use JsonRpcId.NULL for an unrecoverable id");
		Objects.requireNonNull(result, "result must be JsonRpcPayload.absent(), never null");
		if (!result.isAbsent() && error != null) {
			throw new IllegalArgumentException("a response carries exactly one of result and error, not both");
		}
		if (result.isAbsent() && error == null) {
			throw new IllegalArgumentException("a response carries exactly one of result and error, not neither");
		}
	}

	/**
	 * A successful response.
	 *
	 * @throws IllegalArgumentException if {@code result} is {@link JsonRpcPayload#absent()} — an absent result
	 *                                  is how an <i>error</i> response is expressed, so it cannot be a success
	 */
	public static JsonRpcResponse ofResult(JsonRpcId id, JsonRpcPayload result) {
		return new JsonRpcResponse(id, result, null);
	}

	/** A failed response. */
	public static JsonRpcResponse ofError(JsonRpcId id, JsonRpcError error) {
		return new JsonRpcResponse(id, JsonRpcPayload.absent(), Objects.requireNonNull(error, "error"));
	}

	/** Whether this response carries an error rather than a result. */
	public boolean isError() {
		return error != null;
	}
}
