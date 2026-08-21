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

import io.activej.promise.Promise;
import io.activej.promise.SettablePromise;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * {@link TestApi}'s implementation — the sum of its two arguments, plus a recorded notification.
 * <p>
 * {@link #add(int, int)} is stateless. {@link #note(String)} records every delivered
 * {@code test.note} so a test can assert <i>exactly once</i>, and {@link #firstNote()} completes on
 * the next delivery so a test can await the notification instead of polling — a notification
 * produces no response document, so there is no promise on the caller's side to await.
 * <p>
 * Runs on the server's reactor thread (the dispatcher invokes it), so a plain {@code List} and a
 * single pending promise suffice; no synchronization is needed.
 */
public final class TestApiImpl implements TestApi {
	private final List<String> notes = new ArrayList<>();
	private @Nullable SettablePromise<Void> pendingFirst;

	@Override
	public Promise<AddResult> add(int a, int b) {
		return Promise.of(new AddResult(a + b));
	}

	@Override
	public void note(String text) {
		notes.add(text);
		SettablePromise<Void> pending = pendingFirst;
		if (pending != null) {
			pendingFirst = null;
			pending.set(null);
		}
	}

	/** Every {@code test.note} text delivered so far, in order. */
	public List<String> notes() {
		return notes;
	}

	/**
	 * Completes on the next delivery — the await point for "the notification arrived". A call after
	 * a delivery is an already-completed promise, so a test that awaits a handled notification does
	 * not hang.
	 */
	public Promise<Void> firstNote() {
		if (!notes.isEmpty()) return Promise.complete();
		SettablePromise<Void> pending = new SettablePromise<>();
		pendingFirst = pending;
		return pending;
	}
}
