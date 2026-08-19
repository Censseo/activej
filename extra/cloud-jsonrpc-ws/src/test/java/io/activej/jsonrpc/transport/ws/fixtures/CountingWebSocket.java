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

import io.activej.http.HttpRequest;
import io.activej.http.HttpResponse;
import io.activej.http.IWebSocket;
import io.activej.promise.Promise;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * A delegating {@link IWebSocket} that counts how many {@code writeMessage} calls are in flight at
 * once — the observable proof of FR-017's one-{@code writeMessage}-in-flight rule. Reactor-confined:
 * the delegate's promises complete on the reactor thread, so plain {@code int}s suffice.
 */
public final class CountingWebSocket implements IWebSocket {
	private final IWebSocket delegate;
	private int writesInFlight;
	private int maxConcurrentWrites;

	public CountingWebSocket(IWebSocket delegate) {
		this.delegate = Objects.requireNonNull(delegate, "delegate");
	}

	/** The largest number of simultaneously in-flight {@code writeMessage} calls ever observed. */
	public int maxConcurrentWrites() {
		return maxConcurrentWrites;
	}

	@Override
	public Promise<Void> writeMessage(@Nullable Message msg) {
		writesInFlight++;
		if (writesInFlight > maxConcurrentWrites) maxConcurrentWrites = writesInFlight;
		return delegate.writeMessage(msg)
			.whenComplete(($, e) -> writesInFlight--);
	}

	@Override
	public Promise<Message> readMessage() {
		return delegate.readMessage();
	}

	@Override
	public Promise<Frame> readFrame() {
		return delegate.readFrame();
	}

	@Override
	public Promise<Void> writeFrame(@Nullable Frame frame) {
		return delegate.writeFrame(frame);
	}

	@Override
	public void closeEx(Exception e) {
		delegate.closeEx(e);
	}

	@Override
	public boolean isClosed() {
		return delegate.isClosed();
	}

	@Override
	public HttpRequest getRequest() {
		return delegate.getRequest();
	}

	@Override
	public HttpResponse getResponse() {
		return delegate.getResponse();
	}
}