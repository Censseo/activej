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

import io.activej.jsonrpc.transport.JsonRpcTransport;
import io.activej.jsonrpc.transport.ws.JsonRpcWsTransport;
import io.activej.promise.Promise;
import org.jetbrains.annotations.Nullable;

/**
 * A {@link JsonRpcTransport} wrapper that performs the WebSocket handshake <b>lazily</b> (D8): the
 * conformance subjects of this module call {@code createTransport} / {@code createServerTransport}
 * synchronously from inside the harness, before the eventloop has run — and a handshake driven to
 * completion there would never return, because an established connection keeps the loop alive
 * (R3: {@code Eventloop.isAlive()} counts selector keys). The wrapper therefore returns immediately,
 * holding the pending {@code connect} promise, and defers everything — the listener registration and
 * the {@code send} — until the connection resolves, which happens inside the harness's
 * {@code io.activej.promise.TestUtils.await(send)} loop run.
 * <p>
 * {@link #send(byte[])} chains on the connection promise, so the document cannot be written before the
 * handshake is complete; {@link #setListener(Listener)} is forwarded to the resolved transport (whose
 * read loop starts exactly then); a {@link #closeEx(Exception)} before the connection resolves closes
 * it on arrival. A failed handshake fails {@code send} with its cause and leaves nothing to close.
 * <p>
 * A test double, not a component: no reactor guard, driven on the reactor thread by the harness.
 */
public final class ConnectingTransport implements JsonRpcTransport {
	private final Promise<JsonRpcWsTransport> connecting;
	private @Nullable JsonRpcWsTransport transport;
	private @Nullable Listener listener;
	private boolean closed;
	private @Nullable Exception closeException;

	public ConnectingTransport(Promise<JsonRpcWsTransport> connecting) {
		this.connecting = connecting;
		connecting.whenResult(t -> {
			transport = t;
			if (closed) {
				t.closeEx(closeException);
				return;
			}
			if (listener != null) t.setListener(listener);
		});
	}

	@Override
	public Promise<Void> send(byte[] document) {
		return connecting.then(t -> t.send(document));
	}

	@Override
	public void setListener(Listener listener) {
		this.listener = listener;
		JsonRpcWsTransport t = transport;
		if (t != null) t.setListener(listener);
	}

	@Override
	public void closeEx(Exception e) {
		if (closed) return;
		closed = true;
		closeException = e;
		JsonRpcWsTransport t = transport;
		if (t != null) t.closeEx(e);
	}
}
