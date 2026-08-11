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

import java.util.List;
import java.util.Objects;

/**
 * A decoded JSON-RPC 2.0 batch (§6): a top-level array of elements, each decoded <b>independently</b>, so one
 * invalid element yields its own {@code -32600} without invalidating the others (FR-038).
 * <p>
 * A batch may mix requests, notifications and responses; no homogeneity is required (FR-087).
 *
 * <h2>Never empty</h2>
 * An empty top-level array does <b>not</b> produce a batch — it produces a single
 * {@code JsonRpcMalformed(-32600)} (FR-039), so the caller renders one object rather than an array. This
 * constructor therefore refuses an empty list rather than trusting the decoder to never build one.
 *
 * <h2>Never a message</h2>
 * {@code JsonRpcBatch} implements {@link JsonRpcInput}, not {@link JsonRpcMessage}. A batch cannot nest, and
 * making it a message would let {@code [[…]]} typecheck.
 *
 * <h2>The size bound is the decoder's</h2>
 * {@code maxBatchSize} is applied <b>while</b> elements are decoded (FR-054) — the batch is refused on the
 * element that would exceed it, before that element and every element after it is decoded or retained. Counting
 * first and checking afterwards has already paid the cost the bound exists to prevent, so this constructor
 * deliberately does not re-check a bound the list can no longer violate.
 *
 * @param elements the decoded elements, in document order; never empty, and held immutably
 */
public record JsonRpcBatch(List<JsonRpcDecoded> elements) implements JsonRpcInput {
	/** @throws IllegalArgumentException if {@code elements} is empty — an empty array is not a batch (FR-039) */
	public JsonRpcBatch {
		Objects.requireNonNull(elements, "elements");
		if (elements.isEmpty()) {
			throw new IllegalArgumentException(
				"a batch is never empty; an empty top-level array is a single JsonRpcMalformed(-32600)");
		}
		elements = List.copyOf(elements);
	}

	/** The number of decoded elements. */
	public int size() {
		return elements.size();
	}
}
