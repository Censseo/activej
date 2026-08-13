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

package io.activej.jsonrpc.transport;

import io.activej.async.process.AsyncCloseable;
import io.activej.promise.Promise;
import org.jetbrains.annotations.Nullable;

/**
 * The transport SPI of the JSON-RPC line: a <b>duplex, message-oriented</b> channel carrying complete
 * JSON-RPC documents as contiguous {@code byte[]} (FR-081, FR-082).
 *
 * <h2>Duplex, not request/response</h2>
 * Outbound is {@link #send(byte[])}, inbound is a {@link Listener} this transport pushes to. It is
 * deliberately <b>not</b> {@code Promise<byte[]> request(byte[])}: that shape is marginally simpler for an
 * HTTP transport and makes a server&rarr;client call impossible without a redesign, which is precisely the
 * redesign this SPI exists to avoid. An HTTP transport satisfies the duplex shape trivially by feeding each
 * response body to its listener; the asymmetry costs it nothing.
 *
 * <h2>Implementor obligations</h2>
 * <ol>
 *     <li><b>Join before decoding.</b> {@code document} is one <b>complete, contiguous</b> JSON-RPC document.
 *     A transport whose bytes arrive in pieces joins them before calling {@link Listener#onDocument} — never
 *     hands a fragment to a decoder and never decodes across pieces. This is a hard contract, not a
 *     convenience: {@code JsonRpcDecoder} leaves {@code params}/{@code result}/{@code error.data} as index
 *     pairs into <i>the array it was given</i>, and those indices do not survive a buffer refill.</li>
 *
 *     <li><b>Bound the accumulation, not the result.</b> Apply {@code JsonRpcLimits.MAX_BODY_SIZE}
 *     <b>during</b> accumulation and refuse as soon as the running total crosses it — never accumulate a
 *     whole document and check its length afterwards. A bound checked against an array that already exists
 *     arrives after the allocation it was meant to prevent. The decoder re-applies the same bound, but as a
 *     last line of defence rather than a first one.</li>
 *
 *     <li><b>Never deliver a zero-length document.</b> "No response" is the absence of a call, not an empty
 *     one — a notification and an all-notification batch both produce nothing, and a dispatcher signals that
 *     with a zero-length array which must not reach the wire or a listener.</li>
 *
 *     <li><b>{@code send}'s promise means <i>written</i>, not <i>answered</i>.</b> It completes when the
 *     document has been handed to the underlying medium. Correlating an answer is the caller's job, and it
 *     does it by {@code id}.</li>
 *
 *     <li><b>Assume nothing about pairing or order.</b> A document sent need not produce a document received,
 *     one sent may produce several received, and responses may arrive in any order relative to the requests
 *     that caused them. JSON-RPC 2.0 &sect;6 guarantees no ordering and a transport is entitled to reorder.</li>
 *
 *     <li><b>Closing is idempotent and reported exactly once.</b> {@link #close()} and
 *     {@link #closeEx(Exception)} may be called any number of times; {@link Listener#onClosed} fires exactly
 *     once, whether the close was local, remote or a failure of the medium itself.</li>
 *
 *     <li><b>Everything transport-specific stays inside the implementation.</b> ByteBuf ownership, framing,
 *     connection establishment, reconnection and connection lifetime are entirely the implementor's, and none
 *     of them appears in this interface. {@code JsonRpcTransportBoundaryTest} enforces that this package names
 *     no such type (FR-084).</li>
 * </ol>
 *
 * <h2>Deliberately not {@code Reactive}</h2>
 * This interface does <b>not</b> extend {@code Reactive} (FR-087). An implementation is free to be reactive
 * — a socket-backed one will be, and will guard its public methods with {@code checkInReactorThread(this)} —
 * but an in-memory one must not be forced to carry a reactor it never uses. The components on both ends of
 * this SPI are reactor-confined regardless, so a reactive transport is called from the reactor thread in
 * practice.
 * <p>
 * 'Not {@code Reactive}' excuses an implementation from <i>carrying</i> a reactor, not from delivering on
 * one: {@code onDocument} and {@code onClosed} must fire on the reactor thread of the component that owns
 * this transport. {@code JsonRpcClient} guards both callbacks with {@code checkInReactorThread(this)}, so
 * an off-thread delivery fails fast instead of silently corrupting its correlation table. A transport whose
 * bytes arrive on its own I/O thread hops (its {@code Reactor#post} is the usual one) rather than calling
 * the listener directly.
 *
 * <h2>Why {@code byte[]} and not a pooled buffer</h2>
 * The decoder needs one contiguous array, so the copy exists wherever the boundary is drawn. Drawing it here
 * keeps buffer ownership — and the recycling obligation that comes with it — inside each transport, which is
 * where the knowledge of when a buffer may be released actually lives. The cost is one array copy per
 * document at each boundary.
 */
public interface JsonRpcTransport extends AsyncCloseable {
	/**
	 * Sends one complete JSON-RPC document.
	 *
	 * @param document the whole document as a contiguous array, never empty. The transport does not retain it
	 *                 after the returned promise completes
	 * @return a promise completing when the document has been <b>written</b> to the underlying medium, or
	 * failing with the medium's exception. It says nothing about an answer
	 */
	Promise<Void> send(byte[] document);

	/**
	 * Registers the listener receiving inbound documents and the close signal.
	 * <p>
	 * Called once, before the first {@link #send(byte[])}. An implementation is free to buffer or to drop
	 * documents that arrive before a listener exists, and must document which it does.
	 */
	void setListener(Listener listener);

	/**
	 * The inbound half of the SPI: what a transport pushes to its owner.
	 */
	interface Listener {
		/**
		 * Delivers one complete, contiguous JSON-RPC document. Never called with a zero-length array
		 * (obligation 3), and never called after {@link #onClosed}.
		 */
		void onDocument(byte[] document);

		/**
		 * Signals that the transport is closed and no further document will arrive. Fires <b>exactly once</b>
		 * (obligation 6).
		 *
		 * @param e the cause when the close was a failure, or the exception a local close carried;
		 *          {@code null} when the peer closed cleanly
		 */
		void onClosed(@Nullable Exception e);
	}
}
