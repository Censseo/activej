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

import io.activej.async.exception.AsyncCloseException;
import io.activej.bytebuf.ByteBuf;
import io.activej.net.socket.tcp.ITcpSocket;
import io.activej.promise.Promise;
import org.jetbrains.annotations.Nullable;

/**
 * A socket that is <b>already dead when it is handed over</b> — the zombie-guard input (research
 * risk 2, FR-032). The real-world case is a peer that resets its connection between the accept and
 * the moment the session's read loop starts; that race cannot be provoked deterministically over a
 * real socket, so it is expressed here as a socket that answers {@code isClosed()} with
 * {@code true} and fails every operation immediately.
 * <p>
 * It is a stub of the medium, not of the transport: the transport under test is the real one, its
 * framing is the real one, and what is faked is only the fact the guard reads. Every {@code write}
 * recycles the buffer it is given, because a socket that refuses a write still owns it (DI-1).
 */
public final class ClosedTcpSocket implements ITcpSocket {
	@Override
	public Promise<ByteBuf> read() {
		return Promise.ofException(new AsyncCloseException("the socket is already closed"));
	}

	@Override
	public Promise<Void> write(@Nullable ByteBuf buf) {
		if (buf != null) buf.recycle();
		return Promise.ofException(new AsyncCloseException("the socket is already closed"));
	}

	@Override
	public boolean isReadAvailable() {
		return false;
	}

	@Override
	public boolean isClosed() {
		return true;
	}

	@Override
	public void closeEx(Exception e) {
		// already closed: idempotent, and there is nothing underneath to release
	}
}
