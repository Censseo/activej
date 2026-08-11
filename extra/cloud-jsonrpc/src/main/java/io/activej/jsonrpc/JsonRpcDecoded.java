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
 * What decoding <b>one element</b> yields: either a valid {@link JsonRpcMessage} or a {@link JsonRpcMalformed}
 * describing why it is not one.
 *
 * <h2>Classification instead of exception (FR-080)</h2>
 * A malformed envelope is expected traffic on this path, not an exceptional condition, and a batch needs one
 * outcome <i>per element</i> — which no exception can carry. So the decoder <b>returns</b> a failure rather
 * than throwing one. {@link JsonRpcException} keeps the narrower role of crossing an API boundary when a call
 * must be completed exceptionally.
 * <p>
 * {@link JsonRpcMessage} is itself a permitted subtype, so a valid element is its own outcome and the success
 * path allocates no wrapper.
 */
public sealed interface JsonRpcDecoded extends JsonRpcInput permits JsonRpcMessage, JsonRpcMalformed {
}
