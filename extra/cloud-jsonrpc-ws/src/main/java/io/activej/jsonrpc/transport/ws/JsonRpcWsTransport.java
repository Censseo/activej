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

package io.activej.jsonrpc.transport.ws;

import io.activej.async.exception.AsyncCloseException;
import io.activej.http.HttpRequest;
import io.activej.http.IWebSocket;
import io.activej.http.IWebSocketClient;
import io.activej.http.WebSocketException;
import io.activej.jsonrpc.transport.JsonRpcTransport;
import io.activej.promise.Promise;
import io.activej.promise.SettablePromise;
import io.activej.reactor.AbstractReactive;
import io.activej.reactor.Reactor;
import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

import static io.activej.common.Checks.checkState;
import static io.activej.http.IWebSocket.Message;
import static io.activej.http.IWebSocket.Message.MessageType.BINARY;
import static io.activej.reactor.Reactive.checkInReactorThread;

/**
 * The WebSocket binding of {@link JsonRpcTransport} (research D3–D5): a duplex, message-oriented
 * channel carrying one complete JSON-RPC document per TEXT message, over core-http's message-level
 * {@link IWebSocket} API. The same class serves the client endpoint ({@link #connect(Reactor,
 * IWebSocketClient, HttpRequest)}) and the server session ({@link #of(Reactor, IWebSocket)}), which
 * is what makes the reverse-direction conformance suite a replay rather than a second
 * implementation.
 * <p>
 * <b>The framing rule (FR-010/FR-012/FR-013).</b> One TEXT message is exactly one document:
 * outbound {@code send} writes one unfragmented TEXT frame; inbound fragmented messages are joined
 * by {@code readMessage} <i>before</i> any byte reaches this transport (verdict 00-A's contiguity
 * rule holds at the message boundary), so the listener always receives one contiguous array. The
 * transport tier of the two-tier size bound is inherited: core-http applies
 * {@code HttpServer}/{@code HttpClient maxWebSocketMessageSize} (1 mb default) during accumulation
 * and answers the excess with close {@code 1009} (FR-016, FR-091).
 * <p>
 * <b>The serial read loop (obligations 1–3).</b> Started at {@link #setListener(Listener)}; exactly
 * one {@code readMessage} is outstanding at any moment. A TEXT payload is converted to bytes and
 * delivered synchronously; {@code null} (peer clean close, code 1000) resolves the loop; a read
 * failure (a {@link WebSocketException} carries the peer's code and reason verbatim) resolves it
 * too — both go through the single close latch, so {@link Listener#onClosed} fires exactly once
 * (R10, D4, obligation 6). A failure carrying close code {@code 1000} is D4's clean close
 * <i>normalized</i>: core-http fails {@code readMessage} with {@code WebSocketException(1000)} once
 * documents preceded the close (it resolves {@code null} only when the close frame arrives before any
 * data frame), so the transport maps that case to {@code onClosed(null)} rather than a close-with-cause
 * (T014).
 * <p>
 * <b>The internal write queue (FR-017, R7).</b> {@code WebSocket} enforces one {@code writeMessage}
 * in flight with a {@code checkState}, so concurrent sends from the two directions of one
 * connection are serialised here: each {@code send} appends to a promise chain, the promise
 * completes when its message has been <b>written</b> (obligation 4), and per-direction order is
 * preserved. A failed write is the close of the medium and closes the transport with its cause.
 * <p>
 * <b>Refusals (FR-014/FR-015, D5).</b> A BINARY message is refused with close {@code 1003}
 * (RFC 6455 §7.4.1 — data the endpoint cannot accept) and its payload ByteBuf recycled — the
 * module's only ByteBuf ownership (R8); an empty TEXT message is refused with close {@code 1002}
 * (RFC 6455 §7.4.1 — a zero-length document is never legal in this stack, obligation 3).
 * <p>
 * <b>Closing (FR-019).</b> {@link #closeEx(Exception)} is idempotent: it latches, closes the
 * websocket, fails every queued {@code send} with the close cause (or {@link AsyncCloseException}
 * when the close carried none), and fires {@code onClosed} exactly once. {@code send} after close
 * fails immediately with the same cause. The injected {@link IWebSocketClient} is <b>never</b>
 * closed (FR-065); only the websocket the transport wraps is owned.
 * <p>
 * <b>Threading.</b> Reactive: every public method opens with {@code checkInReactorThread(this)}.
 * {@code connect} failure (e.g. the admission gate answering a non-{@code 101}) fails the promise
 * with the cause — {@code HANDSHAKE_FAILED} for a refused upgrade — and registers nothing (FR-061).
 * <p>
 * <b>Per-document allocation.</b> One {@code String} outbound ({@code Message.text}) and one
 * {@code byte[]} inbound ({@code getBytes}), plus one {@code SettablePromise} per {@code send};
 * frame payloads are pooled by core-http and recycled by it, with the single BINARY-refusal
 * exception above.
 */
public final class JsonRpcWsTransport extends AbstractReactive implements JsonRpcTransport {
	private final IWebSocket webSocket;

	private @Nullable Listener listener;
	private boolean closed;
	private boolean closeSignalled;
	private @Nullable Exception closeException;
	private Promise<Void> writeTail = Promise.complete();

	private JsonRpcWsTransport(Reactor reactor, IWebSocket webSocket) {
		super(reactor);
		this.webSocket = Objects.requireNonNull(webSocket, "webSocket");
	}

	/**
	 * Wraps an already-established websocket — the server side, handed to the transport from the
	 * servlet's {@code onWebSocket}. The websocket is owned: closing the transport closes it.
	 */
	public static JsonRpcWsTransport of(Reactor reactor, IWebSocket webSocket) {
		return new JsonRpcWsTransport(
			Objects.requireNonNull(reactor, "reactor"),
			Objects.requireNonNull(webSocket, "webSocket"));
	}

	/**
	 * Performs the client upgrade and wraps the resulting websocket. On failure the returned promise
	 * fails with the cause and nothing is registered (FR-061). The injected {@link IWebSocketClient}
	 * is not owned and is never closed (FR-065).
	 */
	public static Promise<JsonRpcWsTransport> connect(Reactor reactor, IWebSocketClient client, HttpRequest request) {
		Objects.requireNonNull(reactor, "reactor");
		Objects.requireNonNull(client, "client");
		Objects.requireNonNull(request, "request");
		return client.webSocketRequest(request)
			.map(webSocket -> of(reactor, webSocket));
	}

	/**
	 * Sends one complete document as one unfragmented TEXT message (FR-010). The returned promise
	 * completes when the message has been written to the medium — never when an answer arrives
	 * (obligation 4); it fails with the close cause once the transport is closed. A zero-length
	 * array is refused immediately with {@link IllegalArgumentException} (FR-020); a send before
	 * {@link #setListener(Listener)} is refused as a programmer error. {@code document} is not
	 * retained after the returned promise completes (a queued send holds the array until its write
	 * runs — the SPI's exact wording, obligation 4).
	 */
	@Override
	public Promise<Void> send(byte[] document) {
		checkInReactorThread(this);
		if (closed) return Promise.ofException(closedException());
		Objects.requireNonNull(document, "document");
		if (document.length == 0) {
			return Promise.ofException(new IllegalArgumentException("Document must not be empty (FR-020)"));
		}
		checkState(listener != null, "setListener must be called before the first send");
		SettablePromise<Void> sendPromise = new SettablePromise<>();
		writeTail = writeTail.then(
			$ -> doWrite(document, sendPromise),
			// the previous write failed and closed the transport: this send fails with its cause
			// and the chain stays healthy for whatever the caller queues next (which will fail fast)
			e -> {
				sendPromise.setException(e);
				return Promise.complete();
			});
		return sendPromise;
	}

	/**
	 * Registers the listener documents are delivered to and starts the serial read loop. Must be
	 * called once, before the first {@link #send(byte[])}; a second call is refused (FR-019). When
	 * the transport is already closed — e.g. a local {@code closeEx} before any listener existed —
	 * {@link Listener#onClosed} fires immediately, exactly once.
	 */
	@Override
	public void setListener(Listener listener) {
		checkInReactorThread(this);
		checkState(this.listener == null, "Listener is already set");
		this.listener = Objects.requireNonNull(listener, "listener");
		if (closed) {
			signalClose(closeException);
			return;
		}
		doRead();
	}

	/**
	 * Closes the transport: idempotent, fires {@link Listener#onClosed} exactly once (obligation 6),
	 * fails every queued and future {@link #send(byte[])} with {@code e}, and closes the owned
	 * websocket — a {@link WebSocketException} becomes the close frame carrying its code and reason,
	 * any other exception becomes the generic going-away/server-error close (core-http's rule). The
	 * injected {@link IWebSocketClient} is never closed (FR-065).
	 */
	@Override
	public void closeEx(Exception e) {
		checkInReactorThread(this);
		Objects.requireNonNull(e, "e");
		if (closed) return;
		closed = true;
		closeException = e;
		webSocket.closeEx(e);
		signalClose(e);
	}

	/** One {@code readMessage} at a time (FR-090); the loop is only re-issued after delivery. */
	private void doRead() {
		Listener listener = this.listener;
		if (listener == null) return;
		webSocket.readMessage()
			.whenComplete((message, e) -> {
				if (e != null) {
					// D4's clean close must hold even when documents preceded the close: core-http
					// resolves readMessage() to null only when the close frame arrives before any data
					// frame, and fails it with WebSocketException(1000) (REGULAR_CLOSE) otherwise. Code
					// 1000 IS the clean close, so it normalizes to onClosed(null) (T014, FR-018).
					if (e instanceof WebSocketException wsException && wsException.getCode() == 1000) {
						closeCleanly();
					} else {
						closeEx(e);
					}
					return;
				}
				if (message == null) {
					closeCleanly();
					return;
				}
				if (message.getType() == BINARY) {
					// R8: the binary payload is pooled and owned by us — recycle it, then refuse
					message.getBuf().recycle();
					closeEx(new WebSocketException(1003, "binary messages are not supported"));
					return;
				}
				String text = message.getText();
				if (text.isEmpty()) {
					closeEx(new WebSocketException(1002, "empty message"));
					return;
				}
				listener.onDocument(text.getBytes(StandardCharsets.UTF_8));
				doRead();
			});
	}

	private Promise<Void> doWrite(byte[] document, SettablePromise<Void> sendPromise) {
		if (closed) {
			sendPromise.setException(closedException());
			return Promise.complete();
		}
		return webSocket.writeMessage(Message.text(new String(document, StandardCharsets.UTF_8)))
			.whenComplete(($, e) -> {
				if (e != null) {
					sendPromise.setException(e);
					closeEx(e);
				} else {
					sendPromise.set(null);
				}
			});
	}

	/** Peer clean close (code 1000): no cause, the websocket has already echoed the close (R10). */
	private void closeCleanly() {
		if (closed) return;
		closed = true;
		closeException = null;
		signalClose(null);
	}

	/**
	 * Fires {@link Listener#onClosed} exactly once — and only once there is a listener to deliver
	 * to. A close that happens <b>before</b> {@link #setListener(Listener)} (obligation 6: local,
	 * remote or failed alike) must still reach the listener: arming the latch with no listener
	 * present would swallow the signal, so the latch is armed only at delivery, and a listener
	 * registered after the close is told about it immediately (B3, adversarial plan).
	 */
	private void signalClose(@Nullable Exception e) {
		if (closeSignalled) return;
		if (listener == null) return;
		closeSignalled = true;
		listener.onClosed(e);
	}

	private Exception closedException() {
		Exception e = closeException;
		return e != null ? e : new AsyncCloseException("the transport is closed");
	}
}