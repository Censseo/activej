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

package io.activej.jsonrpc.service;

import io.activej.jmx.api.JmxRefreshable;
import io.activej.jmx.api.attribute.JmxAttribute;
import io.activej.jmx.stats.EventStats;
import io.activej.jmx.stats.ValueStats;
import io.activej.jsonrpc.JsonRpcError;
import io.activej.jsonrpc.JsonRpcErrors;

import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One registered method's numbers — the value of the {@code methodStats} map (FR-032…FR-034).
 * <p>
 * A JMX composite mirroring {@code RpcRequestStats}' role in {@code RpcClient}: {@code EventStats} for
 * counts, a {@code ValueStats} for latency, and an {@code errorsByCode} breakdown. The smoothing window is
 * <b>1 minute</b>, matching {@code RpcServer} and {@code HttpServer} (FR-037a).
 * <p>
 * <b>{@code errorsByCode} cardinality (FR-033 + FR-034):</b> the key is a JSON-RPC error code, and a code
 * is chosen by <i>this server</i> — never echoed from the request. The reachable set is the nine named
 * {@link JsonRpcErrors} codes plus whatever an application's own {@link JsonRpcException} carries. Since an
 * application-chosen code is still server-side, this is not a remote-input surface — but it is not
 * statically enumerable either, so the map is <b>pre-populated with the named codes</b> and an
 * out-of-range application code increments the single {@link #getOtherErrors()} counter rather than
 * creating an entry. Same rule as the method table, applied one level down. There is no
 * {@code computeIfAbsent} anywhere in this class.
 * <p>
 * Every method of this class accepts only server-derived state — never a wire-received name, id or
 * payload — so nothing in here can leak a request into a JMX attribute (Spec §Security Considerations).
 */
public final class JsonRpcMethodStats implements JmxRefreshable {
	private static final Duration SMOOTHING_WINDOW = Duration.ofMinutes(1);

	private final EventStats successfulRequests = EventStats.create(SMOOTHING_WINDOW);
	private final EventStats failedRequests = EventStats.create(SMOOTHING_WINDOW);
	private final EventStats otherErrors = EventStats.create(SMOOTHING_WINDOW);
	private final ValueStats requestHandlingTime = ValueStats.builder(SMOOTHING_WINDOW)
		.withUnit("milliseconds")
		.build();
	private final Map<Integer, EventStats> errorsByCode;

	private JsonRpcMethodStats() {
		Map<Integer, EventStats> built = new LinkedHashMap<>();
		// FR-033: the named codes are the closed key set; anything else lands in otherErrors
		for (JsonRpcError error : JsonRpcErrors.named()) {
			built.put(error.code(), EventStats.create(SMOOTHING_WINDOW));
		}
		// unmodifiableMap rather than Map.copyOf — the named-code declaration order is deliberate (it is
		// the JMX tabular display order), and copyOf makes no iteration-order guarantee
		this.errorsByCode = Collections.unmodifiableMap(built);
	}

	/** One stats object per registered wire name, built once at dispatcher {@code build()} (FR-034). */
	public static JsonRpcMethodStats create() {
		return new JsonRpcMethodStats();
	}

	/** Invocations that produced a {@code result}. */
	@JmxAttribute(extraSubAttributes = "totalCount")
	public EventStats getSuccessfulRequests() {
		return successfulRequests;
	}

	/** Invocations that produced an {@code error}. */
	@JmxAttribute(extraSubAttributes = "totalCount")
	public EventStats getFailedRequests() {
		return failedRequests;
	}

	/** Invocations answered with an application-chosen code outside the nine named {@link JsonRpcErrors} codes. */
	@JmxAttribute(extraSubAttributes = "totalCount")
	public EventStats getOtherErrors() {
		return otherErrors;
	}

	/**
	 * Latency of one invocation, in milliseconds — both successful and failed. A <b>never-called</b> method
	 * has <b>no samples</b> ({@code getCount() == 0}), never a zero-valued sample.
	 */
	@JmxAttribute(description = "time for handling one request in milliseconds (both successful and failed)")
	public ValueStats getRequestHandlingTime() {
		return requestHandlingTime;
	}

	/**
	 * The named-code breakdown. Pre-populated with the nine {@link JsonRpcErrors} codes at construction;
	 * an application-chosen code outside that set is counted in {@link #getOtherErrors()} and never creates
	 * an entry (FR-033a).
	 */
	@JmxAttribute
	public Map<Integer, EventStats> getErrorsByCode() {
		return errorsByCode;
	}

	/**
	 * The {@link JmxRefreshable} contract: the accumulators' smoothed values are recomputed by the JMX
	 * refresh cycle. Implemented so the per-worker tables aggregate through the map machinery's
	 * refreshable path (the same shape {@code RpcRequestStats} uses).
	 */
	@Override
	public void refresh(long timestamp) {
		successfulRequests.refresh(timestamp);
		failedRequests.refresh(timestamp);
		otherErrors.refresh(timestamp);
		requestHandlingTime.refresh(timestamp);
		for (EventStats bucket : errorsByCode.values()) {
			bucket.refresh(timestamp);
		}
	}
}
