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

package io.activej.jsonrpc.transport.ws.fixtures;

import io.activej.promise.Promise;
import io.activej.promise.SettablePromise;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * {@link UserEvents}'s implementation, installed on a client's own {@code JsonRpcDispatcher} (the
 * client side of a broadcast). Records every delivered {@code userEvents.changed} so a test can
 * assert <i>exactly once per client</i>, and exposes {@link #firstInvocation()} — a promise that
 * completes on the next delivery — so a test can await the notification instead of polling.
 * <p>
 * Runs on the client's reactor thread (the dispatcher invokes it), so a plain {@code List} and a
 * single pending promise suffice; no synchronization is needed.
 */
public final class UserEventsImpl implements UserEvents {
	private final List<Long> ids = new ArrayList<>();
	private @Nullable SettablePromise<Void> pendingFirst;

	@Override
	public void userChanged(long id) {
		ids.add(id);
		SettablePromise<Void> pending = pendingFirst;
		if (pending != null) {
			pendingFirst = null;
			pending.set(null);
		}
	}

	/** Every {@code userEvents.changed} id delivered so far, in order. */
	public List<Long> ids() {
		return ids;
	}

	/**
	 * Completes on the next delivery — the await point for "the client's handler fired once". A
	 * second call after a delivery is a completed promise, so a test that awaits an already-handled
	 * notification does not hang.
	 */
	public Promise<Void> firstInvocation() {
		if (!ids.isEmpty()) return Promise.complete();
		SettablePromise<Void> pending = new SettablePromise<>();
		pendingFirst = pending;
		return pending;
	}
}
