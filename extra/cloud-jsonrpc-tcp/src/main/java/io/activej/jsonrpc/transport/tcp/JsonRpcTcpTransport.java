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

package io.activej.jsonrpc.transport.tcp;

import io.activej.async.exception.AsyncCloseException;
import io.activej.bytebuf.ByteBuf;
import io.activej.common.Checks;
import io.activej.common.MemSize;
import io.activej.common.builder.AbstractBuilder;
import io.activej.common.exception.MalformedDataException;
import io.activej.common.exception.TruncatedDataException;
import io.activej.csp.binary.BinaryChannelSupplier;
import io.activej.csp.binary.decoder.impl.OfByteTerminated;
import io.activej.csp.supplier.ChannelSupplier;
import io.activej.csp.supplier.ChannelSuppliers;
import io.activej.jsonrpc.JsonRpcLimits;
import io.activej.jsonrpc.transport.JsonRpcTransport;
import io.activej.net.socket.tcp.ITcpSocket;
import io.activej.net.socket.tcp.TcpSocket;
import io.activej.promise.Promise;
import io.activej.reactor.AbstractReactive;
import io.activej.reactor.Reactor;
import io.activej.reactor.net.SocketSettings;
import io.activej.reactor.nio.NioReactor;
import org.jetbrains.annotations.Nullable;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Objects;

import static io.activej.common.Checks.checkArgument;
import static io.activej.common.Checks.checkState;
import static io.activej.reactor.Reactive.checkInReactorThread;

/**
 * The framed-TCP binding of {@link JsonRpcTransport} (research D2–D5): a duplex, message-oriented
 * channel carrying one complete JSON-RPC document per <b>LF-terminated line</b>, over one
 * {@link ITcpSocket}. The same class serves the accepted server side ({@link #of(Reactor,
 * ITcpSocket)}) and the dialled client side ({@link #connect(NioReactor, InetSocketAddress)}) —
 * which is what makes the reverse-direction conformance suite a replay rather than a second
 * implementation.
 *
 * <h2>The framing rule (FR-010/FR-011/FR-013)</h2>
 * One message is one complete UTF-8 JSON-RPC document terminated by exactly one LF byte
 * ({@code 0x0A}). There is no preamble, no handshake and no negotiation: the first bytes on the wire
 * are the first document. A conforming JSON text never contains a raw LF (RFC 8259 §7 escapes
 * control characters inside strings) and {@code JsonRpcEncoder} never emits one, so an inbound LF is
 * always a boundary.
 * <p>
 * <b>Nothing is trimmed</b> (research D10): a CRLF-terminated line keeps its carriage return, which
 * the envelope decoder treats as insignificant trailing whitespace. CRLF interoperability therefore
 * costs this transport zero code.
 *
 * <h2>The inbound loop (obligations 1–3)</h2>
 * Inbound framing is <b>composed, never written</b>: {@code BinaryChannelSupplier.of(
 * ChannelSuppliers.ofSocket(socket)).decodeStream(new OfByteTerminated((byte) '\n', maxMessageSize))}.
 * The loop is serial — exactly one {@code get()} outstanding, the next issued only after the previous
 * document was delivered — so there is no read-ahead of documents and CSP's pull discipline is the
 * backpressure (FR-022).
 * <p>
 * The decoder yields the whole line with the terminator stripped, and that buffer is <b>copied to a
 * {@code byte[]} and recycled before</b> the listener is called ({@code ByteBuf.asArray()} does both
 * in one step, FR-024). Bounding happens <i>during</i> accumulation: {@code OfByteTerminated} throws
 * {@link MalformedDataException} the moment {@code maxMessageSize} bytes pass without a terminator,
 * so no buffer of the attempted size is ever allocated (obligation 2, FR-016).
 *
 * <h2>The end-of-stream taxonomy (FR-019)</h2>
 * Inherited from {@code BinaryChannelSupplier} rather than re-derived: end-of-stream <b>between</b>
 * messages surfaces as a {@code null} document and becomes {@code onClosed(null)}; end-of-stream
 * <b>mid</b>-message propagates {@link TruncatedDataException} and closes with that cause, the
 * partial accumulation recycled by the supplier's own cleanup. Read end-of-stream is the close of the
 * medium — a peer that half-closes its output is answered with a full close.
 *
 * <h2>The outbound path — no queue (FR-023, research D4/R4)</h2>
 * {@code send} allocates one array of {@code document.length + 1}, appends the terminator and hands
 * it to {@code socket.write} in a single call. There is deliberately <b>no</b> {@code writeTail}
 * promise chain: unlike {@code IWebSocket.writeMessage}, {@code TcpSocket.write} permits concurrency
 * — it coalesces into its own buffer and completes each caller when the batch carrying its bytes has
 * flushed, so per-direction order is append order and obligation 4 (<i>written</i>, never
 * <i>answered</i>) holds with no transport-level state at all.
 *
 * <h2>Refusals</h2>
 * A <b>bare LF</b> decodes to a zero-length document, which obligation 3 forbids delivering; there is
 * no honest resynchronisation point for a framing violation, so the connection closes with a
 * fixed-string cause carrying no peer content (FR-017/FR-097). Outbound,
 * {@code send(new byte[0])} fails immediately with {@link IllegalArgumentException} rather than emit
 * a bare LF (FR-018). JSON-level faults are <i>not</i> framing violations: they are answered by the
 * envelope layer as {@code -32700}/{@code -32600} documents and the connection stays up.
 *
 * <h2>Closing (FR-025)</h2>
 * {@link #closeEx(Exception)} is idempotent: it latches, closes the framing stream and the owned
 * socket, and fires {@code onClosed} <b>exactly once</b> — local, remote or failed alike. A close that
 * happens <i>before</i> {@link #setListener(Listener)} arms the latch and is delivered to whichever
 * listener shows up afterwards (feature 015's {@code signalClose} idiom): arming at close rather than
 * at delivery would swallow the signal, and a session constructed over an already-dead connection
 * would wait forever. After close, {@code send} fails immediately with the close cause.
 *
 * <h2>Threading</h2>
 * Reactive: every public method opens with {@code checkInReactorThread(this)}. The transport owns
 * exactly its socket (FR-027) — closing it closes that socket and nothing else.
 */
public final class JsonRpcTcpTransport extends AbstractReactive implements JsonRpcTransport {
	private static final boolean CHECKS = Checks.isEnabled(JsonRpcTcpTransport.class);

	/** The one framing byte of this wire contract. */
	private static final byte LF = (byte) '\n';

	/**
	 * The close cause of a bare-LF line (FR-017). A fixed string: nothing derived from peer content
	 * ever reaches an output of this module (FR-097).
	 */
	static final String EMPTY_LINE = "empty line: a zero-length document is not a legal message";

	private final ITcpSocket socket;
	private int maxMessageSize = JsonRpcLimits.MAX_BODY_SIZE.toInt();

	private @Nullable Listener listener;
	private @Nullable BinaryChannelSupplier binaryInput;
	private @Nullable ChannelSupplier<ByteBuf> documents;

	/** The close latch triple: {@code closed} guards the medium, {@code closeSignalled} the listener. */
	private boolean closed;
	private boolean closeSignalled;
	private @Nullable Exception closeException;

	private JsonRpcTcpTransport(Reactor reactor, ITcpSocket socket) {
		super(reactor);
		this.socket = socket;
	}

	/**
	 * Wraps an already-established socket — the server side, handed over from
	 * {@code JsonRpcTcpServer.serve}. The socket is owned: closing the transport closes it.
	 *
	 * @throws NullPointerException if either argument is {@code null}
	 */
	public static JsonRpcTcpTransport of(Reactor reactor, ITcpSocket socket) {
		return builder(reactor, socket).build();
	}

	/**
	 * The configurable form of {@link #of(Reactor, ITcpSocket)} — the transport tier of the two-tier
	 * size bound is the only option (contract §2).
	 *
	 * @throws NullPointerException if either argument is {@code null}
	 */
	public static Builder builder(Reactor reactor, ITcpSocket socket) {
		return new JsonRpcTcpTransport(
			Objects.requireNonNull(reactor, "reactor"),
			Objects.requireNonNull(socket, "socket")).new Builder();
	}

	/**
	 * Connects to {@code address} and wraps the resulting socket — the client side. On failure
	 * (unreachable, refused, timed out) the returned promise fails with the cause and <b>nothing</b> is
	 * created or registered anywhere (FR-061).
	 */
	public static Promise<JsonRpcTcpTransport> connect(NioReactor reactor, InetSocketAddress address) {
		return connect(reactor, address, null, null);
	}

	/**
	 * The full connect form, mirroring {@code TcpSocket.connect}'s own overloads: an optional connect
	 * timeout and optional {@link SocketSettings}. The transport tier keeps its default; use
	 * {@link #connect(NioReactor, InetSocketAddress, Duration, SocketSettings, MemSize)} to raise it.
	 */
	public static Promise<JsonRpcTcpTransport> connect(
		NioReactor reactor, InetSocketAddress address, @Nullable Duration timeout,
		@Nullable SocketSettings socketSettings
	) {
		return connect(reactor, address, timeout, socketSettings, JsonRpcLimits.MAX_BODY_SIZE);
	}

	/**
	 * The full connect form plus the transport tier — the client-side equivalent of
	 * {@link Builder#withMaxMessageSize(MemSize)}, needed because the connected socket does not exist
	 * until the promise resolves and so cannot be handed to a builder beforehand.
	 */
	public static Promise<JsonRpcTcpTransport> connect(
		NioReactor reactor, InetSocketAddress address, @Nullable Duration timeout,
		@Nullable SocketSettings socketSettings, MemSize maxMessageSize
	) {
		Objects.requireNonNull(reactor, "reactor");
		Objects.requireNonNull(address, "address");
		Objects.requireNonNull(maxMessageSize, "maxMessageSize");
		return TcpSocket.connect(reactor, address, timeout, socketSettings)
			.map(socket -> builder(reactor, socket)
				.withMaxMessageSize(maxMessageSize)
				.build());
	}

	/** The transport tier of the two-tier size bound is the whole of this transport's configuration. */
	public final class Builder extends AbstractBuilder<Builder, JsonRpcTcpTransport> {
		private Builder() {}

		/**
		 * The largest message this transport will accumulate before refusing, applied <b>during</b>
		 * accumulation by the framing decoder (FR-016). Defaults to {@link JsonRpcLimits#MAX_BODY_SIZE}.
		 * <p>
		 * ⚠ With the two tiers equal — which is the default — the transport tier wins and the envelope's
		 * {@code -32001 Request too large} answer is unreachable. A deployment that wants that answer
		 * sets this strictly above {@code JsonRpcLimits.MAX_BODY_SIZE}.
		 *
		 * @throws IllegalArgumentException under {@code CHECKS} if the tier is not positive — a
		 *                                  non-positive bound would make no message framable at all
		 */
		public Builder withMaxMessageSize(MemSize maxMessageSize) {
			checkNotBuilt(this);
			Objects.requireNonNull(maxMessageSize, "maxMessageSize");
			if (CHECKS) checkArgument(maxMessageSize.toInt() > 0, "maxMessageSize must be positive");
			JsonRpcTcpTransport.this.maxMessageSize = maxMessageSize.toInt();
			return this;
		}

		@Override
		protected JsonRpcTcpTransport doBuild() {
			return JsonRpcTcpTransport.this;
		}
	}

	/**
	 * Sends one complete document as one LF-terminated line (FR-011): the document bytes are the
	 * caller's, the terminator is this transport's, and both go to the socket in a single
	 * {@code write}. The returned promise completes when the bytes have been <b>written</b> — never
	 * when an answer arrives (obligation 4) — and fails with the close cause once the transport is
	 * closed. A zero-length array is refused immediately (FR-018) as a <b>failed promise</b> carrying
	 * {@link IllegalArgumentException} — checked before the listener-set precondition below, so an
	 * empty array sent before {@link #setListener(Listener)} fails that way, not this one;
	 * {@code document} is not retained after the promise completes.
	 *
	 * @throws IllegalStateException if {@link #setListener(Listener)} has not been called yet and
	 *                               {@code document} is non-empty
	 */
	@Override
	public Promise<Void> send(byte[] document) {
		checkInReactorThread(this);
		if (closed) return Promise.ofException(closedException());
		Objects.requireNonNull(document, "document");
		if (document.length == 0) {
			return Promise.ofException(new IllegalArgumentException("Document must not be empty (FR-018)"));
		}
		checkState(listener != null, "setListener must be called before the first send");

		// one array, one wrap, one write: TcpSocket coalesces concurrent writes itself (research D4/R4)
		ByteBuf buf = ByteBuf.wrapForWriting(new byte[document.length + 1]);
		buf.put(document);
		buf.put(LF);
		return socket.write(buf)
			// FR-026: a write failure is the close of the medium; the caller still sees the cause
			.whenException(this::closeEx);
	}

	/**
	 * Registers the listener documents are delivered to and starts the serial read loop. Called once,
	 * before the first {@link #send(byte[])}; a second call is refused (FR-025). When the transport is
	 * already closed — a local {@code closeEx}, or a socket that died during construction — the close
	 * is signalled immediately, exactly once.
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
		startReading();
	}

	/**
	 * Closes the transport: idempotent, fires {@link Listener#onClosed} exactly once (obligation 6),
	 * fails every subsequent {@link #send(byte[])} with {@code e}, and closes the framing stream — which
	 * recycles whatever was accumulated — and the owned socket. Nothing else is closed (FR-027).
	 */
	@Override
	public void closeEx(Exception e) {
		checkInReactorThread(this);
		Objects.requireNonNull(e, "e");
		if (closed) return;
		closed = true;
		closeException = e;
		closeMedium(e);
		signalClose(e);
	}

	/**
	 * Whether this transport has latched closed. The zombie guard of {@code JsonRpcTcpServer.serve}
	 * reads it: a connection that died while its session was being constructed has already spent its
	 * exactly-once {@code onClosed}, so registering it would leave an entry nothing will ever remove.
	 */
	public boolean isClosed() {
		checkInReactorThread(this);
		return closed;
	}

	@Override
	public String toString() {
		return "JsonRpcTcpTransport{" + (closed ? "closed" : "open") + '}';
	}

	// ---------------------------------------------------------------------------------------------
	// The inbound loop.
	// ---------------------------------------------------------------------------------------------

	/**
	 * Composes the framing (research D2/D3): the socket as a byte channel, a {@code ByteBufs}
	 * accumulator on top of it, and the existing LF-terminated decoder over that. Zero new framing code
	 * is written here on purpose — that composition is what earns this module its place in {@code extra/}.
	 */
	private void startReading() {
		BinaryChannelSupplier binaryInput = BinaryChannelSupplier.of(ChannelSuppliers.ofSocket(socket));
		this.binaryInput = binaryInput;
		this.documents = binaryInput.decodeStream(new OfByteTerminated(LF, maxMessageSize));
		doRead();
	}

	/**
	 * One {@code get()} at a time (FR-022): the next decode is issued only after the previous document
	 * has been delivered, so nothing is pre-read at the message level and the listener's own work is the
	 * backpressure.
	 */
	private void doRead() {
		if (closed) return;
		ChannelSupplier<ByteBuf> documents = this.documents;
		if (documents == null) return;
		documents.get()
			.whenComplete((buf, e) -> {
				if (e != null) {
					// an I/O failure, a MalformedDataException from the size bound, or a
					// TruncatedDataException from a stream that ended mid-message (FR-019/FR-026)
					closeEx(e);
					return;
				}
				if (buf == null) {
					// end-of-stream between messages: the peer's clean close (research R3)
					closeCleanly();
					return;
				}
				if (buf.readRemaining() == 0) {
					// a bare LF. Obligation 3 forbids delivering it and there is no resynchronisation
					// point for a framing violation, so the connection closes (FR-017).
					buf.recycle();
					closeEx(new MalformedDataException(EMPTY_LINE));
					return;
				}
				// copy, recycle, then deliver — in that order (FR-024): asArray() does the first two
				byte[] document = buf.asArray();
				Listener listener = this.listener;
				if (closed || listener == null) return;
				listener.onDocument(document);
				doRead();
			});
	}

	// ---------------------------------------------------------------------------------------------
	// Closing.
	// ---------------------------------------------------------------------------------------------

	/** The peer's clean close: no cause, and the medium is closed on this side too (contract §4). */
	private void closeCleanly() {
		if (closed) return;
		closed = true;
		closeException = null;
		closeMedium(new AsyncCloseException("the peer closed the connection"));
		signalClose(null);
	}

	/**
	 * Closes the framing stream first — its cleanup recycles whatever was accumulated mid-message — and
	 * then the socket. Both are idempotent, and the socket is closed even when no listener was ever set
	 * and therefore no stream exists.
	 */
	private void closeMedium(Exception e) {
		BinaryChannelSupplier binaryInput = this.binaryInput;
		if (binaryInput != null) binaryInput.closeEx(e);
		socket.closeEx(e);
	}

	/**
	 * Fires {@link Listener#onClosed} exactly once — and only once there is a listener to deliver to. A
	 * close that happened <b>before</b> {@link #setListener(Listener)} must still reach the listener
	 * registered afterwards, so the latch is armed at delivery rather than at close (feature 015's
	 * idiom).
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
