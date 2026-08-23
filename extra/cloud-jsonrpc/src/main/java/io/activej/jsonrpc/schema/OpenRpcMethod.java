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

package io.activej.jsonrpc.schema;

import io.activej.json.JsonCodec;
import io.activej.json.JsonCodecs;
import io.activej.json.ObjectJsonCodec;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * An OpenRPC <b>Method Object</b> — one JSON-RPC wire name, its parameters and, unless it is a
 * notification, its result.
 *
 * <p>Emitted members, in this fixed order (rule M10): {@code name}, {@code params},
 * {@code result} (<b>omitted</b> when {@code null}), {@code paramStructure} (<b>omitted</b> when
 * {@code null}).
 *
 * <h2>Two members that are omitted, not nulled</h2>
 * <ol>
 *     <li><b>{@code result} absent means notification.</b> That is OpenRPC's own rule, not a
 *     convention of this feature: an undefined {@code result} <i>is</i> the notification marker
 *     (rule M3). {@code "result": null} says something else entirely — a callable method answering
 *     the JSON literal {@code null}, which is what a {@code void} method emits (rule M7,
 *     {@link JsonSchemaMapping#VOID_RESULT}). The two must never be confusable, so this model
 *     writes the member or does not write it, and never writes {@code null}.</li>
 *     <li><b>{@code paramStructure} absent means "either".</b> A namable method — every parameter
 *     carrying a {@code @JsonRpcParam} name — accepts both calling conventions, which is exactly
 *     OpenRPC's default; writing {@code "either"} out would state a choice the generator never
 *     made. Only {@link #BY_POSITION} is ever emitted, and only for a method carrying a positional
 *     label (rule M5).</li>
 * </ol>
 * Both are expressed the same way: the member is registered on the codec with a {@code null}
 * default, which is {@code ObjectJsonCodec.BuilderArray}'s encode-side omission — a value equal to
 * its default is not written at all. The envelope's {@code JsonRpcEncoder} states the same rule for
 * a notification's {@code id} in the hand-rolled form; this is that rule inside a codec.
 *
 * @param name           the JSON-RPC wire name, e.g. {@code user.get} — unique within a document by
 *                       construction, since the dispatcher's handler table is a map
 * @param params         the Content Descriptors in <b>declaration order</b> (rule M4); never
 *                       {@code null}, and emitted even when empty, because OpenRPC requires the
 *                       member
 * @param result         the result Content Descriptor, or {@code null} for a notification (rule M3)
 * @param paramStructure {@link #BY_POSITION} when the method carries any positional label, or
 *                       {@code null} to omit the member (rule M5)
 */
public record OpenRpcMethod(
	String name,
	List<OpenRpcContentDescriptor> params,
	@Nullable OpenRpcContentDescriptor result,
	@Nullable String paramStructure
) {

	/** The one {@code paramStructure} value this feature emits: the method is addressable by position only. */
	public static final String BY_POSITION = "by-position";

	/** Legal in OpenRPC, never emitted here — a namable method omits the member instead (rule M5). */
	public static final String BY_NAME = "by-name";

	/** OpenRPC's default, never emitted here — absence already means it (rule M5). */
	public static final String EITHER = "either";

	private static final Set<String> PARAM_STRUCTURES = Set.of(BY_POSITION, BY_NAME, EITHER);

	/**
	 * @throws NullPointerException     if {@code name} or {@code params} is {@code null}
	 * @throws IllegalArgumentException if {@code paramStructure} is neither {@code null} nor one of
	 *                                  OpenRPC's three values — a typo here would be a member no
	 *                                  consumer understands, discovered only by an external
	 *                                  validator
	 */
	public OpenRpcMethod {
		Objects.requireNonNull(name, "name");
		Objects.requireNonNull(params, "params");
		if (paramStructure != null && !PARAM_STRUCTURES.contains(paramStructure)) {
			throw new IllegalArgumentException(
				"not an OpenRPC paramStructure: \"" + paramStructure + "\", expected one of " +
				PARAM_STRUCTURES + " or null to omit the member");
		}
		params = List.copyOf(params); // the iteration order IS the emitted order
	}

	/**
	 * The codec, both directions. {@code result} and {@code paramStructure} carry a {@code null}
	 * default, so each is omitted on encode when absent and restored as absent on decode.
	 */
	@SuppressWarnings("unchecked")
	public static final JsonCodec<OpenRpcMethod> CODEC = ObjectJsonCodec
		.<OpenRpcMethod>builder(array -> new OpenRpcMethod(
			(String) array[0],
			(List<OpenRpcContentDescriptor>) array[1],
			(OpenRpcContentDescriptor) array[2],
			(String) array[3]))
		.with("name", OpenRpcMethod::name, JsonCodecs.ofString())
		.with("params", OpenRpcMethod::params, JsonCodecs.ofList(OpenRpcContentDescriptor.CODEC))
		.with("result", OpenRpcMethod::result, OpenRpcContentDescriptor.CODEC, null)
		.with("paramStructure", OpenRpcMethod::paramStructure, JsonCodecs.ofString(), null)
		.build();
}
