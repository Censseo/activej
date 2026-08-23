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
 * An OpenRPC <b>Info Object</b> — the two members OpenRPC requires of every document, and nothing
 * else.
 *
 * <p>Emitted members, in this fixed order (rule M10): {@code title}, {@code version}.
 *
 * <h2>Application-supplied, never invented (FR-013, rule M9)</h2>
 * Both values come from the application through {@code withDiscovery(...)}. Nothing here derives a
 * title from a class name or a version from a POM: a document that invented its own identity would
 * be published, cached and depended upon under a name its author never chose.
 * <p>
 * The optional {@code description}, {@code termsOfService}, {@code contact} and {@code license}
 * members have no field, so they cannot be emitted. Supporting one of them is an additive model
 * change with a frozen-reference diff, which is the intended cost.
 *
 * @param title   the service's name, as the application states it
 * @param version the service's version, as the application states it. This is the <b>API</b>
 *                version, unrelated to {@link OpenRpcDocument#OPENRPC_VERSION}
 */
public record OpenRpcInfo(String title, String version) {

	/** @throws NullPointerException if either member is {@code null} — both are REQUIRED by OpenRPC */
	public OpenRpcInfo {
		Objects.requireNonNull(title, "title");
		Objects.requireNonNull(version, "version");
	}

	/** The codec, both directions. */
	public static final JsonCodec<OpenRpcInfo> CODEC = ObjectJsonCodec
		.<OpenRpcInfo>builder(array -> new OpenRpcInfo((String) array[0], (String) array[1]))
		.with("title", OpenRpcInfo::title, JsonCodecs.ofString())
		.with("version", OpenRpcInfo::version, JsonCodecs.ofString())
		.build();
}
