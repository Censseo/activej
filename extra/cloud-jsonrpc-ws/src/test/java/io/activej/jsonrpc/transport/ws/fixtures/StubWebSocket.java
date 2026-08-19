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
import io.activej.promise.SettablePromise;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

import static io.activej.common.Checks.checkState;

/**
 * A controllable {@link IWebSocket} for the transport's pure-programmer-error tests (T004: zero-length
 * send, second {@code setListener}, send-after-close). Mirrors {@code WebSocket}'s serial read/write
 * contract and close semantics without any I/O: {@code readMessage}/{@code writeMessage} stay pending
 * until the test completes them or the socket is closed, and {@code closeEx} fails the pending
 * operation with the close exception — exactly what a real socket does when the transport closes it.
 * The frame-level API is deliberately absent: these tests never touch frames (FR-011).
 */
public final class StubWebSocket implements IWebSocket {
	private @Nullable SettablePromise<Message> readPromise;
	private @Nullable SettablePromise<Void> writePromise;
	private @Nullable Exception exception;
	private final List<String> writtenTexts = new ArrayList<>();

	@Override
	public Promise<Message> readMessage() {
		checkState(readPromise == null, "Concurrent reads");
		if (exception != null) return Promise.ofException(exception);
		SettablePromise<Message> readPromise = new SettablePromise<>();
		this.readPromise = readPromise;
		return readPromise;
	}

	@Override
	public Promise<Void> writeMessage(@Nullable Message msg) {
		checkState(writePromise == null, "Concurrent writes");
		// the payload as this socket saw it, recorded at the moment writeMessage was CALLED — the
		// observation point for whether the transport reads the caller's array eagerly or lazily
		// (G7, adversarial plan). TEXT only: the transport never writes BINARY, and getText() would throw.
		if (msg != null && msg.getType() == Message.MessageType.TEXT) writtenTexts.add(msg.getText());
		if (exception != null) return Promise.ofException(exception);
		SettablePromise<Void> writePromise = new SettablePromise<>();
		this.writePromise = writePromise;
		return writePromise;
	}

	@Override
	public void closeEx(Exception e) {
		if (exception != null) return;
		exception = e;
		if (readPromise != null) {
			readPromise.setException(e);
			readPromise = null;
		}
		if (writePromise != null) {
			writePromise.setException(e);
			writePromise = null;
		}
	}

	@Override
	public boolean isClosed() {
		return exception != null;
	}

	@Override
	public HttpRequest getRequest() {
		throw new UnsupportedOperationException();
	}

	@Override
	public HttpResponse getResponse() {
		throw new UnsupportedOperationException();
	}

	@Override
	public Promise<Frame> readFrame() {
		throw new UnsupportedOperationException();
	}

	@Override
	public Promise<Void> writeFrame(@Nullable Frame frame) {
		throw new UnsupportedOperationException();
	}

	// test helpers — resolve a pending operation the way the socket's I/O would

	public void deliverMessage(Message message) {
		checkState(readPromise != null, "no pending read");
		SettablePromise<Message> readPromise = this.readPromise;
		this.readPromise = null;
		readPromise.set(message);
	}

	public void failRead(Exception e) {
		checkState(readPromise != null, "no pending read");
		SettablePromise<Message> readPromise = this.readPromise;
		this.readPromise = null;
		readPromise.setException(e);
	}

	/** Every TEXT payload handed to {@link #writeMessage(Message)}, in call order. */
	public List<String> writtenTexts() {
		return writtenTexts;
	}

	public void completeWrite() {
		checkState(writePromise != null, "no pending write");
		SettablePromise<Void> writePromise = this.writePromise;
		this.writePromise = null;
		writePromise.set(null);
	}

	/**
	 * Fails the pending write the way a dead socket would — without going through {@link #closeEx},
	 * so the transport's own reaction to a failed {@code writeMessage} is what the test observes
	 * (B2, adversarial plan). Mirrors {@link #failRead(Exception)}.
	 */
	public void failWrite(Exception e) {
		checkState(writePromise != null, "no pending write");
		SettablePromise<Void> writePromise = this.writePromise;
		this.writePromise = null;
		writePromise.setException(e);
	}
}