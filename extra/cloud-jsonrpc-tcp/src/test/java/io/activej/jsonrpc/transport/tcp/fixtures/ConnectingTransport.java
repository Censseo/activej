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

import io.activej.jsonrpc.transport.JsonRpcTransport;
import io.activej.jsonrpc.transport.tcp.JsonRpcTcpTransport;
import io.activej.promise.Promise;
import org.jetbrains.annotations.Nullable;

/**
 * A {@link JsonRpcTransport} wrapper that establishes its connection <b>lazily</b> (research D8): the
 * conformance subjects of this module call {@code createTransport} / {@code createServerTransport}
 * synchronously from inside the harness, before the eventloop has run — and a connection driven to
 * completion there would never return, because an established connection keeps the loop alive
 * ({@code Eventloop.isAlive()} counts selector keys, research R3). The wrapper therefore returns
 * immediately, holding the pending promise, and defers everything — the listener registration and the
 * {@code send} — until it resolves, which happens inside the harness's
 * {@code io.activej.promise.TestUtils.await(send)} loop run.
 * <p>
 * The promise it holds is whichever end of the connection the harness plays: the dialled client
 * transport of {@link io.activej.jsonrpc.transport.tcp.JsonRpcTcpTransport#connect} in the forward
 * direction, and the <i>accepted server-side</i> transport in the reverse one — the same wrapper,
 * because {@code JsonRpcTcpTransport} is one class on both ends (FR-062).
 * <p>
 * {@link #send(byte[])} chains on that promise, so no document can be written before the connection
 * exists; {@link #setListener(Listener)} is forwarded to the resolved transport (whose serial read loop
 * starts exactly then, which is also why the listener must be installed <i>before</i> the first
 * {@code send} — {@code JsonRpcTcpTransport.send} refuses otherwise). Registration order does that: the
 * constructor's continuation is subscribed before {@code send}'s, so it runs first. A
 * {@link #closeEx(Exception)} before the connection resolves closes it on arrival; a failed connect
 * fails {@code send} with its cause and leaves nothing to close.
 * <p>
 * A test double, not a component: no reactor guard, driven on the reactor thread by the harness.
 */
public final class ConnectingTransport implements JsonRpcTransport {
	private final Promise<JsonRpcTcpTransport> connecting;
	private @Nullable JsonRpcTcpTransport transport;
	private @Nullable Listener listener;
	private boolean closed;
	private @Nullable Exception closeException;

	public ConnectingTransport(Promise<JsonRpcTcpTransport> connecting) {
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
		JsonRpcTcpTransport t = transport;
		if (t != null) t.setListener(listener);
	}

	@Override
	public void closeEx(Exception e) {
		if (closed) return;
		closed = true;
		closeException = e;
		JsonRpcTcpTransport t = transport;
		if (t != null) t.closeEx(e);
	}
}
