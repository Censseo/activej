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
 * What decoding one complete JSON-RPC document yields: either a single element ({@link JsonRpcDecoded}) or a
 * batch of them ({@link JsonRpcBatch}).
 * <p>
 * Sealed over exactly those two, so a caller distinguishes "one envelope" from "a batch" by type rather than
 * by a boolean — which is what decides whether the answer is rendered as one object or as an array (FR-043).
 * <p>
 * The two degenerate cases arrive as a {@link JsonRpcMalformed}, hence as a {@link JsonRpcDecoded} and hence
 * as a single document: a document that could not be parsed at all, and an <b>empty</b> top-level array,
 * which §6 makes a single {@code -32600} rather than an empty batch (FR-039).
 */
public sealed interface JsonRpcInput permits JsonRpcDecoded, JsonRpcBatch {
}
