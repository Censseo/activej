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
 * One element that could not be decoded into a valid {@link JsonRpcMessage}, carrying the normative error
 * object and whatever identifier could be recovered before the failure (FR-037, FR-080).
 * <p>
 * This is a <b>returned value</b>, not a thrown exception: a batch produces one outcome per element, and an
 * exception cannot express that.
 *
 * @param id    the identifier recovered before the failure, or {@link JsonRpcId#NULL} when the failure
 *              happened before a syntactically valid one had been read; never {@code null}
 * @param error the normative error object — its message is one of the fixed strings of {@link JsonRpcErrors},
 *              and its {@code data} is never populated from a caught exception, because a decode-failure
 *              message from the JSON layer embeds the offending input (FR-089)
 */
public record JsonRpcMalformed(JsonRpcId id, JsonRpcError error) implements JsonRpcDecoded {
	/** @throws NullPointerException if either component is {@code null}; use {@link JsonRpcId#NULL} for no id */
	public JsonRpcMalformed {
		Objects.requireNonNull(id, "id must be a JsonRpcId; use JsonRpcId.NULL when none was recovered");
		Objects.requireNonNull(error, "error");
	}

	/**
	 * Renders this failure as a response document — how a caller turns an element failure into something it
	 * can send back.
	 */
	public JsonRpcResponse toResponse() {
		return JsonRpcResponse.ofError(id, error);
	}
}
