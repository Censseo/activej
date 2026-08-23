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

import java.util.List;
import java.util.Objects;

/**
 * The root of an <b>OpenRPC document</b> — the value {@code rpc.discover} answers and the offline
 * export writes, byte for byte the same artifact (research Decision 5).
 *
 * <p>Emitted members, in this fixed order (rule M10): {@code openrpc}, {@code info},
 * {@code methods}. The whole model is four small immutable values —
 * {@link OpenRpcDocument}, {@link OpenRpcInfo}, {@link OpenRpcMethod},
 * {@link OpenRpcContentDescriptor} — plus {@link JsonSchemaFragment} for the {@code schema} member.
 *
 * <h2>Member order is a contract, not an accident (rule M10, FR-051)</h2>
 * Every codec here is a hand-assembled {@code ObjectJsonCodec} whose field order <b>is</b> the
 * emitted member order, and {@code methods} keeps the order it was given (the generator sorts by
 * wire name, rule M2). That is what licenses the phase-US3 comparison against frozen reference
 * documents to be a <b>byte</b> comparison: a reordering is then a visible diff rather than an
 * invisible one.
 *
 * <h2>What is not here (rule M9, FR-013)</h2>
 * There is no {@code servers}, {@code externalDocs} or {@code components} field — not an unused
 * field, no field at all. The rule is "never invent metadata", and the cheapest way to keep a rule
 * about output is to make the input impossible: a member with no field cannot be emitted empty, null
 * or guessed.
 *
 * <h2>Why hand-assembled codecs and not derived record codecs (research Decision 3)</h2>
 * Three requirements no derived codec meets: OpenRPC needs <b>absence</b> ({@code result} undefined
 * ⇒ notification, rule M3) where a derived codec's null-encode behaviour is unverified; a schema
 * member may be the JSON boolean {@code true} (rule M8), which no record shape expresses; and the
 * member order must be pinned. FR-014 therefore holds literally — the document is produced by this
 * module's own codec stack, with no new dependency and no bytecode.
 *
 * <h2>Synchronous by construction</h2>
 * Nothing in this package touches a reactor, a {@code Promise}, a {@code ByteBuf} or a transport.
 * The document is computed once, at {@code build()} time, and is immutable thereafter (DI-4);
 * {@code ModuleBoundaryTest} asserts the import rule by scanning these files as text.
 *
 * @param openrpc the OpenRPC specification version the document conforms to — {@link #OPENRPC_VERSION}
 *                for anything this feature produces. It is a component rather than a constant in the
 *                codec so that a document read back from a frozen reference reports the version it
 *                actually carries
 * @param info    the application-supplied identity; never derived, never invented (FR-013)
 * @param methods every dispatcher wire name <b>except</b> {@code rpc.discover}, in the generator's
 *                deterministic order (rule M2). May be <b>empty</b> — a service with no methods is a
 *                valid document, not an error
 */
public record OpenRpcDocument(String openrpc, OpenRpcInfo info, List<OpenRpcMethod> methods) {

	/**
	 * The pinned OpenRPC specification version, emitted as the {@code openrpc} member of every
	 * document this feature produces (rule M1).
	 * <p>
	 * Changing it is a deliberate contract change: it re-freezes every reference document (FR-051)
	 * and needs the external validator re-run against the matching meta-schema (FR-052). That is why
	 * it is one constant in one place.
	 */
	public static final String OPENRPC_VERSION = "1.4.0";

	/** @throws NullPointerException if any member is {@code null} — all three are REQUIRED by OpenRPC */
	public OpenRpcDocument {
		Objects.requireNonNull(openrpc, "openrpc");
		Objects.requireNonNull(info, "info");
		Objects.requireNonNull(methods, "methods");
		methods = List.copyOf(methods); // the iteration order IS the emitted order
	}

	/** A document at the pinned {@link #OPENRPC_VERSION} — the only form this feature produces. */
	public static OpenRpcDocument of(OpenRpcInfo info, List<OpenRpcMethod> methods) {
		return new OpenRpcDocument(OPENRPC_VERSION, info, methods);
	}

	/**
	 * The codec, and the entry point every consumer of this package should use. Both directions are
	 * supported: the emitted form reads back equal, and re-encodes byte-identically.
	 * <p>
	 * Decoding is not needed by anything that ships — nothing here consumes a foreign OpenRPC
	 * document — but {@code ObjectJsonCodec} gives it for the same declaration, and having it means
	 * the round trip can be <i>asserted</i> rather than assumed.
	 */
	@SuppressWarnings("unchecked")
	public static final JsonCodec<OpenRpcDocument> CODEC = ObjectJsonCodec
		.<OpenRpcDocument>builder(array -> new OpenRpcDocument(
			(String) array[0],
			(OpenRpcInfo) array[1],
			(List<OpenRpcMethod>) array[2]))
		.with("openrpc", OpenRpcDocument::openrpc, JsonCodecs.ofString())
		.with("info", OpenRpcDocument::info, OpenRpcInfo.CODEC)
		.with("methods", OpenRpcDocument::methods, JsonCodecs.ofList(OpenRpcMethod.CODEC))
		.build();
}
