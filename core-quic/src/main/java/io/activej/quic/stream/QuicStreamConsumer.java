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
import io.activej.csp.consumer.AbstractChannelConsumer;
import io.activej.csp.consumer.ChannelConsumer;
import io.activej.promise.Promise;
import org.jetbrains.annotations.Nullable;

/**
 * The sending half of a QUIC stream (RFC 9000 §2.2) as a CSP {@link ChannelConsumer}.
 * <p>
 * {@code accept(buf)} <b>takes ownership of {@code buf} on every path</b> — including rejection,
 * reset and close — and resolves only once every one of its bytes has become a {@code STREAM} frame
 * handed to the transport (FR-020, research R-06). While flow control blocks progress the promise
 * stays pending, which is what makes CSP's backpressure equal QUIC's: no queue can form between the
 * application and the transport, because a {@link ChannelConsumer} issues one write at a time.
 * <p>
 * {@code accept(null)} writes the end-of-data marker: a {@code STREAM} frame carrying {@code FIN}
 * (RFC 9000 §19.8), which fixes the stream's final size (RFC 9000 §4.5).
 * <p>
 * Constructed on, and confined to, its stream's reactor —
 * {@link io.activej.csp.consumer.AbstractChannelConsumer} captures the current reactor and guards
 * {@code accept()} with it.
 *
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9000#section-2.2">RFC 9000 §2.2 — Sending and Receiving Data</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9000#section-3.1">RFC 9000 §3.1 — Sending Stream States</a>
 */
final class QuicStreamConsumer extends AbstractChannelConsumer<ByteBuf> {
	private final SendPart part;

	QuicStreamConsumer(SendPart part) {
		this.part = part;
	}

	@Override
	protected Promise<Void> doAccept(@Nullable ByteBuf value) {
		return value == null ? part.writeFin() : part.write(value);
	}

	/**
	 * Released synchronously rather than from {@code onCleanup}, which the base class posts: a withheld
	 * buffer must not outlive the last reactor tick a shutting-down process will run.
	 */
	@Override
	protected void onClosed(Exception e) {
		part.closeEx(e);
	}

	@Override
	public String toString() {
		return "QuicStreamConsumer{" + part + '}';
	}
}
