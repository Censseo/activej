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
 * A JSON-RPC 2.0 Request object (§4): an identified call that expects exactly one response.
 * <p>
 * Rendered as <code>{"jsonrpc":"2.0","id":&lt;id&gt;,"method":&lt;string&gt;[,"params":&lt;value&gt;]}</code>,
 * in that member order (FR-041). An absent {@code params} member is omitted entirely, never emitted as
 * {@code null}.
 * <p>
 * A request <b>always</b> has an identifier; the absence of one is a {@link JsonRpcNotification}, a distinct
 * type (FR-011).
 *
 * @param id     the correlation key echoed by the response; never {@code null}
 * @param method the method name; never {@code null}, never empty
 * @param params the arguments, still undecoded — {@link JsonRpcPayload#absent()} when the member was omitted;
 *               never {@code null}. When present and not the JSON literal {@code null} it is an array or an
 *               object, the two structured forms of §4.2 — the decoder enforces that (FR-086)
 */
public record JsonRpcRequest(JsonRpcId id, String method, JsonRpcPayload params) implements JsonRpcMessage {
	/** @throws IllegalArgumentException if {@code method} is empty, {@link NullPointerException} if any is null */
	public JsonRpcRequest {
		Objects.requireNonNull(id, "a request always has an id; a message without one is a JsonRpcNotification");
		Objects.requireNonNull(method, "method");
		Objects.requireNonNull(params, "params must be JsonRpcPayload.absent(), never null");
		if (method.isEmpty()) throw new IllegalArgumentException("method must not be empty");
	}

	/** A request with no {@code params} member at all — distinct from {@code "params":null}. */
	public JsonRpcRequest(JsonRpcId id, String method) {
		this(id, method, JsonRpcPayload.absent());
	}
}
