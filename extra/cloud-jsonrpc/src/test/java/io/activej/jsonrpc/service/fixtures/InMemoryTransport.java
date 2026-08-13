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

package io.activej.jsonrpc.service.fixtures;

import io.activej.async.exception.AsyncCloseException;
import io.activej.jsonrpc.transport.JsonRpcTransport;
import io.activej.promise.Promise;
import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static io.activej.common.Checks.checkState;

/**
 * A {@link JsonRpcTransport} with no I/O: it hands each sent document straight to a {@link Peer} and hands
 * the peer's answer straight back to the listener, in the calling stack frame, with no reactor hop and no
 * buffer anywhere (FR-086).
 *
 * <h2>Joining a client to a dispatcher</h2>
 * The peer is a callback rather than a named type so this fixture compiles against nothing it does not need.
 * {@code JsonRpcDispatcher.dispatch(byte[])} has exactly the {@link Peer} shape, so the intended wiring is a
 * method reference:
 * <pre>{@code
 * InMemoryTransport transport = InMemoryTransport.create(dispatcher::dispatch);
 * JsonRpcClient client = JsonRpcClient.builder(reactor, transport).build();
 * }</pre>
 * A peer that answers a zero-length array — which is how a dispatcher says "no response document", for a
 * notification or an all-notification batch — produces no delivery at all (obligation 3).
 *
 * <h2>Reorder mode</h2>
 * FR-094 requires that correlation survive responses arriving in an order no client could have predicted, so
 * this double can hold inbound documents and release them in an order the test chooses. It is
 * <b>deterministic and explicit</b> — {@link #startHolding()}, then {@link #release(int)},
 * {@link #releaseInReverseOrder()} or {@link #releaseInOrder()} — rather than randomised: a shuffling double
 * turns a correlation bug into a flaky test and proves nothing on the runs where the shuffle was the
 * identity.
 *
 * <h2>What this double deliberately does not do</h2>
 * It never fragments a document, so obligation 1 ("join before decoding") is satisfied by construction and
 * obligation 2 ({@code JsonRpcLimits.MAX_BODY_SIZE} applied <i>during</i> accumulation) has nothing to
 * accumulate. It therefore neither truncates nor refuses an oversized document: the decoder's own last line
 * of defence stays the thing under test, which is what lets the oversize conformance vector be replayed
 * through this transport unchanged. A real transport does not get that exemption.
 * <p>
 * It also cannot exhibit the failures a real transport does — partial writes, close racing a send,
 * reordering under load. Reordering is forced into the harness deliberately; the rest belongs to the first
 * real transport.
 *
 * <h2>Threading</h2>
 * Not {@code Reactive} and holding no reactor (FR-087) — which is the point: the SPI has to be implementable
 * by something that has neither. Every method runs on the caller's thread.
 */
public final class InMemoryTransport implements JsonRpcTransport {
	/**
	 * The far side. {@code Promise<byte[]> respond(byte[])} is exactly
	 * {@code JsonRpcDispatcher.dispatch(byte[])}'s shape; a zero-length or {@code null} answer means "no
	 * response document".
	 */
	@FunctionalInterface
	public interface Peer {
		Promise<byte[]> respond(byte[] document);
	}

	private final Peer peer;

	private final List<byte[]> sent = new ArrayList<>();
	private final List<byte[]> held = new ArrayList<>();

	private @Nullable Listener listener;
	private boolean holding;
	private boolean closed;
	private @Nullable Exception closeException;

	private InMemoryTransport(Peer peer) {
		this.peer = peer;
	}

	public static InMemoryTransport create(Peer peer) {
		return new InMemoryTransport(peer);
	}

	// region JsonRpcTransport

	@Override
	public void setListener(Listener listener) {
		this.listener = listener;
	}

	/**
	 * Hands the document to the peer and completes as soon as it has been handed over — <i>written</i>, not
	 * <i>answered</i> (obligation 4). The peer's answer, if there is one, reaches the listener through
	 * {@link #inbound(byte[])}, which for a synchronous peer happens before this method returns; a client
	 * that registers its correlation entry only after {@code send} returns is broken, and this double is
	 * built to catch that rather than to hide it.
	 * <p>
	 * A peer that fails its promise closes this transport with that exception: a double must not swallow a
	 * failure nobody else is watching.
	 */
	@Override
	public Promise<Void> send(byte[] document) {
		checkState(listener != null, "no listener registered — call setListener before sending");
		if (closed) return Promise.ofException(closeException);
		sent.add(document);
		peer.respond(document)
			.whenResult(this::inbound)
			.whenException(this::closeEx);
		return Promise.complete();
	}

	/** Closes this transport and reports {@code e} to the listener. Idempotent; {@code onClosed} fires once. */
	@Override
	public void closeEx(Exception e) {
		doClose(e);
	}

	// endregion

	// region driving the double

	/**
	 * Delivers a document the peer sent of its own accord — the server&rarr;client direction the SPI is duplex
	 * for. Subject to the hold, exactly like an answer.
	 */
	public void deliverFromPeer(byte[] document) {
		inbound(document);
	}

	/**
	 * Simulates the far side going away.
	 *
	 * @param cause the failure that closed it, or {@code null} for a clean peer close — which is the one
	 *              close the listener is told carried no cause
	 */
	public void closeFromPeer(@Nullable Exception cause) {
		doClose(cause);
	}

	public boolean isClosed() {
		return closed;
	}

	/** Every document {@link #send(byte[])} accepted, in send order. */
	public List<byte[]> sentDocuments() {
		return List.copyOf(sent);
	}

	/** {@link #sentDocuments()} decoded as UTF-8, which is what an assertion normally wants to read. */
	public List<String> sentText() {
		return sent.stream().map(InMemoryTransport::asString).toList();
	}

	// endregion

	// region reorder mode

	/** Turns reorder mode on: inbound documents accumulate instead of reaching the listener. */
	public void startHolding() {
		holding = true;
	}

	/**
	 * Turns reorder mode off. Documents already held stay held — releasing is always explicit, so that a test
	 * never depends on when the mode was flipped.
	 */
	public void stopHolding() {
		holding = false;
	}

	public boolean isHolding() {
		return holding;
	}

	public int heldCount() {
		return held.size();
	}

	/**
	 * Delivers the held document at {@code index} and leaves the rest held.
	 *
	 * @throws IndexOutOfBoundsException if nothing is held at {@code index} — a release nobody can satisfy is
	 *                                   a broken test, not a no-op
	 */
	public void release(int index) {
		byte[] document = held.remove(index);
		toListener(document);
	}

	/** Delivers everything held, in the order it was held. */
	public void releaseInOrder() {
		for (byte[] document : drainHeld()) {
			toListener(document);
		}
	}

	/**
	 * Delivers everything held, <b>last held first</b> — the deterministic reordering FR-094 asserts
	 * correlation survives.
	 */
	public void releaseInReverseOrder() {
		List<byte[]> batch = drainHeld();
		for (int i = batch.size() - 1; i >= 0; i--) {
			toListener(batch.get(i));
		}
	}

	// endregion

	private void inbound(@Nullable byte[] document) {
		if (closed) return;
		// obligation 3: "no response" is the absence of a call, not an empty one
		if (document == null || document.length == 0) return;
		if (holding) {
			held.add(document);
			return;
		}
		toListener(document);
	}

	private void toListener(byte[] document) {
		if (closed) return;
		//noinspection DataFlowIssue - send() and every public entry point requires a listener
		listener.onDocument(document);
	}

	/** A snapshot, so that a listener sending during delivery holds its own answers rather than this batch. */
	private List<byte[]> drainHeld() {
		List<byte[]> batch = List.copyOf(held);
		held.clear();
		return batch;
	}

	private void doClose(@Nullable Exception cause) {
		if (closed) return;
		closed = true;
		closeException = cause != null ? cause : new AsyncCloseException("peer closed");
		held.clear();
		if (listener != null) listener.onClosed(cause);
	}

	private static String asString(byte[] document) {
		return new String(document, StandardCharsets.UTF_8);
	}
}
