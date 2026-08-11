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

import io.activej.common.ApplicationSettings;
import io.activej.common.MemSize;

/**
 * The three bounds that make this decoder safe to point at a network (FR-050…FR-054).
 *
 * <table border="1">
 *     <caption>bounds</caption>
 *     <tr><th>Field</th><th>Default</th><th>Setting key</th><th>Violated →</th></tr>
 *     <tr><td>{@link #MAX_BODY_SIZE}</td><td>{@code 1mb}</td><td>{@code JsonRpcLimits.maxBodySize}</td>
 *         <td>{@code -32001 Request too large}</td></tr>
 *     <tr><td>{@link #MAX_BATCH_SIZE}</td><td>{@code 100}</td><td>{@code JsonRpcLimits.maxBatchSize}</td>
 *         <td>{@code -32002 Batch too large}</td></tr>
 *     <tr><td>{@link #MAX_JSON_DEPTH}</td><td>{@code 64}</td><td>{@code JsonRpcLimits.maxJsonDepth}</td>
 *         <td>{@code -32003 Nesting too deep}</td></tr>
 * </table>
 *
 * Each key resolves against the fully qualified {@code io.activej.jsonrpc.JsonRpcLimits.<setting>} first and
 * the simple {@code JsonRpcLimits.<setting>} second, per the {@link ApplicationSettings} convention. All
 * three are resolved <b>once</b> at class initialisation and are never mutated afterwards (DI-5).
 *
 * <h2>Enabled by default (FR-051)</h2>
 * A consumer opts <b>out</b> by raising a bound, never <b>in</b> by enabling one. There is no "unlimited"
 * value and no disable switch: a limit that has to be turned on is a limit nobody has turned on.
 *
 * <h2>Why static fields rather than a settings object (FR-083, FR-053)</h2>
 * A settings object belongs to a <i>component</i>, and this feature deliberately has none. More concretely,
 * a transport must be able to consult {@link #MAX_BODY_SIZE} <b>during</b> accumulation — before an envelope
 * array exists at all — because applying a size bound to an array already allocated is too late to be worth
 * anything. Enforcing it there is the transport's obligation, not this decoder's; this class is what makes
 * the value reachable for it.
 * <p>
 * A dedicated holder also gives the settings a namespace that survives the decoder being refactored:
 * {@code -DJsonRpcLimits.maxBodySize=4mb} keeps working. When a later feature introduces a server component,
 * a per-instance override can be layered on top with these as defaults — which is explicitly not this
 * feature's to build.
 *
 * <h2>Where the defaults come from</h2>
 * {@code 1mb} — a JSON-RPC envelope is a control message, not a bulk transfer; {@code core-http}'s
 * {@code 100mb} body default is sized for arbitrary bodies and is the wrong reference point.
 * {@code 100} elements — the specification's own §7 examples top out at six, and a batch is a latency
 * optimisation rather than a bulk channel. {@code 64} levels — twice the deepest fixture anyone has
 * measured, and an order of magnitude above a realistic DTO graph.
 */
public final class JsonRpcLimits {
	private JsonRpcLimits() {}

	/**
	 * The largest envelope this decoder will look at, {@code 1mb} by default.
	 * <p>
	 * Readable without an instance so a transport can bound its own accumulation loop against it before the
	 * bytes are ever handed over (FR-053).
	 */
	public static final MemSize MAX_BODY_SIZE =
		ApplicationSettings.getMemSize(JsonRpcLimits.class, "maxBodySize", MemSize.megabytes(1));

	/** The most elements a batch may carry, {@code 100} by default. Applied while elements are decoded. */
	public static final int MAX_BATCH_SIZE =
		ApplicationSettings.getInt(JsonRpcLimits.class, "maxBatchSize", 100);

	/** The deepest a document may nest, {@code 64} by default. Applied by a pre-parse scan. */
	public static final int MAX_JSON_DEPTH =
		ApplicationSettings.getInt(JsonRpcLimits.class, "maxJsonDepth", 64);
}
