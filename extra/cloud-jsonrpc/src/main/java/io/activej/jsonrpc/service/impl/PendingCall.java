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

package io.activej.jsonrpc.service.impl;

import io.activej.common.annotation.ExposedInternals;
import io.activej.json.JsonCodec;
import io.activej.jsonrpc.JsonRpcId;
import io.activej.promise.SettablePromise;
import io.activej.reactor.schedule.ScheduledRunnable;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * One call awaiting its answer: the value of {@code JsonRpcClient}'s correlation table, and — deliberately —
 * the holder of any future deadline (FR-067).
 *
 * <h2>One object, not two (ADR-030, detail 1)</h2>
 * {@code cloud-rpc}'s {@code RpcClientConnection} keeps a {@code ScheduledCallback} that <b>is</b> both the
 * map value and the scheduled timeout, so disarming a deadline when the answer arrives is a field access
 * rather than a second lookup in a second structure. That shape is copied here; none of its code is (verdict
 * 00-C).
 *
 * <h2>{@link #deadline} is reserved and stays {@code null}</h2>
 * This feature never writes {@link #deadline}. It exists so that the per-call timeout of a later feature adds
 * a <b>mechanism</b> — schedule on registration, cancel in the removal path — and not a <b>data structure</b>:
 * changing the table's value type later would touch every call site that this feature is deliberately keeping
 * to one. A reader who finds the field always {@code null} has found the intended state, not dead code.
 *
 * <h2>Removal precedes completion, and precedes decoding (FR-068, FR-069)</h2>
 * A {@code PendingCall} leaves the table through exactly one private {@code remove(id)} on the client, and it
 * leaves <b>before</b> the response payload is decoded and before {@link #promise} is completed. That ordering
 * is what makes the orphan rule stronger than {@code cloud-rpc}'s: an orphan value is never constructed, so
 * there is nothing to discard. It also means a continuation that issues a new call from inside
 * {@code promise}'s completion cannot observe a half-removed table.
 *
 * <h2>Not part of the supported API surface</h2>
 * {@code io.activej.jsonrpc.service.impl} is the client's own machinery. Reactor-confined and mutable:
 * everything here is touched on one thread only.
 */
@ExposedInternals
public final class PendingCall {
	/** The table's key, retained so the single removal path needs no closure capture. */
	public final JsonRpcId id;

	/** Completed exactly once, always after this call has left the table. */
	public final SettablePromise<Object> promise;

	/**
	 * The codec for the declared result type, or {@code null} <b>iff</b> the method declares
	 * {@code Promise<Void>} — whose wire value is the JSON literal {@code null} and needs no codec for
	 * {@code Void} at all (FR-030).
	 */
	public final @Nullable JsonCodec<?> resultCodec;

	/**
	 * Reserved for a later feature's per-call deadline. <b>Always {@code null} in this feature</b> — see the
	 * class documentation; nothing reads it and nothing writes it.
	 */
	public @Nullable ScheduledRunnable deadline;

	/**
	 * @param id          the identifier this call was sent with, and the table's key
	 * @param promise     the caller's promise, completed once and only after removal
	 * @param resultCodec the declared result's codec, or {@code null} for {@code Promise<Void>}
	 * @throws NullPointerException if {@code id} or {@code promise} is {@code null}
	 */
	public PendingCall(JsonRpcId id, SettablePromise<Object> promise, @Nullable JsonCodec<?> resultCodec) {
		this.id = Objects.requireNonNull(id, "id");
		this.promise = Objects.requireNonNull(promise, "promise");
		this.resultCodec = resultCodec;
	}

	@Override
	public String toString() {
		return "PendingCall[" + id + ']';
	}
}
