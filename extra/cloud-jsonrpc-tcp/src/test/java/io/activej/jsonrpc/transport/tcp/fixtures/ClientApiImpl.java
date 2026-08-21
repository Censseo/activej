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

package io.activej.jsonrpc.transport.tcp.fixtures;

import io.activej.json.JsonCodecs;
import io.activej.jsonrpc.JsonRpcErrors;
import io.activej.jsonrpc.JsonRpcException;
import io.activej.jsonrpc.JsonRpcPayload;
import io.activej.promise.Promise;
import io.activej.promise.SettablePromise;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * {@link ClientApi}'s implementation, installed on a <b>client's</b> own {@code JsonRpcDispatcher}
 * — the client side of a server-initiated call. {@link #decide(int)} answers with a value, so the
 * server's {@code Promise} completes with it; {@link #fail()} fails with an application
 * {@code JsonRpcException} carrying a custom code, message and {@code data}, so the server-side
 * caller sees them verbatim; {@link #event(long)} records each broadcast delivery and exposes
 * {@link #firstEvent()} as its await point, since a notification leaves the sender nothing to
 * await.
 * <p>
 * Runs on the client's reactor thread (the dispatcher invokes it); a plain {@code List} and a
 * single pending promise suffice, no synchronization is needed.
 */
public final class ClientApiImpl implements ClientApi {
	private final List<Long> events = new ArrayList<>();
	private @Nullable SettablePromise<Void> pendingFirst;

	@Override
	public Promise<String> decide(int n) {
		return Promise.of("decided-" + n);
	}

	@Override
	public Promise<String> fail() {
		return Promise.ofException(new JsonRpcException(JsonRpcErrors.of(42, "the-client-said-no",
			JsonRpcPayload.encoded(JsonCodecs.ofString(), "detail"))));
	}

	@Override
	public void event(long id) {
		events.add(id);
		SettablePromise<Void> pending = pendingFirst;
		if (pending != null) {
			pendingFirst = null;
			pending.set(null);
		}
	}

	/** Every {@code client.event} id delivered so far, in order. */
	public List<Long> events() {
		return events;
	}

	/**
	 * Completes on the next delivery — the await point for "the broadcast reached this client". A
	 * call after a delivery is an already-completed promise, so a test that awaits a handled event
	 * does not hang.
	 */
	public Promise<Void> firstEvent() {
		if (!events.isEmpty()) return Promise.complete();
		SettablePromise<Void> pending = new SettablePromise<>();
		pendingFirst = pending;
		return pending;
	}
}
