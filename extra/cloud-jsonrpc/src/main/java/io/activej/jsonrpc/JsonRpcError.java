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

import java.util.Objects;

/**
 * The JSON-RPC 2.0 Error object of §5.1: an integer {@code code}, a string {@code message}, and an optional
 * {@code data} payload.
 * <p>
 * Rendered as <code>{"code":&lt;int&gt;,"message":&lt;string&gt;[,"data":&lt;value&gt;]}</code>, in that
 * member order (FR-041). An absent {@code data} member is omitted entirely, never emitted as {@code null}.
 *
 * <h2>Constructing one</h2>
 * The canonical constructor validates only what is structurally required — a non-null message and a non-null
 * payload — because it is also the path a peer's error takes when it is decoded, and a peer's code may
 * legitimately be anything. Use {@link JsonRpcErrors#of} to build an <i>application</i> error: it additionally
 * refuses the reserved range {@code -32768 … -32000}, which is the range whose meanings the specification and
 * this implementation own (FR-016).
 *
 * <h2>What may go in the message (FR-089)</h2>
 * The {@code message} of an error that will be <b>encoded</b> must be one of the fixed strings of
 * {@link JsonRpcErrors}, and {@code data} must never be populated from a caught exception. This is not a style
 * preference: {@code JsonUtils.fromJsonBytes} builds its {@code MalformedDataException} as
 * {@code "Unexpected JSON data: " + <the remaining input bytes>}, so any code that funnels a caught message
 * into an outgoing error object echoes a peer's input — possibly its secrets — to that peer's counterparty.
 * Diagnostic detail belongs on a thrown {@link JsonRpcException}, which stays local.
 *
 * @param code    the error code; any integer. {@link JsonRpcErrors#isReserved} identifies the reserved range
 * @param message a short human-readable description; never {@code null} (FR-014)
 * @param data    additional information about the error, or {@link JsonRpcPayload#absent()} when the member
 *                is omitted; never {@code null}
 */
public record JsonRpcError(int code, String message, JsonRpcPayload data) {
	/** Accepts any {@code code}, including a reserved one: this is also the path a peer's error takes. */
	public JsonRpcError {
		Objects.requireNonNull(message, "an error object must carry a message");
		Objects.requireNonNull(data, "data must be JsonRpcPayload.absent(), never null");
	}

	/** Whether this error's code is in the reserved range {@code -32768 … -32000}. */
	public boolean isReserved() {
		return JsonRpcErrors.isReserved(code);
	}

	/** Whether this error's code is in the implementation-defined server-error range {@code -32099 … -32000}. */
	public boolean isServerError() {
		return JsonRpcErrors.isServerError(code);
	}
}
