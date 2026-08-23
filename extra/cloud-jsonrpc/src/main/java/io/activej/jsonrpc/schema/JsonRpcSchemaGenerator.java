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

import io.activej.json.JsonUtils;
import io.activej.jsonrpc.schema.JsonSchemaFragment.Any;
import io.activej.jsonrpc.service.JsonRpcMethodDescriptor;
import io.activej.jsonrpc.service.JsonRpcParamDescriptor;
import io.activej.jsonrpc.service.JsonRpcServiceContract;
import io.activej.types.Types;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Turns validated {@link JsonRpcServiceContract}s into an {@link OpenRpcDocument} — <b>the</b> generator, and
 * the only one (research Decision 5).
 *
 * <h2>One generator, three consumers</h2>
 * The runtime {@code rpc.discover} answer, the offline export and the frozen reference documents are the same
 * bytes because they are the same call (FR-040). A dispatcher built with discovery enabled computes them once
 * at {@code build()} time and serves the array; the export writes that array to a file; the non-regression
 * test compares it to a frozen one. Nothing here is per-request, and nothing regenerates.
 *
 * <h2>Static, synchronous, reactor-free</h2>
 * Generation is a pure walk over already-resolved descriptors and {@code java.lang.reflect}: no {@code
 * Reactor}, no {@code Promise}, no {@code ByteBuf}, no transport, no I/O, no cache and no mutable static
 * state. The class is therefore usable from any thread — including none in particular, which is how the
 * offline export uses it — and {@code ModuleBoundaryTest} asserts the import rule by scanning this file as
 * text.
 *
 * <h2>What it emits, rule by rule</h2>
 * The normative table is {@code specs/018-jsonrpc-schema-discovery/contracts/openrpc-mapping.md} §1.
 * <ul>
 *     <li><b>M2</b> — every wire name of every contract given, as one list <b>sorted by wire name</b> across
 *     all of them. Not per-contract blocks: a document listing two services is one sorted table, so adding a
 *     service to a deployment reorders nothing that was already there.</li>
 *     <li><b>M3</b> — a {@link JsonRpcMethodDescriptor#isNotification() notification} gets a Method Object
 *     with <b>no</b> {@code result} member. A callable method always gets one.</li>
 *     <li><b>M4</b> — one Content Descriptor per parameter, in declaration order, named by
 *     {@code @JsonRpcParam} or, for an unannotated parameter, by the positional label
 *     {@code param<index>}.</li>
 *     <li><b>M5</b> — {@code paramStructure: "by-position"} exactly when the method is not
 *     {@link JsonRpcMethodDescriptor#isNamable() namable}; a namable method omits the member, and OpenRPC's
 *     {@code either} default applies.</li>
 *     <li><b>M7</b> — a {@code void} / {@code Promise<Void>} result is {@link JsonSchemaMapping#VOID_RESULT}.
 *     </li>
 *     <li><b>M8</b> — a type outside the pinned subset is {@link Any}, which serialises as the JSON literal
 *     {@code true}.</li>
 * </ul>
 *
 * <h2>The result Content Descriptor is named {@value #RESULT_NAME}</h2>
 * OpenRPC requires the member and the mapping contract pins a <i>parameter</i>'s name (M4) but not a
 * result's, so this generator chooses — and chooses the one name it can know. A parameter has an authored
 * name; a result has none anywhere in the declaration, and deriving one from the method (the wire name's last
 * segment, the Java return type) would publish a label the author never wrote, under the same hazard as
 * {@code @JsonRpcMethod}'s empty {@code value()} falling back to the Java identifier. {@value #RESULT_NAME} is
 * exactly the JSON-RPC member the value occupies, invents nothing (FR-013, rule M9), and is stable across
 * every method of every service.
 *
 * <h2>Why nothing here filters {@code rpc.discover} (M2)</h2>
 * Rule M2 excludes {@code rpc.discover} from {@code methods[]}, and this generator has no such filter,
 * because at this layer the name is <b>unreachable</b> rather than merely absent:
 * <ol>
 *     <li>the input is a {@link JsonRpcServiceContract}, and contract rule 10 (FR-001) refuses any wire name
 *     in the reserved {@code rpc.} namespace at the point the name is computed — a user-declared
 *     {@code rpc.discover} fails at {@code build()} and never becomes a descriptor;</li>
 *     <li>the dispatcher's own built-in {@code rpc.discover} entry goes straight into the frozen handler
 *     table and belongs to no contract, so a discovery-enabled dispatcher passing its contracts here passes
 *     the user methods and nothing else.</li>
 * </ol>
 * An unconditional filter would therefore be a branch no input can take, and its correctness would rest on
 * the same two facts anyway. What guards the rule instead is an assertion: {@code
 * JsonRpcSchemaGeneratorTest.theReservedNamespaceIsUnreachableFromAContractAtAll} pins fact 1, so relaxing
 * rule 10 — which research Decision 2 contemplates for future protocol methods — fails a test naming this
 * class rather than silently emitting the name.
 */
public final class JsonRpcSchemaGenerator {
	private JsonRpcSchemaGenerator() {}

	/**
	 * The {@code name} of every result Content Descriptor this generator emits. See the class Javadoc for why
	 * it is a constant rather than derived from the method.
	 */
	public static final String RESULT_NAME = "result";

	/**
	 * The prefix of a positional label: an unannotated parameter at index {@code i} is described as
	 * {@code param}{@code i} (rule M4).
	 * <p>
	 * A positional label is <b>not</b> a wire key — the enclosing method carries
	 * {@link OpenRpcMethod#BY_POSITION}, which says so — and it is not a fallback for a missing
	 * {@code @JsonRpcParam} either: an unannotated parameter is positional-only by decision (FR-041), and the
	 * label exists solely because OpenRPC requires a Content Descriptor to have a name.
	 */
	public static final String POSITIONAL_LABEL_PREFIX = "param";

	/**
	 * Generates the document for a set of contracts.
	 *
	 * @param info      the application-supplied identity, emitted verbatim; never derived, never invented
	 *                  (FR-013)
	 * @param contracts the contracts to describe, in any order — the emitted method order is sorted by wire
	 *                  name (M2) and does not depend on this one. May be empty, which is a valid document
	 *                  with {@code "methods": []}
	 * @throws NullPointerException     if {@code info}, {@code contracts} or any element is {@code null}
	 * @throws IllegalArgumentException if two contracts claim the same wire name. A document cannot describe
	 *                                  one name twice, and {@code JsonRpcDispatcher.build()} refuses the same
	 *                                  configuration — this is the offline export reaching the same verdict
	 *                                  without a dispatcher
	 */
	public static OpenRpcDocument generate(OpenRpcInfo info, Collection<JsonRpcServiceContract> contracts) {
		Objects.requireNonNull(info, "info");
		Objects.requireNonNull(contracts, "contracts");

		// M2: sorted by wire name, across every contract at once. Natural String order, so the result depends
		// on nothing outside the names themselves - a Collator or a case-insensitive comparator would make
		// the frozen reference documents (FR-051) a function of the locale that generated them
		Map<String, OpenRpcMethod> byWireName = new TreeMap<>();
		Map<String, Class<?>> claimedBy = new HashMap<>();

		for (JsonRpcServiceContract contract : contracts) {
			Objects.requireNonNull(contract, "contracts must not contain null");
			// the parameter types on a descriptor are already bound; a result type is not exposed at all, so
			// it is read off the Method and bound here exactly as the contract bound it
			Map<TypeVariable<?>, Type> bindings = Types.getAllTypeBindings(contract.serviceType());

			for (JsonRpcMethodDescriptor descriptor : contract.methods().values()) {
				String wireName = descriptor.wireName();
				Class<?> claimant = claimedBy.putIfAbsent(wireName, contract.serviceType());
				if (claimant != null) {
					throw new IllegalArgumentException(
						"wire name '" + wireName + "' is claimed by two services: " + claimant.getName() +
						" and " + contract.serviceType().getName() + "; one OpenRPC document cannot describe " +
						"one method name twice");
				}
				byWireName.put(wireName, describe(descriptor, bindings));
			}
		}

		return OpenRpcDocument.of(info, List.copyOf(byWireName.values()));
	}

	/** @see #generate(OpenRpcInfo, Collection) */
	public static OpenRpcDocument generate(OpenRpcInfo info, JsonRpcServiceContract... contracts) {
		return generate(info, List.of(contracts));
	}

	/**
	 * Generates the document and encodes it with {@link OpenRpcDocument#CODEC} — the single call the
	 * dispatcher's pre-computed answer, the offline export and the frozen references all make, which is what
	 * makes the three byte-identical (FR-040).
	 *
	 * @return the document as UTF-8 JSON. A fresh array each call; the caller owns it
	 * @see #generate(OpenRpcInfo, Collection)
	 */
	public static byte[] generateBytes(OpenRpcInfo info, Collection<JsonRpcServiceContract> contracts) {
		return JsonUtils.toJsonBytes(OpenRpcDocument.CODEC, generate(info, contracts));
	}

	/** @see #generateBytes(OpenRpcInfo, Collection) */
	public static byte[] generateBytes(OpenRpcInfo info, JsonRpcServiceContract... contracts) {
		return generateBytes(info, List.of(contracts));
	}

	// -------------------------------------------------------------------------------------------------

	private static OpenRpcMethod describe(
		JsonRpcMethodDescriptor descriptor, Map<TypeVariable<?>, Type> bindings
	) {
		List<JsonRpcParamDescriptor> declared = descriptor.params();
		List<OpenRpcContentDescriptor> params = new ArrayList<>(declared.size());
		for (JsonRpcParamDescriptor param : declared) {
			// M4: the authored name, or the positional label for this index
			String name = param.name() != null ? param.name() : POSITIONAL_LABEL_PREFIX + param.index();
			params.add(new OpenRpcContentDescriptor(name, JsonSchemaMapping.map(param.type())));
		}

		// M3: absence IS the notification marker, so the member is left out rather than nulled - which the
		// document model expresses by carrying a null here and omitting it on encode
		OpenRpcContentDescriptor result = descriptor.isNotification() ?
			null :
			new OpenRpcContentDescriptor(RESULT_NAME, resultSchema(descriptor, bindings));

		// M5: a namable method omits the member and inherits OpenRPC's "either" default
		String paramStructure = descriptor.isNamable() ? null : OpenRpcMethod.BY_POSITION;

		return new OpenRpcMethod(descriptor.wireName(), params, result, paramStructure);
	}

	/**
	 * The described shape of a callable method's result.
	 * <p>
	 * The descriptor publishes the result <i>codec</i> but not the result <i>type</i>, so the type is read
	 * back off {@link JsonRpcMethodDescriptor#method()} and bound with the service type's own bindings. Which
	 * of the two shapes it is follows from the descriptor's documented invariants, so no {@code Promise} is
	 * named here — and none can be: {@code io.activej.promise} is not importable in this package.
	 */
	private static JsonSchemaFragment resultSchema(
		JsonRpcMethodDescriptor descriptor, Map<TypeVariable<?>, Type> bindings
	) {
		// FR-030: on a callable method a null result codec means the declared result was void or
		// Promise<Void>, and nothing else - an unresolvable type was a contract violation long before a
		// descriptor existed
		if (descriptor.resultCodec() == null) return JsonSchemaMapping.VOID_RESULT;

		Type declared;
		try {
			declared = Types.bind(descriptor.method().getGenericReturnType(), bindings);
		} catch (RuntimeException e) {
			// unreachable: contract rule 7 rejected an unbound result type. Answering Any rather than
			// throwing keeps this class as total as the mapping it delegates to - a type that cannot be
			// described is a documented outcome here, never a failure
			return Any.INSTANCE;
		}

		// a bare T rather than a Promise<T>: the declared type IS the result type (FR-046)
		if (descriptor.isSynchronousResult()) return JsonSchemaMapping.map(declared);

		// otherwise the contract resolved a codec from the sole type argument of a parameterized result, so
		// that argument is present, bound and not a wildcard - describing the container instead would
		// describe the async plumbing rather than the wire value
		if (declared instanceof ParameterizedType parameterized) {
			Type[] arguments = parameterized.getActualTypeArguments();
			if (arguments.length == 1) return JsonSchemaMapping.map(arguments[0]);
		}
		return Any.INSTANCE;
	}
}
