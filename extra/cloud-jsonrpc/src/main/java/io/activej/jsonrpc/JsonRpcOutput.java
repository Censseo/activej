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
 * What is to be sent. Encoding needs a state the input side has no use for: <b>nothing to send</b>.
 * <table border="1">
 *     <caption>outgoing documents</caption>
 *     <tr><th>Variant</th><th>Renders as</th><th>Arises from</th></tr>
 *     <tr><td>{@link None}</td><td><b>zero bytes</b></td>
 *         <td>a lone notification; an all-notification batch (FR-044)</td></tr>
 *     <tr><td>{@link Single}</td><td>one JSON document</td>
 *         <td>one request, notification or response</td></tr>
 *     <tr><td>{@link Batch}</td><td>a JSON array, <b>even for one element</b> (FR-043)</td>
 *         <td>a batch that produced at least one response</td></tr>
 * </table>
 *
 * <h2>Why {@code None} is a type and not an empty list</h2>
 * "No response document at all" and "an empty array" are different documents on the wire: {@code []} is
 * itself a {@code -32600} when a peer receives it (§6), so it must never be emitted as an answer. Making the
 * two distinct types means a caller cannot conflate them by accident — and {@link Batch} refuses an empty
 * list at construction so the wrong one is unconstructible rather than merely discouraged.
 *
 * <h2>No ordering relationship (FR-046)</h2>
 * A batch's responses carry no ordering relationship to the request array they answer. Correlation is by
 * {@code id} alone; a consumer that relies on position will pass against one implementation and fail against
 * the next.
 */
public sealed interface JsonRpcOutput permits JsonRpcOutput.None, JsonRpcOutput.Single, JsonRpcOutput.Batch {
	/** Nothing to send — rendered as zero bytes, which is not {@code []} and not <code>{}</code>. */
	JsonRpcOutput NONE = new None();

	/** Nothing to send. The same value as {@link #NONE}, for call sites that read better as a call. */
	static JsonRpcOutput none() {
		return NONE;
	}

	/**
	 * One document.
	 *
	 * @throws NullPointerException if {@code message} is {@code null}
	 */
	static JsonRpcOutput single(JsonRpcMessage message) {
		return new Single(message);
	}

	/**
	 * A JSON array of documents, <b>even when it holds exactly one</b> (FR-043).
	 *
	 * @throws IllegalArgumentException if {@code messages} is empty — use {@link #none()} to send nothing
	 */
	static JsonRpcOutput batch(List<JsonRpcMessage> messages) {
		return new Batch(messages);
	}

	/** Nothing to send. Use the {@link #NONE} constant. */
	record None() implements JsonRpcOutput {
		@Override
		public String toString() {
			return "JsonRpcOutput.None";
		}
	}

	/**
	 * One document.
	 *
	 * @param message the message to render; never {@code null}
	 */
	record Single(JsonRpcMessage message) implements JsonRpcOutput {
		/** @throws NullPointerException if {@code message} is {@code null} */
		public Single {
			Objects.requireNonNull(message, "message");
		}
	}

	/**
	 * A JSON array of documents — <b>including when it holds exactly one</b>, because batch-ness is part of
	 * the answer's shape (FR-043).
	 *
	 * @param messages the messages to render, held immutably; never empty
	 */
	record Batch(List<JsonRpcMessage> messages) implements JsonRpcOutput {
		/** @throws IllegalArgumentException if {@code messages} is empty — use {@link #NONE} to send nothing */
		public Batch {
			Objects.requireNonNull(messages, "messages");
			if (messages.isEmpty()) {
				throw new IllegalArgumentException(
					"an empty batch would render as [], which is itself a -32600 on the wire; " +
					"use JsonRpcOutput.NONE when there is nothing to send");
			}
			messages = List.copyOf(messages);
		}
	}
}
