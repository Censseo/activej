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

package io.activej.jsonrpc.schema.fixtures;

import io.activej.jsonrpc.service.JsonRpcMethod;
import io.activej.jsonrpc.service.JsonRpcNotification;
import io.activej.jsonrpc.service.JsonRpcParam;
import io.activej.jsonrpc.service.JsonRpcService;
import io.activej.promise.Promise;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The reference service the schema export is measured against (FR-053) — one interface exercising every row
 * of the pinned Java&nbsp;→&nbsp;JSON&nbsp;Schema subset, both parameter styles, both method kinds and the
 * marked partial fallback. Every later document test replays against <i>this</i> declaration, and the frozen
 * reference document under {@code src/test/resources/io/activej/jsonrpc/openrpc/} is its byte-exact image.
 *
 * <h2>Nothing here is arbitrary</h2>
 * A method exists only because some row of the mapping table, or some structural rule of the OpenRPC
 * contract, needs a witness. Adding, removing, renaming or reordering anything below <b>changes the frozen
 * reference</b> and must land with a regenerated document — that is the point of the fixture, not a hazard of
 * it.
 *
 * <h2>Coverage map</h2>
 * <table>
 *     <tr><th>Row / rule</th><th>Witness</th></tr>
 *     <tr><td>ordinary method, request/response</td><td>{@link #createOrder}, and seven more</td></tr>
 *     <tr><td>{@code @JsonRpcNotification} — Method Object with <b>no</b> {@code result}</td>
 *         <td>{@link #orderSeen} ({@code void}), {@link #ping} ({@code Promise<Void>})</td></tr>
 *     <tr><td>fully <b>named</b> params</td><td>{@link #createOrder}, {@link #scalars}, …</td></tr>
 *     <tr><td><b>positional</b> params ⇒ {@code paramStructure: "by-position"} + {@code param<index>} labels</td>
 *         <td>{@link #sum}, {@link #ping} — <b>no</b> {@code @JsonRpcParam} anywhere on them</td></tr>
 *     <tr><td>no params at all ({@code isNamable()} vacuously true)</td><td>{@link #catalogue}</td></tr>
 *     <tr><td>{@code boolean}/{@code Boolean}</td><td>{@link #scalars}, {@link #boxedScalars}</td></tr>
 *     <tr><td>{@code byte}/{@code short}/{@code int}/{@code long} + boxed, {@code BigInteger}</td>
 *         <td>{@link #scalars}, {@link #boxedScalars}</td></tr>
 *     <tr><td>{@code float}/{@code double} + boxed, {@code BigDecimal}</td>
 *         <td>{@link #scalars}, {@link #boxedScalars}, {@link #sum}</td></tr>
 *     <tr><td>{@code String}, {@code char}/{@code Character}</td><td>{@link #scalars}, {@link #boxedScalars}</td></tr>
 *     <tr><td>{@code enum E}</td><td>{@link #scalars} (result), {@link #containers} (inside a {@code Set})</td></tr>
 *     <tr><td>{@code T[]}, {@code List<T>}, {@code Set<T>}, {@code Map<String,V>}</td>
 *         <td>{@link #containers}, {@link #catalogue}, {@link Inventory}</td></tr>
 *     <tr><td>{@code record R}</td><td>{@link Order} (flat), {@link Inventory} (containers), {@link OrderLine} (nested)</td></tr>
 *     <tr><td>{@code Promise<Void>} result ⇒ {@code {"type":"null"}}</td><td>{@link #archive}</td></tr>
 *     <tr><td>outside the subset ⇒ the boolean {@code true}</td><td>{@link #audit}</td></tr>
 * </table>
 *
 * <h2>Two rules this fixture obeys by construction</h2>
 * <ul>
 *     <li><b>Namability is all-or-nothing per method</b> — {@code JsonRpcMethodDescriptor.isNamable()} is
 *     {@code params().stream().allMatch(p -> p.name() != null)}. A positional method therefore carries no
 *     {@code @JsonRpcParam} on <i>any</i> parameter; a single annotated parameter would not make it
 *     namable, and a single unannotated one is enough to make a method positional-only.</li>
 *     <li><b>Every type resolves to a codec</b> (contract rule 8) — including the fallback types, which are
 *     outside the <i>schema</i> subset but well inside what {@code JsonCodecFactory} resolves. A type the
 *     factory cannot resolve never reaches the schema generator at all: it is a startup violation.</li>
 * </ul>
 *
 * <p>Every {@code value()} is spelled out. An empty one falls back to the Java identifier, which would make a
 * later rename a silent wire-format change — and here, a silent change to a frozen document.
 */
@JsonRpcService("reference")
public interface ReferenceApi {

	// -------------------------------------------------------------------------------------------------
	// Named parameters, record result.
	// -------------------------------------------------------------------------------------------------

	/**
	 * The ordinary case: every parameter named, the result a flat {@code record}.
	 *
	 * @return {@code reference.createOrder} — {@code {"type":"object","properties":{…},"required":[…]}}
	 */
	@JsonRpcMethod("createOrder")
	Promise<Order> createOrder(
		@JsonRpcParam("customer") String customer,
		@JsonRpcParam("quantity") int quantity,
		@JsonRpcParam("express") boolean express);

	// -------------------------------------------------------------------------------------------------
	// Positional parameters — no @JsonRpcParam anywhere, so isNamable() is false.
	// -------------------------------------------------------------------------------------------------

	/**
	 * Positional-only: the Content Descriptors get the synthesised labels {@code param0} / {@code param1} and
	 * the Method Object carries {@code paramStructure: "by-position"}. Those labels are <b>not</b> wire keys —
	 * this method cannot be called with named {@code params} at all.
	 */
	@JsonRpcMethod("sum")
	Promise<Double> sum(double left, double right);

	// -------------------------------------------------------------------------------------------------
	// Every scalar row of the subset, primitive and boxed.
	// -------------------------------------------------------------------------------------------------

	/**
	 * The primitive rows in one signature, with an {@code enum} result. Both integral widths and both
	 * floating widths appear, because all four collapse into two schema fragments and a mapping that
	 * confused them would still round-trip.
	 */
	@JsonRpcMethod("scalars")
	Promise<Status> scalars(
		@JsonRpcParam("flag") boolean flag,
		@JsonRpcParam("tiny") byte tiny,
		@JsonRpcParam("small") short small,
		@JsonRpcParam("count") int count,
		@JsonRpcParam("id") long id,
		@JsonRpcParam("ratio") float ratio,
		@JsonRpcParam("amount") double amount,
		@JsonRpcParam("label") String label,
		@JsonRpcParam("initial") char initial);

	/**
	 * The boxed rows, plus the two arbitrary-precision types — which are the pair most easily got wrong:
	 * {@code BigDecimal} is {@code {"type":"number"}} and {@code BigInteger} is {@code {"type":"integer"}}.
	 */
	@JsonRpcMethod("boxedScalars")
	Promise<BigDecimal> boxedScalars(
		@JsonRpcParam("flag") Boolean flag,
		@JsonRpcParam("tiny") Byte tiny,
		@JsonRpcParam("small") Short small,
		@JsonRpcParam("count") Integer count,
		@JsonRpcParam("id") Long id,
		@JsonRpcParam("ratio") Float ratio,
		@JsonRpcParam("amount") Double amount,
		@JsonRpcParam("initial") Character initial,
		@JsonRpcParam("precise") BigDecimal precise,
		@JsonRpcParam("huge") BigInteger huge);

	// -------------------------------------------------------------------------------------------------
	// Containers, at the top level and nested inside a record.
	// -------------------------------------------------------------------------------------------------

	/**
	 * Every container row as a parameter, and the same four again as the components of the
	 * {@link Inventory} result — so a mapping that handles containers only at the top level fails here.
	 */
	@JsonRpcMethod("containers")
	Promise<Inventory> containers(
		@JsonRpcParam("tags") String[] tags,
		@JsonRpcParam("lines") List<OrderLine> lines,
		@JsonRpcParam("statuses") Set<Status> statuses,
		@JsonRpcParam("quantities") Map<String, Integer> quantities);

	/** No parameters at all — an empty {@code params} array, and no {@code paramStructure} member. */
	@JsonRpcMethod("catalogue")
	Promise<List<Status>> catalogue();

	// -------------------------------------------------------------------------------------------------
	// The null-schema result.
	// -------------------------------------------------------------------------------------------------

	/**
	 * A callable method whose result is {@code Promise<Void>}: the wire value is always the JSON literal
	 * {@code null}, the descriptor's {@code resultCodec()} is {@code null}, and the Content Descriptor's
	 * schema is {@code {"type":"null"}}. This is <b>not</b> a notification — the {@code result} member is
	 * present and describes {@code null}.
	 */
	@JsonRpcMethod("archive")
	Promise<Void> archive(@JsonRpcParam("id") long id);

	// -------------------------------------------------------------------------------------------------
	// The marked partial fallback: resolvable codecs, outside the schema subset.
	// -------------------------------------------------------------------------------------------------

	/**
	 * Three shapes {@code JsonCodecFactory} resolves and the pinned subset does not describe, so all three
	 * emit the JSON Schema boolean {@code true} — "constrains nothing", trivially distinguishable from a
	 * described type:
	 * <ul>
	 *     <li>{@code Instant} — a leaf value type the factory registers ({@code JsonCodecs.ofInstant}), with
	 *     no row in the mapping table;</li>
	 *     <li>{@code Map<Status,BigDecimal>} — an {@code enum}-keyed map, which the factory resolves through
	 *     {@code JsonKeyCodec.ofEnumKey} while the subset admits {@code Map<String,V>} only;</li>
	 *     <li>{@code UUID} as the result — the factory registers {@code JsonCodecs.ofUuid}; JSON Schema could
	 *     describe it as a formatted string, and this feature deliberately does not.</li>
	 * </ul>
	 * An approximate schema presented as complete would be the spec violation; the boolean is the honest
	 * answer.
	 */
	@JsonRpcMethod("audit")
	Promise<UUID> audit(
		@JsonRpcParam("at") Instant at,
		@JsonRpcParam("byStatus") Map<Status, BigDecimal> byStatus);

	// -------------------------------------------------------------------------------------------------
	// Notifications — a Method Object with no result member at all.
	// -------------------------------------------------------------------------------------------------

	/** A {@code void} notification with a named parameter. */
	@JsonRpcNotification("orderSeen")
	void orderSeen(@JsonRpcParam("id") long id);

	/**
	 * A {@code Promise<Void>} notification with a <b>positional</b> parameter — the two axes crossed, so
	 * neither "notifications are always void" nor "positional methods are always callable" can be assumed
	 * anywhere downstream.
	 */
	@JsonRpcNotification("ping")
	Promise<Void> ping(String note);
}
