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
 * A JSON-RPC 2.0 Notification (§4.1): a call with no identifier, to which the server must not reply.
 * <p>
 * Rendered as <code>{"jsonrpc":"2.0","method":&lt;string&gt;[,"params":&lt;value&gt;]}</code>, in that member
 * order. There is <b>no {@code id} member at all</b> — not even {@code "id":null} (FR-042).
 *
 * <h2>Why there is no id component</h2>
 * "A notification produces no response" is enforced by the type system rather than by a runtime check
 * (FR-011): a notification carries no identifier, and no method anywhere in this module turns one into a
 * {@link JsonRpcResponse}. A consumer that wants to answer has nothing to correlate with and no factory to
 * reach for — which is the point. The alternative, a request with a nullable id plus a "do not answer this
 * one" flag, puts the rule in a comment.
 *
 * @param method the method name; never {@code null}, never empty
 * @param params the arguments, still undecoded — {@link JsonRpcPayload#absent()} when the member was omitted;
 *               never {@code null}
 */
public record JsonRpcNotification(String method, JsonRpcPayload params) implements JsonRpcMessage {
	/** @throws IllegalArgumentException if {@code method} is empty, {@link NullPointerException} if it is null */
	public JsonRpcNotification {
		Objects.requireNonNull(method, "method");
		Objects.requireNonNull(params, "params must be JsonRpcPayload.absent(), never null");
		if (method.isEmpty()) throw new IllegalArgumentException("method must not be empty");
	}

	/** A notification with no {@code params} member at all — distinct from {@code "params":null}. */
	public JsonRpcNotification(String method) {
		this(method, JsonRpcPayload.absent());
	}
}
