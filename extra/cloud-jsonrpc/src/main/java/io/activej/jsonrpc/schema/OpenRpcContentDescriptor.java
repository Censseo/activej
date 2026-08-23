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

import java.util.Objects;

/**
 * An OpenRPC <b>Content Descriptor Object</b> — one described value: a parameter of a method, or its
 * result.
 *
 * <p>Emitted members, in this fixed order (rule M10, matching the contract's own §1 example literally):
 * {@code name}, {@code required}, {@code schema}.
 *
 * <h2>Why there is no {@code required} component</h2>
 * OpenRPC's {@code required} is an optional boolean defaulting to {@code false}. In this stack it is
 * <b>always {@code true}</b> and <b>always written</b> (rule M6), for a result as much as for a
 * parameter: a JSON-RPC method's parameters are all positional-or-named and none is optional — the
 * contract of feature 012 has no notion of an omittable argument — and a callable method always
 * produces a {@code result} member, the {@code void} case included (it renders as the JSON literal
 * {@code null}, which is a <i>present</i> value, rule M7).
 * <p>
 * So the field is not on the record. A type that cannot express {@code "required": false} cannot
 * emit it by accident, and the day optionality does exist in the contract, adding it here is a
 * deliberate model change with a frozen-reference diff (FR-051) rather than a silent one.
 *
 * @param name   the label a consumer sees. For a parameter this is the {@code @JsonRpcParam} name,
 *               or the positional label {@code param<index>} when the parameter is unannotated
 *               (rule M4) — a positional label is <b>not</b> a wire key, and rule M5's
 *               {@code paramStructure} on the enclosing method says so
 * @param schema the described shape, or {@link JsonSchemaFragment.Any} for a type outside the pinned
 *               subset — which serialises as the bare JSON literal {@code true}, never {@code {}}
 *               (rule M8, FR-022)
 */
public record OpenRpcContentDescriptor(String name, JsonSchemaFragment schema) {

	/**
	 * @throws NullPointerException if {@code name} or {@code schema} is {@code null} — both members
	 *                              are REQUIRED by OpenRPC, and there is no honest substitute for
	 *                              either
	 */
	public OpenRpcContentDescriptor {
		Objects.requireNonNull(name, "name");
		Objects.requireNonNull(schema, "schema");
	}

	/**
	 * The codec, both directions. The {@code required} member is written from a constant and read
	 * back into nothing: it has exactly one legal value here, so decoding it into a component would
	 * create a second, unreachable state.
	 */
	public static final JsonCodec<OpenRpcContentDescriptor> CODEC = ObjectJsonCodec
		.<OpenRpcContentDescriptor>builder(array -> new OpenRpcContentDescriptor(
			(String) array[0],
			(JsonSchemaFragment) array[2]))
		.with("name", OpenRpcContentDescriptor::name, JsonCodecs.ofString())
		.with("required", descriptor -> Boolean.TRUE, JsonCodecs.ofBoolean())
		.with("schema", OpenRpcContentDescriptor::schema, JsonSchemaMapping.SCHEMA_CODEC)
		.build();
}
