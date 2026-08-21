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

import io.activej.jsonrpc.service.JsonRpcDispatcher;
import io.activej.jsonrpc.transport.JsonRpcTransport;
import io.activej.promise.Promise;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * The reorderable double of both conformance directions (research D8/D9, FR-073/FR-076): an
 * <b>in-memory</b> {@link JsonRpcTransport} over a real {@link JsonRpcDispatcher}, which holds the
 * answers it produces and delivers them in an order the test chooses.
 *
 * <h2>Why in memory, when everything else in this module is a real socket</h2>
 * The harness asserts {@code heldCount() == 3} <b>synchronously</b>, immediately after three proxy
 * calls and before the eventloop has run — no socket-backed transport can satisfy that, because
 * nothing has yet crossed the wire at that point. So the double models the one property the test is
 * actually about: a transport whose §6 ordering guarantee is <i>none</i>. Dispatch over the harness's
 * own service completes synchronously, so the three answers exist by the time the three calls return;
 * {@link #releaseInReverseOrder()} then delivers them last-held-first, and the {@code JsonRpcClient}
 * above must still resolve every promise with its own answer — correlation by {@code id} alone
 * (feature 012, FR-066), which is what the harness proves. Genuine socket-level reordering is asserted
 * elsewhere in this module, by real concurrent calls answered out of order.
 *
 * <h2>Why this is a class and not the harness's interface</h2>
 * Both harnesses declare their own {@code protected interface ReorderableTransport}, which only a
 * subclass of that harness may name. This fixture therefore carries the shape — the same four methods
 * — and each subject declares a one-line subclass that implements its own harness's interface. Two
 * nested interfaces, one implementation.
 * <p>
 * A test double, not a component: no reactor guard, driven on the reactor thread by the harness.
 */
public class HoldingTransport implements JsonRpcTransport {
	private final JsonRpcDispatcher peer;

	private final List<byte[]> held = new ArrayList<>();
	private @Nullable Listener listener;
	private boolean holding;
	private boolean closed;

	public HoldingTransport(JsonRpcDispatcher peer) {
		this.peer = peer;
	}

	@Override
	public Promise<Void> send(byte[] document) {
		peer.dispatch(document)                                  // total; synchronous for the harness's service
			.whenResult(response -> {
				if (closed || response.length == 0) return;       // obligation 3: "no response" = no call
				if (holding) held.add(response);
				else listener.onDocument(response);
			});
		return Promise.complete();
	}

	@Override
	public void setListener(Listener listener) {
		this.listener = listener;
	}

	@Override
	public void closeEx(Exception e) {
		if (closed) return;
		closed = true;
		if (listener != null) listener.onClosed(e);
	}

	/** This double is its own transport — the harness's {@code ReorderableTransport.transport()}. */
	public JsonRpcTransport transport() {
		return this;
	}

	/** Inbound documents accumulate instead of reaching the listener. */
	public void startHolding() {
		holding = true;
	}

	public int heldCount() {
		return held.size();
	}

	/** Delivers everything held, <b>last held first</b>. */
	public void releaseInReverseOrder() {
		for (int i = held.size() - 1; i >= 0; i--) listener.onDocument(held.get(i));
		held.clear();
	}
}
