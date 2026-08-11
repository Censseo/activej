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

/**
 * The set of things that can appear as one element on the wire: a {@link JsonRpcRequest}, a
 * {@link JsonRpcNotification} or a {@link JsonRpcResponse} (FR-010).
 * <p>
 * Sealed, so an exhaustive {@code switch} is the natural way to handle an element and a future message kind
 * cannot be silently ignored by an existing consumer.
 * <p>
 * A valid message is also its own decode outcome — {@code JsonRpcMessage} is a permitted subtype of
 * {@link JsonRpcDecoded} — so the success path of decoding allocates no wrapper.
 * <p>
 * A <b>batch</b> is deliberately not a message: {@link JsonRpcBatch} implements {@link JsonRpcInput} instead,
 * because a batch cannot nest and making it a message would let {@code [[…]]} typecheck.
 */
public sealed interface JsonRpcMessage
	extends JsonRpcDecoded
	permits JsonRpcRequest, JsonRpcNotification, JsonRpcResponse {
}
