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

package io.activej.quic.stream;

import io.activej.bytebuf.ByteBuf;
import io.activej.csp.supplier.AbstractChannelSupplier;
import io.activej.csp.supplier.ChannelSupplier;
import io.activej.promise.Promise;

/**
 * The receiving half of a QUIC stream (RFC 9000 §2.2) as a CSP {@link ChannelSupplier}: the bridge
 * between QUIC's flow control and CSP's inherent, one-item-at-a-time backpressure (research R-05,
 * R-06).
 * <p>
 * Each {@code get()} resolves with the <b>next contiguous slice</b> of the stream — never a
 * concatenation, never a copy, and never an empty buffer — or with {@code null} at end-of-stream.
 * When nothing is contiguous the promise stays pending, which is exactly the backpressure signal:
 * taking a slice is what advances the consumed offset, and the consumed offset is what releases more
 * flow-control credit to the peer. A reader that stops reading therefore stalls the sender at the
 * window rather than growing this endpoint's memory. That is the design, not a defect.
 * <p>
 * <b>Buffer ownership (DI-1)</b>: <b>the caller owns and must recycle</b> every buffer it takes.
 * Closing this supplier releases everything the receiving part still holds.
 * <p>
 * Constructed on, and confined to, its stream's reactor —
 * {@link io.activej.csp.supplier.AbstractChannelSupplier} captures the current reactor and guards
 * {@code get()} with it.
 *
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9000#section-2.2">RFC 9000 §2.2 — Sending and Receiving Data</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9000#section-3.2">RFC 9000 §3.2 — Receiving Stream States</a>
 */
final class QuicStreamSupplier extends AbstractChannelSupplier<ByteBuf> {
	private final ReceivePart part;

	QuicStreamSupplier(ReceivePart part) {
		this.part = part;
	}

	@Override
	protected Promise<ByteBuf> doGet() {
		return part.read();
	}

	/**
	 * Released synchronously rather than from {@code onCleanup}, which the base class posts: a stream
	 * abandoned during connection teardown must not leave a buffer alive past the last reactor tick a
	 * test — or a shutting-down process — will run.
	 */
	@Override
	protected void onClosed(Exception e) {
		part.closeEx(e);
	}

	@Override
	public String toString() {
		return "QuicStreamSupplier{" + part + '}';
	}
}
