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
import io.activej.common.builder.AbstractBuilder;
import io.activej.common.inspector.BaseInspector;
import io.activej.common.recycle.Recyclers;
import io.activej.promise.Promise;
import io.activej.promise.SettablePromise;
import io.activej.quic.codec.*;
import io.activej.quic.connection.QuicConnection;
import io.activej.quic.connection.QuicConnection.Role;
import io.activej.quic.connection.QuicConnectionSettings;
import io.activej.quic.connection.QuicFrameHandler;
import io.activej.quic.connection.QuicTransportErrors;
import io.activej.quic.connection.QuicTransportException;
import io.activej.quic.tls.EncryptionLevel;
import io.activej.quic.tls.QuicTransportParameters;
import io.activej.reactor.AbstractReactive;
import io.activej.reactor.Reactor;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import static io.activej.reactor.Reactive.checkInReactorThread;

/**
 * The stream layer of one {@link QuicConnection}: the {@link QuicFrameHandler} that turns
 * {@code STREAM} frames into {@link QuicStream}s and application writes into {@code STREAM} frames
 * (RFC 9000 §2–§4).
 *
 * <h2>How it attaches</h2>
 * A stream manager must hold the connection it serves — an application write originates traffic from
 * a path no frame triggered — so it is registered through the <em>factory</em> form of handler
 * registration, which runs after the connection's fields are assigned:
 * <pre>{@code
 * QuicEndpoint.builder(reactor, socket)
 *     .withFrameHandlerFactory(connection -> QuicStreamManager.builder(reactor, connection)
 *         .withStreamListener(stream -> serve(stream))
 *         .build())
 *     .build();
 * }</pre>
 * The same factory serves a dialled connection, so there is one way in rather than two.
 *
 * <h2>Windows and the RFC 9000 §18.2 naming trap</h2>
 * The two bidirectional parameter names are relative to <em>whoever sent the parameter</em>, not to
 * "me" and "peer". For this endpoint, talking to a peer:
 * <table>
 *   <caption>Initial flow-control windows per stream type</caption>
 *   <tr><th>Stream</th><th>Our sending half</th><th>Our receiving half</th></tr>
 *   <tr><td>ours, bidirectional</td>
 *       <td>peer's {@code initial_max_stream_data_bidi_remote}</td>
 *       <td>our {@code initial_max_stream_data_bidi_local}</td></tr>
 *   <tr><td>peer's, bidirectional</td>
 *       <td>peer's {@code initial_max_stream_data_bidi_local}</td>
 *       <td>our {@code initial_max_stream_data_bidi_remote}</td></tr>
 *   <tr><td>ours, unidirectional</td>
 *       <td>peer's {@code initial_max_stream_data_uni}</td><td>—</td></tr>
 *   <tr><td>peer's, unidirectional</td>
 *       <td>—</td><td>our {@code initial_max_stream_data_uni}</td></tr>
 * </table>
 * Connection-level accounting has no such asymmetry: our send limit is the peer's
 * {@code initial_max_data}, our receive limit is our own.
 *
 * <h2>Flow control (RFC 9000 §4)</h2>
 * Four rules, and each one is what keeps another from deadlocking:
 * <ul>
 *   <li><b>Send.</b> A byte is never given an offset at or beyond either limit; a write with nowhere
 *       to go is <em>withheld</em>, and the peer is told which limit is holding it — once per limit
 *       value, connection-wide for {@code DATA_BLOCKED} (FR-022, FR-027).</li>
 *   <li><b>Receive.</b> Both limits are checked <em>before</em> the frame is handed to a receiving
 *       part, because that part delivers what it accepts. Either overrun is
 *       {@code FLOW_CONTROL_ERROR} (FR-023).</li>
 *   <li><b>Grant.</b> Credit is granted off the <em>reader's</em> consumption, at the half-window
 *       threshold, to {@code consumed + window} — never because the peer announced it was blocked
 *       (FR-025).</li>
 *   <li><b>Resume.</b> Every event that can unblock a writer — {@code MAX_STREAM_DATA},
 *       {@code MAX_DATA}, an acknowledgement freeing outstanding budget — pumps the writers it might
 *       have released. A limit raised with nobody retried is a stall, not a wrong answer, which is
 *       the failure mode this layer is easiest to get wrong in.</li>
 * </ul>
 *
 * <h2>Buffer ownership (DI-1)</h2>
 * <ul>
 *   <li>{@link #onFrame} — the frame is <b>borrowed</b>; a payload that must outlive the call is
 *       retained with {@code slice()}, never copied (FR-014).</li>
 *   <li>{@link #onFrameAcknowledged} / {@link #onFrameLost} — ownership <b>passes in</b>; every path
 *       either recycles the frame or re-enqueues it, handing ownership back.</li>
 * </ul>
 *
 * <h2>Batching (research R-03)</h2>
 * {@code QuicConnection.enqueueFrame} queues but does not send; {@code requestSend()} sends. A batch
 * of stream frames is therefore enqueued and then flushed with a <b>single</b> {@code requestSend()},
 * so a burst shares a datagram rather than paying a flush per frame.
 * <p>
 * Reactive, and confined to its connection's reactor: every public method opens with
 * {@code checkInReactorThread(this)} (FR-040, WI-1).
 *
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9000#section-2">RFC 9000 §2 — Streams</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9000#section-4">RFC 9000 §4 — Flow Control</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9000#section-18.2">RFC 9000 §18.2 — Transport Parameter Definitions</a>
 */
public final class QuicStreamManager extends AbstractReactive implements QuicFrameHandler {
	private static final Logger logger = LoggerFactory.getLogger(QuicStreamManager.class);

	/**
	 * A {@code STREAM} frame's header at its widest (RFC 9000 §19.8): the type byte plus three
	 * variable-length integers — stream id, offset and length — each of which can occupy eight bytes.
	 */
	static final int MAX_STREAM_FRAME_HEADER = 1 + 8 + 8 + 8;

	/**
	 * A 1-RTT packet's cost beyond its frames, at its widest (RFC 9000 §17.3): the first byte, a
	 * 20-byte destination connection ID, a four-byte packet number and the AEAD tag.
	 * <p>
	 * Deliberately the <em>worst</em> case rather than this connection's actual overhead: a frame too
	 * large for the remaining datagram allowance is never polled from the send queue, and since it sits
	 * at the head it would block every frame behind it. Under-filling a datagram costs a few bytes;
	 * over-filling one costs the connection.
	 */
	static final int MAX_SHORT_HEADER_PACKET_OVERHEAD = 1 + 20 + 4 + 16;

	/**
	 * The {@code streamId} an {@link Inspector} is given for a <em>connection-level</em> event. Negative,
	 * and therefore not a legal RFC 9000 §2.1 identifier, so no stream can ever collide with it.
	 */
	private static final long CONNECTION_LEVEL = -1;

	/**
	 * Which of the three limits of RFC 9000 §4 held a writer or an open back, as reported to
	 * {@link Inspector#onFlowControlBlocked} (FR-027, FR-029).
	 *
	 * @see <a href="https://www.rfc-editor.org/rfc/rfc9000#section-4">RFC 9000 §4 — Flow Control</a>
	 */
	public enum BlockedBy {
		/** The connection-wide data limit of RFC 9000 §4.1 — announced with {@code DATA_BLOCKED} (§19.12). */
		CONNECTION_DATA,
		/** One stream's data limit of RFC 9000 §4.1 — announced with {@code STREAM_DATA_BLOCKED} (§19.13). */
		STREAM_DATA,
		/** The concurrency limit of RFC 9000 §4.6 — announced with {@code STREAMS_BLOCKED} (§19.14). */
		STREAM_COUNT
	}

	/**
	 * The optional statistics hook of FR-044, following the {@code QuicConnection.Inspector} precedent:
	 * an interface declared by the component, <b>absent by default</b>, that a later module implements to
	 * publish JMX statistics without this module depending on {@code boot-jmx}. Every counter of FR-043
	 * stays readable with no inspector attached, so attaching one is never required to observe the layer.
	 * <p>
	 * It carries <b>no {@link ByteBuf} and no payload byte</b> by construction — every parameter is a
	 * stream identifier, a flag, a 62-bit application error code, a limit or an enum constant (FR-044,
	 * SI-6). A stream identifier and an application error code are the peer's own routing values, not its
	 * data; that boundary is what makes this seam safe to publish.
	 * <p>
	 * <b>Threading</b>: every callback runs on the connection's reactor thread, inside the operation that
	 * produced the event. An implementation that blocks blocks the reactor, and one that throws fails the
	 * operation — accumulate, never act.
	 *
	 * @see <a href="https://www.rfc-editor.org/rfc/rfc9000#section-2">RFC 9000 §2 — Streams</a>
	 * @see <a href="https://www.rfc-editor.org/rfc/rfc9000#section-4">RFC 9000 §4 — Flow Control</a>
	 */
	public interface Inspector extends BaseInspector<Inspector> {
		/**
		 * A stream came into existence on this endpoint (RFC 9000 §2.1) — either allocated locally, or
		 * created because the peer named it, including every lower-numbered stream a single identifier
		 * implicitly opens (FR-003). Fires once per stream, before any byte of it is readable.
		 */
		void onStreamOpened(long streamId, boolean locallyInitiated, boolean bidirectional);

		/**
		 * Every half this endpoint owns is terminal and the application has drained what it could, so the
		 * stream's state has been released and its accounting returned to the connection (RFC 9000 §3,
		 * FR-006). Fires once per stream.
		 */
		void onStreamClosed(long streamId);

		/**
		 * A stream's sending half was aborted with an application error code (RFC 9000 §19.4) — by this
		 * endpoint ({@code byPeer == false}, including an abort a peer's {@code STOP_SENDING} forced), or
		 * by the peer ({@code byPeer == true}). Fires once per stream per direction, never per
		 * retransmission.
		 */
		void onStreamReset(long streamId, boolean byPeer, long applicationErrorCode);

		/**
		 * This endpoint had something to send and a limit would not let it, and it said so on the wire
		 * (RFC 9000 §19.12–§19.14, FR-027, FR-029). Fires once per limit <em>value</em>, matching the
		 * de-duplication of the announcement itself, so a persistently blocked sender does not flood this
		 * seam any more than it floods the peer.
		 *
		 * @param streamId the blocked stream, or <b>negative</b> for a connection-level limit —
		 *                 {@link BlockedBy#CONNECTION_DATA} and {@link BlockedBy#STREAM_COUNT} are always
		 *                 connection-level
		 */
		void onFlowControlBlocked(long streamId, BlockedBy blockedBy);

		/**
		 * This endpoint granted the peer more credit and told it so — {@code MAX_STREAM_DATA},
		 * {@code MAX_DATA} or {@code MAX_STREAMS} (RFC 9000 §19.9–§19.11, FR-025, FR-028).
		 *
		 * @param streamId the stream whose data limit rose, or <b>negative</b> for a connection-level
		 *                 grant — both {@code MAX_DATA} and {@code MAX_STREAMS} are connection-level
		 * @param newLimit the new absolute limit: a byte offset for the two data grants, a stream
		 *                 <em>count</em> for {@code MAX_STREAMS}
		 */
		void onLimitGranted(long streamId, long newLimit);
	}

	private final QuicConnection connection;
	private final QuicConnectionSettings settings;
	private final Role role;
	private final int maxFrameDataSize;
	private final long maxOutstandingStreamBytes;

	private @Nullable Consumer<QuicStream> streamListener;
	private @Nullable Runnable earlyDataRejectionListener;
	/** Absent by default (FR-044); every FR-043 counter is readable without one. */
	private @Nullable Inspector inspector;

	private final Map<Long, QuicStream> streams = new HashMap<>();

	/** Opens issued before the handshake completed (FR-042), bounded by {@code maxPendingStreamOpens}. */
	private final Deque<PendingOpen> pendingOpens = new ArrayDeque<>();

	private final SendSink sendSink = new SendSink();
	private final ReceiveListener receiveListener = new ReceiveListener();

	// Sized from the peer's transport parameters, so none of them exists before the handshake.
	private @Nullable ConnectionFlowController connectionFlowControl;
	private @Nullable QuicTransportParameters peerParameters;
	private @Nullable StreamCounter localBidi;
	private @Nullable StreamCounter localUni;
	private @Nullable StreamCounter peerBidi;
	private @Nullable StreamCounter peerUni;

	private long outstandingStreamBytes;
	private boolean closed;

	/**
	 * Every sending part with {@link SendPart#hasPendingWrite()} true, kept up to date by
	 * {@link SendSink#onPendingWriteChanged} so {@link #retryBlockedWriters} can retry exactly the parts
	 * worth retrying instead of walking every stream on the connection. Insertion order, matching the
	 * "whichever comes first" fairness {@link #retryBlockedWriters} already documents.
	 */
	private final Set<SendPart> pendingWriters = new LinkedHashSet<>();

	/**
	 * Depth of the current batch (research R-03, T035). While it is positive, {@link SendSink#requestSend}
	 * only records that a flush is due; the outermost {@code endBatch} performs it, so a frame handler
	 * callback that touches several streams shares one datagram instead of paying a flush per stream.
	 * <p>
	 * A depth rather than a flag: a resumed write's continuation writes again, re-entering through
	 * {@link SendPart#pump} while the outer batch is still open.
	 */
	private int batchDepth;
	private boolean sendRequested;

	/**
	 * The connection-level limit last announced with a {@code DATA_BLOCKED} frame, or {@code -1} if none
	 * ever was (FR-027).
	 * <p>
	 * Connection-wide, unlike {@code SendPart}'s per-stream counterpart, because the limit is: every
	 * sending part can reach the same one, and announcing it per part is exactly the flood FR-027
	 * forbids.
	 */
	private long dataBlockedAnnouncedAt = -1;

	/**
	 * The stream <em>count</em> limit last announced with a {@code STREAMS_BLOCKED} frame, per direction,
	 * or {@code -1} if none ever was (FR-028).
	 * <p>
	 * Two fields rather than a field on {@link StreamCounter}: the counter is a pure arithmetic value with
	 * no notion of a wire, and announcement bookkeeping belongs to the layer that owns the wire — the same
	 * division that keeps {@code dataBlockedAnnouncedAt} here rather than on
	 * {@link ConnectionFlowController}. {@code -1} rather than {@code 0} because a peer can genuinely
	 * grant zero streams, and being blocked at zero is precisely the case that most needs announcing.
	 */
	private long bidiStreamsBlockedAnnouncedAt = -1;
	private long uniStreamsBlockedAnnouncedAt = -1;

	// FR-043 counters, readable with no inspector attached.
	private long streamsOpenedLocally;
	private long streamsAcceptedFromPeer;
	private long streamsResetLocally;
	private long streamsResetByPeer;
	private long bytesDelivered;
	private long bytesSent;
	private long timesBlockedByConnectionLimit;
	private long timesBlockedByStreamLimit;
	private long timesBlockedByStreamCountLimit;

	private record PendingOpen(StreamDirection direction, SettablePromise<QuicStream> promise) {}

	private QuicStreamManager(Reactor reactor, QuicConnection connection) {
		super(reactor);
		this.connection = connection;
		this.settings = connection.settings();
		this.role = connection.role();
		this.maxOutstandingStreamBytes = settings.maxOutstandingStreamBytes();
		this.maxFrameDataSize = Math.max(1,
			settings.maxDatagramSize() - MAX_SHORT_HEADER_PACKET_OVERHEAD - MAX_STREAM_FRAME_HEADER);
	}

	/**
	 * @param connection the connection this manager is the stream layer of. Its
	 *                   {@link QuicConnection#settings()} supply every limit this endpoint advertises
	 *                   and every local bound; the peer's half of the picture arrives with the
	 *                   handshake
	 */
	public static Builder builder(Reactor reactor, QuicConnection connection) {
		return new QuicStreamManager(reactor, connection).new Builder();
	}

	/**
	 * The one-shot builder every component in the platform is constructed through (DI-4). Both options
	 * are optional: a manager with neither still receives and sends streams, and still keeps every
	 * counter of FR-043.
	 */
	public final class Builder extends AbstractBuilder<Builder, QuicStreamManager> {
		private Builder() {}

		/**
		 * Invoked exactly once per <b>peer-opened</b> stream, on the reactor thread, before any byte of
		 * it is readable (FR-004). A listener that throws closes the connection with
		 * {@code INTERNAL_ERROR} — a handler bug, matching the connection layer's existing rule for a
		 * frame handler that throws.
		 */
		public Builder withStreamListener(Consumer<QuicStream> listener) {
			checkNotBuilt(this);
			QuicStreamManager.this.streamListener = listener;
			return this;
		}

		/**
		 * Called once, on the reactor thread, when the peer refused this connection's early data
		 * (spec FR-055) — see {@link QuicFrameHandler#onEarlyDataRejected}.
		 * <p>
		 * A plain {@link Runnable}, because this manager passes on <b>no</b> decision of its own with it:
		 * it discards nothing, and the listener is expected to walk {@link #earlyDataStreams()} and call
		 * {@link #discardStream} for the ones it will re-create. By the time it runs, the connection's
		 * 0-RTT keys are already gone, so anything the listener opens or writes goes out at 1-RTT.
		 */
		public Builder withEarlyDataRejectionListener(Runnable listener) {
			checkNotBuilt(this);
			QuicStreamManager.this.earlyDataRejectionListener = listener;
			return this;
		}

		/**
		 * Attaches the optional statistics hook of FR-044, mirroring
		 * {@code QuicConnection.Builder.withInspector}. Absent by default, and never required: every
		 * counter of FR-043 is readable without one.
		 * <p>
		 * The inspector is <b>never</b> handed a {@link ByteBuf} or a payload byte — see {@link Inspector}.
		 */
		public Builder withInspector(Inspector inspector) {
			checkNotBuilt(this);
			QuicStreamManager.this.inspector = inspector;
			return this;
		}

		@Override
		protected QuicStreamManager doBuild() {
			return QuicStreamManager.this;
		}
	}

	// ---------------------------------------------------------------- opening

	/**
	 * Opens a locally-initiated bidirectional stream (RFC 9000 §2.1).
	 * <p>
	 * Before establishment the promise stays pending until {@code onEstablished} (FR-042): a stream id
	 * cannot be allocated before the peer has said how many streams it will accept. Once the connection
	 * has closed, it fails with the connection's exception, unwrapped.
	 */
	public Promise<QuicStream> openBidirectional() {
		checkInReactorThread(this);
		return open(StreamDirection.BIDIRECTIONAL);
	}

	/** Opens a locally-initiated unidirectional stream (RFC 9000 §2.1). See {@link #openBidirectional()}. */
	public Promise<QuicStream> openUnidirectional() {
		checkInReactorThread(this);
		return open(StreamDirection.UNIDIRECTIONAL);
	}

	private Promise<QuicStream> open(StreamDirection direction) {
		if (closed) {
			return Promise.ofException(connectionException());
		}
		// A batch, because being unable to open announces STREAMS_BLOCKED, and an application that opens
		// several streams in a row deserves one datagram rather than one per attempt (research R-03).
		beginBatch();
		try {
			if (peerParameters == null && !initializeFromEarlyParameters()) {
				// FR-042: withheld, not failed. The handshake is what supplies the stream limits, and until
				// it has there is no limit value a STREAMS_BLOCKED could truthfully carry.
				return withholdOpen(direction);
			}
			return allocateLocalStream(direction);
		} finally {
			endBatch();
		}
	}

	/**
	 * Allocates the next ordinal of this type, or — if the peer has granted no more (RFC 9000 §4.6) —
	 * withholds the request and tells the peer with {@code STREAMS_BLOCKED} (FR-029).
	 * <p>
	 * Exceeding the peer's count is forbidden, and failing the caller instead would make a limit the peer
	 * is free to raise a moment later into an application-visible error. Withholding is what turns it
	 * back into what it is: backpressure.
	 */
	private Promise<QuicStream> allocateLocalStream(StreamDirection direction) {
		StreamCounter counter = counterOf(direction);
		assert counter != null;
		if (!counter.canOpen()) {
			announceStreamsBlocked(direction, counter);
			return withholdOpen(direction);
		}
		return Promise.of(allocateNow(direction, counter));
	}

	/** Allocates and registers the stream; the counter has already been checked. */
	private QuicStream allocateNow(StreamDirection direction, StreamCounter counter) {
		long streamId = StreamIds.of(counter.allocate(), role == Role.CLIENT,
			direction == StreamDirection.BIDIRECTIONAL);
		QuicStream stream = createStream(streamId, true);
		streamsOpenedLocally++;
		return stream;
	}

	/**
	 * Parks an open in the withheld-open deque, or fails it if {@code maxPendingStreamOpens} requests are
	 * already parked (FR-029, SI-3).
	 * <p>
	 * One deque for both the pre-establishment case and the at-the-limit one, and one bound over both: to
	 * the memory a stalled peer can make this endpoint hold, the reason the open cannot proceed makes no
	 * difference.
	 */
	private Promise<QuicStream> withholdOpen(StreamDirection direction) {
		if (pendingOpens.size() >= settings.maxPendingStreamOpens()) {
			return Promise.ofException(
				new QuicStreamLimitException(direction, settings.maxPendingStreamOpens()));
		}
		SettablePromise<QuicStream> promise = new SettablePromise<>();
		pendingOpens.addLast(new PendingOpen(direction, promise));
		return promise;
	}

	/**
	 * Tells the peer that this endpoint has a stream to open and no credit for it (RFC 9000 §19.14),
	 * <b>once per limit value per direction</b> (FR-028).
	 * <p>
	 * The same de-duplication as {@code DATA_BLOCKED}'s, one level up and per direction: a limit only ever
	 * rises, so "different from the last announced" is the whole of the rule, and a hundred withheld opens
	 * at one limit are one announcement — which is also why
	 * {@link #timesBlockedByStreamCountLimit()} counts episodes rather than requests.
	 *
	 * @return whether a frame was actually queued
	 */
	private boolean announceStreamsBlocked(StreamDirection direction, StreamCounter counter) {
		long limit = counter.limit();
		if (direction == StreamDirection.BIDIRECTIONAL) {
			if (bidiStreamsBlockedAnnouncedAt == limit) return false;
			bidiStreamsBlockedAnnouncedAt = limit;
		} else {
			if (uniStreamsBlockedAnnouncedAt == limit) return false;
			uniStreamsBlockedAnnouncedAt = limit;
		}
		timesBlockedByStreamCountLimit++;
		if (inspector != null) {
			// Connection-level: RFC 9000 §4.6 limits a *type*, not a stream, so there is no id to name.
			inspector.onFlowControlBlocked(CONNECTION_LEVEL, BlockedBy.STREAM_COUNT);
		}
		return enqueueControlFrame(new StreamsBlockedFrame(limit, limitTypeOf(direction)));
	}

	/**
	 * Resumes withheld opens that can now proceed — every direction on establishment, one direction on a
	 * {@code MAX_STREAMS} that actually raised its limit (FR-029, FR-042).
	 *
	 * @param only the direction whose credit changed, or {@code null} for "whatever can be served"
	 */
	private void drainPendingOpens(@Nullable StreamDirection only) {
		if (pendingOpens.isEmpty()) return;
		beginBatch();
		try {
			// Two passes, and the split is load-bearing: completing an open runs the application's
			// continuation synchronously, which routinely opens another stream and so mutates the very deque
			// the first pass is walking. Deciding first and completing afterwards is what keeps that from
			// being a ConcurrentModificationException — or worse, a lost open.
			List<PendingOpen> served = null;
			List<QuicStream> allocated = null;
			for (Iterator<PendingOpen> it = pendingOpens.iterator(); it.hasNext(); ) {
				PendingOpen pending = it.next();
				if (only != null && pending.direction() != only) continue;
				StreamCounter counter = counterOf(pending.direction());
				if (counter == null) break;             // not established yet: nothing can be served at all
				if (!counter.canOpen()) {
					if (only != null) break;            // this direction is spent; the queue keeps its order
					continue;                           // the other direction may still have credit
				}
				it.remove();
				if (served == null) {
					served = new ArrayList<>();
					allocated = new ArrayList<>();
				}
				served.add(pending);
				// Allocated now rather than in the second pass, so that this loop's own accounting is what
				// bounds it: the counter is what says when the credit has run out.
				allocated.add(allocateNow(pending.direction(), counter));
			}
			if (served != null) {
				for (int i = 0; i < served.size(); i++) {
					served.get(i).promise().set(allocated.get(i));
				}
			}
			// Whatever is still withheld is still blocked, possibly at a limit the peer has since raised —
			// and a limit it has not been told about is one it has no reason to raise again.
			for (PendingOpen pending : pendingOpens) {
				StreamCounter counter = counterOf(pending.direction());
				if (counter != null && !counter.canOpen()) {
					announceStreamsBlocked(pending.direction(), counter);
				}
			}
		} finally {
			endBatch();
		}
	}

	private @Nullable StreamCounter counterOf(StreamDirection direction) {
		return direction == StreamDirection.BIDIRECTIONAL ? localBidi : localUni;
	}

	private static QuicStreamLimitType limitTypeOf(StreamDirection direction) {
		return direction == StreamDirection.BIDIRECTIONAL
			? QuicStreamLimitType.BIDIRECTIONAL
			: QuicStreamLimitType.UNIDIRECTIONAL;
	}

	// ---------------------------------------------------------------- stream construction

	/**
	 * Builds the halves this endpoint owns for {@code streamId} and registers the stream. The initial
	 * windows follow the RFC 9000 §18.2 table in this class's Javadoc.
	 */
	private QuicStream createStream(long streamId, boolean locallyInitiated) {
		QuicTransportParameters peer = peerParameters;
		assert peer != null && connectionFlowControl != null;
		boolean bidirectional = StreamIds.isBidirectional(streamId);

		SendPart sendPart = null;
		if (StreamIds.canSend(streamId, role)) {
			sendPart = new SendPart(streamId,
				new StreamFlowController(peerSendWindowOf(peer, streamId, locallyInitiated)),
				connectionFlowControl, maxFrameDataSize, sendSink);
		}

		ReceivePart receivePart = null;
		if (StreamIds.canReceive(streamId, role)) {
			receivePart = new ReceivePart(streamId, receiveWindowOf(streamId),
				settings.maxReceiveRangesPerStream(), receiveListener);
		}

		// Latched here, at the one construction site of every stream: whether a byte written now would
		// leave in a 0-RTT packet is a property of the moment the stream came into existence, and it stops
		// being readable from the connection the instant the 1-RTT keys land (spec FR-055).
		QuicStream stream = new QuicStream(reactor, streamId, locallyInitiated,
			connection.isSendingEarlyData(), sendPart, receivePart);
		streams.put(streamId, stream);
		// The single construction site of every stream, local or peer-opened, so the one call here is what
		// makes the inspector's view of "opened" complete (FR-044).
		if (inspector != null) {
			inspector.onStreamOpened(streamId, locallyInitiated, bidirectional);
		}
		return stream;
	}

	/**
	 * Opens {@code streamId} and, per RFC 9000 §2.1, every lower-numbered stream of its type that was
	 * not already open (FR-003), then announces them in ascending order.
	 *
	 * @throws QuicTransportException {@code STREAM_LIMIT_ERROR} if the peer exceeded the count we
	 *                                advertised (RFC 9000 §4.6)
	 */
	private void openPeerStreams(long streamId) throws QuicTransportException {
		long ordinal = StreamIds.ordinal(streamId);
		StreamCounter counter = counterFor(streamId);
		if (!counter.isWithinLimit(ordinal)) {
			throw new QuicTransportException(QuicTransportErrors.STREAM_LIMIT_ERROR,
				"peer opened stream " + streamId + ", ordinal " + ordinal +
				" of a type limited to " + counter.limit());
		}
		boolean bidirectional = StreamIds.isBidirectional(streamId);
		boolean peerIsClient = role == Role.SERVER;
		List<QuicStream> opened = new ArrayList<>();
		for (long o = counter.open(ordinal); o <= ordinal; o++) {
			opened.add(createStream(StreamIds.of(o, peerIsClient, bidirectional), false));
			streamsAcceptedFromPeer++;
		}
		// Announced only once every stream of the run exists, and always before a byte is delivered: the
		// caller's listener may reach for streamOf(...) or attach a reader, and must never see a hole.
		for (QuicStream stream : opened) {
			notifyStreamListener(stream);
		}
	}

	/**
	 * The stream a peer frame names: the open one, or a newly opened run of them if the peer is naming
	 * it for the first time (FR-003). Shared by the five frames that can open a stream — {@code STREAM},
	 * {@code RESET_STREAM}, {@code STOP_SENDING}, {@code MAX_STREAM_DATA} and
	 * {@code STREAM_DATA_BLOCKED} — so that the identifier rules they share are stated once rather than
	 * five times (RFC 9000 §3.1, §3.2). The last two join the list per RFC 9000 §19.10/§19.13, which let
	 * either frame name a stream that has not yet been created.
	 *
	 * @param frameName the frame's name, for the failure message only
	 * @return {@code null} when the stream existed and has already been released — a frame still in
	 * flight for a stream this endpoint has finished with is routine, not an error
	 * @throws QuicTransportException {@code STREAM_STATE_ERROR} if the peer named a locally-initiated
	 *                                stream this endpoint never opened; {@code STREAM_LIMIT_ERROR} if
	 *                                opening it would exceed the count this endpoint advertised
	 */
	private @Nullable QuicStream streamFor(long streamId, String frameName) throws QuicTransportException {
		QuicStream stream = streams.get(streamId);
		if (stream != null) return stream;
		if (isLocallyInitiated(streamId)) {
			if (StreamIds.ordinal(streamId) >= counterFor(streamId).nextOrdinal()) {
				throw new QuicTransportException(QuicTransportErrors.STREAM_STATE_ERROR,
					"peer sent " + frameName + " on stream " + streamId + ", which this endpoint never opened");
			}
			// Opened and already released: the peer is answering a stream we have finished with.
			return null;
		}
		openPeerStreams(streamId);
		return streams.get(streamId);   // null if it was opened and released earlier
	}

	private void notifyStreamListener(QuicStream stream) {
		if (streamListener == null) return;
		// A throwing listener is deliberately not caught: QuicConnection.routeToHandler turns a
		// RuntimeException from onFrame into INTERNAL_ERROR and closes the connection, which is exactly
		// the documented contract. Swallowing it would leave a stream nobody owns.
		streamListener.accept(stream);
	}

	/**
	 * The receive window this endpoint advertises for {@code streamId}, per the RFC 9000 §18.2 table in
	 * this class's Javadoc.
	 * <p>
	 * The single source of truth for both uses of that number: the limit a receiving part starts with,
	 * and the window a credit grant restores above the consumed offset (FR-025). Computing them apart
	 * would let the advertised limit and the enforced one drift.
	 */
	private long receiveWindowOf(long streamId) {
		if (!StreamIds.isBidirectional(streamId)) return settings.initialMaxStreamDataUni();
		return isLocallyInitiated(streamId)
			? settings.initialMaxStreamDataBidiLocal()
			: settings.initialMaxStreamDataBidiRemote();
	}

	private StreamCounter counterFor(long streamId) {
		boolean local = isLocallyInitiated(streamId);
		StreamCounter counter = StreamIds.isBidirectional(streamId)
			? local ? localBidi : peerBidi
			: local ? localUni : peerUni;
		assert counter != null;
		return counter;
	}

	private boolean isLocallyInitiated(long streamId) {
		return StreamIds.isClientInitiated(streamId) == (role == Role.CLIENT);
	}

	// ---------------------------------------------------------------- QuicFrameHandler

	/**
	 * Routes one frame the connection layer does not keep for itself — the nine of RFC 9000 §19.4,
	 * §19.5, §19.8 and §19.9–§19.14 this layer owns. Anything else is a {@code PROTOCOL_VIOLATION}
	 * naming the type, {@code DATAGRAM} included, by design (RFC 9221 is feature 06's).
	 * <p>
	 * <b>Buffer ownership</b>: the frame is <b>borrowed</b>. A payload that must outlive this call is
	 * retained with {@code slice()}, never copied (FR-014); the connection recycles the frame itself the
	 * moment this returns, on the success path and the throwing one alike.
	 *
	 * @throws QuicTransportException with the RFC 9000 §20.1 code for the violation, for every peer
	 *                                violation this layer detects. The connection layer turns it into a
	 *                                {@code CONNECTION_CLOSE}
	 */
	@Override
	public void onFrame(QuicConnection c, EncryptionLevel level, QuicFrame frame) throws QuicTransportException {
		checkInReactorThread(this);
		// One batch for the whole frame: a MAX_DATA can resume every writer on the connection, and a
		// STREAM frame can carry the reader past the threshold that grants credit back. Either way what
		// comes out is a burst of frames that belong in one datagram (research R-03).
		beginBatch();
		try {
			routeFrame(level, frame);
		} finally {
			endBatch();
		}
	}

	private void routeFrame(EncryptionLevel level, QuicFrame frame) throws QuicTransportException {
		if (frame instanceof StreamFrame streamFrame) {
			onStreamFrame(level, streamFrame);
			return;
		}
		if (frame instanceof MaxDataFrame maxData) {
			// RFC 9000 §19.9. Only an actual increase is worth a retry — a stale limit is routine, and
			// raiseLimit reporting `false` is what keeps it from waking every writer on the connection.
			if (requireFlowControl().onMaxData(maxData.maximum)) {
				retryBlockedWriters();
			}
			return;
		}
		if (frame instanceof MaxStreamDataFrame maxStreamData) {
			onMaxStreamDataFrame(maxStreamData);
			return;
		}
		if (frame instanceof MaxStreamsFrame maxStreams) {
			// RFC 9000 §19.11: a count above 2^60 could name a stream identifier that does not exist, and
			// the RFC makes receiving one a connection error. The codec deliberately leaves this
			// protocol-semantic bound to the layer that owns the meaning of the number.
			if (!StreamCounter.isValidStreamCount(maxStreams.maximum)) {
				throw new QuicTransportException(QuicTransportErrors.FRAME_ENCODING_ERROR,
					"MAX_STREAMS carried a stream count of " + maxStreams.maximum +
					", above the 2^60 maximum");
			}
			StreamDirection direction = maxStreams.type == QuicStreamLimitType.BIDIRECTIONAL
				? StreamDirection.BIDIRECTIONAL
				: StreamDirection.UNIDIRECTIONAL;
			StreamCounter counter = counterOf(direction);
			// Only an actual increase is worth a drain — the same rule as MAX_DATA's, one level up. A limit
			// raised with nobody resumed is a stall, so the drain is not optional (FR-029).
			if (counter != null && counter.onMaxStreams(maxStreams.maximum)) {
				drainPendingOpens(direction);
			}
			return;
		}
		if (frame instanceof StreamDataBlockedFrame blocked) {
			onStreamDataBlockedFrame(blocked);
			return;
		}
		if (frame instanceof DataBlockedFrame) {
			// RFC 9000 §19.12: informational, and the connection-level twin of the branch above. It names no
			// stream, so there is no identifier rule for it to break.
			grantConnectionCredit();
			return;
		}
		if (frame instanceof StreamsBlockedFrame blocked) {
			onStreamsBlockedFrame(blocked);
			return;
		}
		if (frame instanceof ResetStreamFrame reset) {
			onResetStreamFrame(reset);
			return;
		}
		if (frame instanceof StopSendingFrame stopSending) {
			onStopSendingFrame(stopSending);
			return;
		}
		throw new QuicTransportException(QuicTransportErrors.PROTOCOL_VIOLATION,
			"Received a " + frame.getClass().getSimpleName() + " at " + level +
			", which this endpoint advertises no support for");
	}

	/**
	 * Retries every sending part that a connection-wide event may have unblocked (FR-026's other half).
	 * <p>
	 * Connection-wide is the operative word: {@code MAX_DATA} and a freed outstanding budget are shared
	 * by every stream, so the one that gets to use them is whichever became pending first, and every
	 * other has to be offered the chance too. Over {@link #pendingWriters} rather than every stream on
	 * the connection, because most streams have nothing pending at any given moment and walking all of
	 * them per acked or lost frame is wasted work proportional to stream count rather than to the (usually
	 * far smaller) number of writers actually waiting on something. A part that turns out to still be
	 * blocked simply re-announces nothing (FR-027).
	 * <p>
	 * Over a copy of the set, because a resumed write completes its promise, whose continuation runs
	 * synchronously and routinely writes again, opens a stream, or releases one — any of which can mutate
	 * {@link #pendingWriters} itself.
	 */
	private void retryBlockedWriters() {
		beginBatch();
		try {
			for (SendPart sendPart : new ArrayList<>(pendingWriters)) {
				sendPart.pump();
			}
		} finally {
			endBatch();
		}
	}

	/**
	 * Routes one {@code MAX_STREAM_DATA} (RFC 9000 §19.10): the peer raised the limit on the half
	 * <em>this</em> endpoint sends into, so it can unblock at most the one stream it names.
	 * <p>
	 * Informational for flow control, but not for the identifier: RFC 9000 §19.10 makes a
	 * {@code MAX_STREAM_DATA} on a stream this endpoint may only receive on, or on a locally-initiated
	 * stream it never opened, a {@code STREAM_STATE_ERROR} — there is no sending half for the limit to
	 * apply to. It is routed through {@link #streamFor} for exactly the reason {@code STREAM},
	 * {@code RESET_STREAM} and {@code STOP_SENDING} are: the identifier rules are one rule, and a frame
	 * naming a peer-initiated stream for the first time opens it and every lower-numbered one of its
	 * type (FR-003).
	 */
	private void onMaxStreamDataFrame(MaxStreamDataFrame frame) throws QuicTransportException {
		long streamId = frame.streamId;
		requirePeerParameters();

		if (!StreamIds.canSend(streamId, role)) {
			throw new QuicTransportException(QuicTransportErrors.STREAM_STATE_ERROR,
				"peer sent MAX_STREAM_DATA on stream " + streamId +
				", which this endpoint may only receive on");
		}

		QuicStream stream = streamFor(streamId, "MAX_STREAM_DATA");
		if (stream == null) return;
		SendPart sendPart = stream.sendPart();
		if (sendPart == null) {
			// Unreachable while canSend decides which halves createStream builds; kept so that a change to
			// one of the two cannot silently become a NullPointerException on peer input.
			throw new QuicTransportException(QuicTransportErrors.STREAM_STATE_ERROR,
				"stream " + streamId + " has no sending part on this endpoint");
		}

		// Only an actual increase is worth a pump — a stale limit is routine (FR-026), and raiseLimit
		// reporting `false` is what keeps it from waking a writer that is still just as blocked.
		if (sendPart.flowControl().raiseLimit(frame.maximum)) {
			sendPart.pump();
		}
	}

	/**
	 * Routes one {@code STREAM_DATA_BLOCKED} (RFC 9000 §19.13): the peer has data for this stream and
	 * this endpoint's own limit is what holds it.
	 * <p>
	 * Informational — credit is granted on the <em>reader's</em> consumption (FR-025), never because the
	 * peer complained — but if the threshold is already met this is as good a moment as any to notice,
	 * and it costs the peer a round trip not to.
	 * <p>
	 * The identifier is <b>not</b> informational: RFC 9000 §19.13 makes a {@code STREAM_DATA_BLOCKED} on
	 * a send-only stream, or on a locally-initiated stream this endpoint never opened, a
	 * {@code STREAM_STATE_ERROR} — the peer is claiming to be blocked sending on a half it does not
	 * have.
	 */
	private void onStreamDataBlockedFrame(StreamDataBlockedFrame frame) throws QuicTransportException {
		long streamId = frame.streamId;
		requirePeerParameters();

		if (!StreamIds.canReceive(streamId, role)) {
			throw new QuicTransportException(QuicTransportErrors.STREAM_STATE_ERROR,
				"peer sent STREAM_DATA_BLOCKED on stream " + streamId +
				", which only this endpoint may send on");
		}

		QuicStream stream = streamFor(streamId, "STREAM_DATA_BLOCKED");
		if (stream == null) return;
		ReceivePart receivePart = stream.receivePart();
		if (receivePart == null) {
			// Unreachable, for the same reason as its twin in onMaxStreamDataFrame, and kept for the same one.
			throw new QuicTransportException(QuicTransportErrors.STREAM_STATE_ERROR,
				"stream " + streamId + " has no receiving part on this endpoint");
		}
		grantStreamCredit(receivePart);
	}

	/**
	 * Routes one {@code STREAMS_BLOCKED} (RFC 9000 §19.14): the peer wants to open a stream of this type
	 * and the count this endpoint advertised is what holds it. The stream-count twin of
	 * {@link #onStreamDataBlockedFrame}, and informational for the same reason — credit is granted off
	 * released streams (FR-028), never because the peer complained.
	 * <p>
	 * It names a <em>type</em> rather than a stream, so there is no identifier rule to break; what there
	 * is instead is the same 2^60 bound {@code MAX_STREAMS} carries, since a count above it describes a
	 * stream identifier that cannot exist (RFC 9000 §19.14).
	 */
	private void onStreamsBlockedFrame(StreamsBlockedFrame frame) throws QuicTransportException {
		if (!StreamCounter.isValidStreamCount(frame.limit)) {
			throw new QuicTransportException(QuicTransportErrors.FRAME_ENCODING_ERROR,
				"STREAMS_BLOCKED carried a stream count of " + frame.limit + ", above the 2^60 maximum");
		}
		grantStreamCountCredit(frame.type);
	}

	/**
	 * Routes one {@code STREAM} frame (RFC 9000 §19.8). The frame is <b>borrowed</b>: its payload is
	 * retained by {@code slice()} where it is kept, and this method recycles nothing.
	 * <p>
	 * The order of the checks is the contract, not an implementation detail, and it is the same as
	 * {@link #onResetStreamFrame}'s: identifier legality, then the encoding bound on the offset range,
	 * then the stream identifier's own rules — which can open a run of streams — then the
	 * connection-level limit, and only then the receiving part. Nothing is opened, registered or
	 * retained on behalf of a frame that a cheaper check will refuse (SI-4, CHK080).
	 * <p>
	 * <b>Why the connection-level limit comes after the stream is resolved</b>, when SI-4 otherwise
	 * wants every wire-supplied quantity checked before anything is allocated. Two reasons, and both
	 * make the alternative worse rather than merely inconvenient:
	 * <ul>
	 *   <li>What this frame can cause to be <em>allocated</em> is a run of streams, and that is already
	 *       bounded earlier and independently by {@link #openPeerStreams}'s {@code STREAM_LIMIT_ERROR}
	 *       check on the arriving ordinal — which is the count-before-allocate SI-4 actually asks
	 *       for.</li>
	 *   <li>The connection-level claim is {@code offset + length} minus <em>this receiving part's</em>
	 *       high-water mark, so computing it before the part is resolved would have to assume a mark of
	 *       zero. For an already-open stream that over-charges; for a stream already released (whose
	 *       accounting has been folded back into the connection) it would turn an ordinary
	 *       retransmission into a spurious {@code FLOW_CONTROL_ERROR} on conforming traffic.</li>
	 * </ul>
	 * What FR-023 does require is preserved exactly: the check is still <em>before</em> the frame
	 * reaches {@link ReceivePart}, which is the point at which a byte could be delivered.
	 *
	 * @param level the encryption level this frame's packet was protected at, carried forward to
	 *              {@link QuicStream#arrivalLevel()} so the layer above can tell data a peer sent in a
	 *              0-RTT packet — and may therefore be replaying — from ordinary data (spec FR-064a)
	 */
	private void onStreamFrame(EncryptionLevel level, StreamFrame frame) throws QuicTransportException {
		long streamId = frame.streamId;
		requirePeerParameters();

		// Identifier legality before any state is touched (SI-4): a frame on a stream this endpoint may
		// only send on is a state violation, whatever else it carries.
		if (!StreamIds.canReceive(streamId, role)) {
			throw new QuicTransportException(QuicTransportErrors.STREAM_STATE_ERROR,
				"peer sent STREAM data on stream " + streamId + ", which only this endpoint may send on");
		}
		int length = frame.data.readRemaining();
		// SI-4: the frame's own syntax bound, before the identifier is allowed to open anything. Written as
		// a subtraction rather than `offset + length > MAX_OFFSET`, which would itself overflow for an
		// offset near Long.MAX_VALUE and wrap to a small positive that passes. ReceivePart and
		// StreamReassembler repeat it because both are reachable on their own; here it must come first,
		// since `end` is what the connection limit below is compared with.
		if (frame.offset < 0 || frame.offset > StreamReassembler.MAX_OFFSET - length) {
			throw new QuicTransportException(QuicTransportErrors.FRAME_ENCODING_ERROR,
				"STREAM frame on stream " + streamId + " has an offset range exceeding 2^62-1");
		}

		QuicStream stream = streamFor(streamId, "STREAM");
		if (stream == null) return;

		ReceivePart receivePart = stream.receivePart();
		if (receivePart == null) {
			throw new QuicTransportException(QuicTransportErrors.STREAM_STATE_ERROR,
				"stream " + streamId + " has no receiving part on this endpoint");
		}

		ConnectionFlowController flowControl = requireFlowControl();
		// The connection-level limit is checked *before* the frame is handed over, not after (FR-023).
		// ReceivePart delivers what it accepts — it resolves a parked read on the spot — so checking
		// afterwards would hand the application bytes the peer was never granted and only then close the
		// connection. The stream-level check has no such problem: it lives inside ReceivePart, ahead of
		// the reassembler.
		long claimed = Math.max(0, frame.offset + length - receivePart.highestOffsetReceived());
		if (claimed > flowControl.receiveAvailable()) {
			throw new QuicTransportException(QuicTransportErrors.FLOW_CONTROL_ERROR,
				"peer sent past the advertised connection limit " + flowControl.receiveLimit());
		}

		// Recorded before the bytes are handed over, never after: ReceivePart resolves a parked read on
		// the spot, and that continuation is where the layer above reads the arrival level (FR-064a).
		stream.onDataArrived(level);

		// slice() rather than the payload itself: the receiving part owns what it is given, while the
		// connection still owns the frame for its own recycling sweep (FR-014).
		long newBytes = receivePart.onStreamFrame(frame.offset, frame.fin, frame.data.slice());
		if (newBytes > 0 && !flowControl.onBytesReceived(newBytes)) {
			// Unreachable: the check above already refused anything the window could not absorb. Kept
			// because the two counts of "bytes above the high-water mark" are computed independently, and
			// a divergence between them must end the connection rather than quietly overrun the window.
			throw new QuicTransportException(QuicTransportErrors.FLOW_CONTROL_ERROR,
				"peer sent past the advertised connection limit " + flowControl.receiveLimit());
		}
	}

	/**
	 * Routes one {@code RESET_STREAM} (RFC 9000 §19.4, FR-034): the peer gave up on its sending half.
	 * <p>
	 * The order of the checks is the contract, not an implementation detail — identifier legality, then
	 * the encoding bound on the declared final size, then the connection-level limit, and only then the
	 * receiving part's own §4.5 and stream-limit rules. Each one is about a different thing the peer
	 * could be lying about, and each is decided before the previous one's answer has been used to touch
	 * any state (SI-4).
	 */
	private void onResetStreamFrame(ResetStreamFrame frame) throws QuicTransportException {
		long streamId = frame.streamId;
		requirePeerParameters();

		// RFC 9000 §19.4: a RESET_STREAM for a stream this endpoint may only send on is a state violation,
		// whatever else it carries.
		if (!StreamIds.canReceive(streamId, role)) {
			throw new QuicTransportException(QuicTransportErrors.STREAM_STATE_ERROR,
				"peer sent RESET_STREAM on stream " + streamId + ", which only this endpoint may send on");
		}
		// SI-4: validated before it is used in arithmetic. ReceivePart repeats this check because it is
		// reachable on its own; here it must come first, since the final size is what the connection-level
		// limit is compared with.
		if (frame.finalSize < 0 || frame.finalSize > StreamReassembler.MAX_OFFSET) {
			throw new QuicTransportException(QuicTransportErrors.FRAME_ENCODING_ERROR,
				"RESET_STREAM on stream " + streamId + " declared a final size exceeding 2^62-1");
		}

		QuicStream stream = streamFor(streamId, "RESET_STREAM");
		if (stream == null) return;
		ReceivePart receivePart = stream.receivePart();
		if (receivePart == null) {
			throw new QuicTransportException(QuicTransportErrors.STREAM_STATE_ERROR,
				"stream " + streamId + " has no receiving part on this endpoint");
		}

		ConnectionFlowController flowControl = requireFlowControl();
		// RFC 9000 §4.5: the declared final size may be above anything that actually arrived, so an abort
		// can reveal bytes the peer must be charged for — otherwise resetting would be a way to buy the
		// connection window back. Checked before the frame is handed over, exactly as a STREAM frame's is
		// (FR-023).
		long claimed = Math.max(0, frame.finalSize - receivePart.highestOffsetReceived());
		if (claimed > flowControl.receiveAvailable()) {
			throw new QuicTransportException(QuicTransportErrors.FLOW_CONTROL_ERROR,
				"peer reset past the advertised connection limit " + flowControl.receiveLimit());
		}

		boolean firstReset = receivePart.resetErrorCode() == null;
		long newBytes = receivePart.onResetStream(frame.appErrorCode, frame.finalSize);
		if (newBytes > 0 && !flowControl.onBytesReceived(newBytes)) {
			// Unreachable, for the same reason as its twin in onStreamFrame, and kept for the same one.
			throw new QuicTransportException(QuicTransportErrors.FLOW_CONTROL_ERROR,
				"peer reset past the advertised connection limit " + flowControl.receiveLimit());
		}
		if (firstReset && receivePart.resetErrorCode() != null) {
			streamsResetByPeer++;
			// The application error code and the final size, never a byte of payload (SI-6).
			logger.debug("{} stream {} was reset by the peer with application error code {}, final size {}",
				role, streamId, frame.appErrorCode, frame.finalSize);
			if (inspector != null) {
				inspector.onStreamReset(streamId, true, frame.appErrorCode);
			}
		}
		// The receiving half may have become terminal on the spot — a parked read is the application
		// observing the abort — and the sending half may have finished long ago.
		releaseIfTerminal(streamId);
	}

	/**
	 * Routes one {@code STOP_SENDING} (RFC 9000 §19.5, FR-033): the peer wants nothing more on this
	 * stream, so RFC 9000 §3.5 has <em>this</em> endpoint abort its own sending half — with the peer's
	 * code, and answering with a {@code RESET_STREAM} of its own.
	 */
	private void onStopSendingFrame(StopSendingFrame frame) throws QuicTransportException {
		long streamId = frame.streamId;
		requirePeerParameters();

		// RFC 9000 §19.5: a STOP_SENDING for a receive-only stream is a state violation — there is no
		// sending half of it to stop.
		if (!StreamIds.canSend(streamId, role)) {
			throw new QuicTransportException(QuicTransportErrors.STREAM_STATE_ERROR,
				"peer sent STOP_SENDING on stream " + streamId + ", which this endpoint may only receive on");
		}

		QuicStream stream = streamFor(streamId, "STOP_SENDING");
		if (stream == null) return;
		SendPart sendPart = stream.sendPart();
		if (sendPart == null) {
			throw new QuicTransportException(QuicTransportErrors.STREAM_STATE_ERROR,
				"stream " + streamId + " has no sending part on this endpoint");
		}

		if (!sendPart.isReset()) {
			logger.debug("{} the peer asked this endpoint to stop sending on stream {}," +
						 " application error code {}", role, streamId, frame.appErrorCode);
		}
		sendPart.onStopSending(frame.appErrorCode);
		releaseIfTerminal(streamId);
	}

	/**
	 * A frame this layer sent has been acknowledged (RFC 9002 §2): a {@code STREAM} frame's bytes are
	 * Data Recvd for RFC 9000 §3.1's purposes and its outstanding budget is freed, a
	 * {@code RESET_STREAM} completes the Reset Sent → Reset Recvd transition, and a
	 * {@code STOP_SENDING} has done its job and stops being retransmitted.
	 * <p>
	 * <b>Buffer ownership</b>: ownership <b>passes in</b>. Every path here either recycles the frame or
	 * hands it to the part that owns it, which recycles it in turn — there is no path on which it
	 * survives this call.
	 */
	@Override
	public void onFrameAcknowledged(QuicConnection c, QuicFrame frame) {
		checkInReactorThread(this);
		if (frame instanceof StreamFrame streamFrame) {
			SendPart sendPart = sendPartOf(streamFrame.streamId);
			if (sendPart == null) {
				streamFrame.recycle();
				return;
			}
			beginBatch();
			try {
				// Takes ownership, including the recycle.
				sendPart.onFrameAcknowledged(streamFrame);
				// An acknowledgement is the only thing that frees outstanding budget (research R-08), and
				// the budget is connection-wide — so the writer it releases is not necessarily the one whose
				// frame was acknowledged. Without this, a writer withheld at the budget is withheld forever.
				retryBlockedWriters();
			} finally {
				endBatch();
			}
			return;
		}
		if (frame instanceof ResetStreamFrame reset) {
			// RFC 9000 §3.1's Reset Sent → Reset Recvd: the abort has been delivered, so this half is
			// terminal and the stream may be released.
			SendPart sendPart = sendPartOf(reset.streamId);
			Recyclers.recycle(frame);
			if (sendPart != null) {
				sendPart.onResetAcknowledged();
			}
			return;
		}
		if (frame instanceof StopSendingFrame stopSending) {
			// Acknowledged is where its retransmission ends (FR-032); what the peer does about it is the
			// peer's RESET_STREAM to send, and no business of this one.
			ReceivePart receivePart = receivePartOf(stopSending.streamId);
			Recyclers.recycle(frame);
			if (receivePart != null) {
				receivePart.onStopSendingAcknowledged();
			}
			return;
		}
		Recyclers.recycle(frame);
	}

	/**
	 * A frame this layer sent was declared lost (RFC 9002 §6.1). Stream data goes back at its original
	 * offsets (FR-017) unless its part was aborted, in which case RFC 9000 §3.1 has it released
	 * instead; a {@code RESET_STREAM} or {@code STOP_SENDING} is regenerated because both are reliable;
	 * a limit or blocked frame is <em>not</em> replayed but re-decided from live state, since RFC 9000
	 * §13.3 wants the value in force now rather than the stale one (FR-030).
	 * <p>
	 * <b>Buffer ownership</b>: ownership <b>passes in</b>. Every path either recycles the frame or
	 * re-enqueues it, handing ownership back to the connection.
	 */
	@Override
	public void onFrameLost(QuicConnection c, QuicFrame frame) {
		checkInReactorThread(this);
		if (frame instanceof StreamFrame streamFrame) {
			SendPart sendPart = sendPartOf(streamFrame.streamId);
			if (sendPart == null) {
				streamFrame.recycle();
				return;
			}
			// Stream data is reliable, so the recycling default of QuicFrameHandler is wrong for it: the
			// frame goes back at its original offsets (FR-017) — unless the part has been aborted, in which
			// case FR-018 has it released instead, and its outstanding budget with it.
			beginBatch();
			try {
				sendPart.onFrameLost(streamFrame);
				if (sendPart.isReset()) {
					// Budget freed and never to be re-spent by this part, so somebody else may now proceed —
					// and if every remaining frame of an aborted stream is lost, this is the only chance to
					// notice, since no acknowledgement will ever arrive to do it.
					retryBlockedWriters();
				}
			} finally {
				endBatch();
			}
			return;
		}
		if (frame instanceof ResetStreamFrame reset) {
			// RFC 9000 §19.4: the abort itself is reliable, so unlike the stream data it supersedes it is
			// re-enqueued until acknowledged. Regenerated rather than replayed — the three fields were
			// fixed at the abort, so the two are the same bytes, and regenerating keeps the frame the send
			// part's to own.
			SendPart sendPart = sendPartOf(reset.streamId);
			Recyclers.recycle(frame);
			if (sendPart != null) {
				sendPart.onResetLost();
			}
			return;
		}
		if (frame instanceof StopSendingFrame stopSending) {
			// Likewise reliable (FR-032): a dropped request to stop would otherwise cost a whole window of
			// bytes this endpoint will only discard.
			ReceivePart receivePart = receivePartOf(stopSending.streamId);
			Recyclers.recycle(frame);
			if (receivePart != null) {
				receivePart.onStopSendingLost();
			}
			return;
		}
		// Everything else this manager sends is a limit frame or a blocked announcement, and RFC 9000
		// §13.3 is explicit that neither is retransmitted verbatim: what the peer needs is the value in
		// force *now*, not the stale one the lost frame carried. So the lost object is released and the
		// decision is taken again from live state — the same "regenerate, never replay" shape as
		// RESET_STREAM's above, differing only in that a reset's three fields cannot have moved since and
		// a limit's can (research R-09).
		//
		// One batch over the whole repair, so a burst of them shares a datagram (research R-03).
		beginBatch();
		try {
			repairLostLimitFrame(frame);
		} finally {
			endBatch();
		}
	}

	/**
	 * Regenerates, from current state, what a lost limit or blocked frame was carrying — or sends nothing
	 * at all when a later frame already carried better information (FR-030, RFC 9000 §13.3).
	 * <p>
	 * The two families need opposite treatment, and both hinge on a value that is already the single
	 * source of truth for "the highest ever decided":
	 * <ul>
	 *   <li><b>Grants</b> — {@code MAX_DATA}, {@code MAX_STREAM_DATA}, {@code MAX_STREAMS}. The receiving
	 *       side's own limit moves only when a grant is decided, atomically with enqueueing the frame, so
	 *       a lost frame is stale exactly when that limit has since risen above it. When it has not, a
	 *       fresh frame carrying the current value is queued <em>bypassing</em> the once-per-value guard
	 *       in the grant methods, which would otherwise refuse to re-announce a value it had already
	 *       announced — the very frame that was lost.</li>
	 *   <li><b>Blocked announcements</b> — {@code DATA_BLOCKED}, {@code STREAM_DATA_BLOCKED},
	 *       {@code STREAMS_BLOCKED}. These are de-duplicated by a "last announced at" tracker, so the
	 *       repair is to rewind that tracker to its never-announced sentinel and let the ordinary blocked
	 *       check speak again. If nothing is blocked any more, it says nothing, which is the right
	 *       answer.</li>
	 * </ul>
	 * <b>Takes ownership of {@code frame}</b>, which is released rather than re-queued on every path.
	 */
	private void repairLostLimitFrame(QuicFrame frame) {
		if (frame instanceof MaxDataFrame maxData) {
			ConnectionFlowController flowControl = connectionFlowControl;
			Recyclers.recycle(frame);
			// A limit above what was lost means a later MAX_DATA already told the peer more than this one
			// would have; below it is unreachable, since receiveLimit never falls.
			if (flowControl != null && flowControl.receiveLimit() == maxData.maximum) {
				enqueueControlFrame(new MaxDataFrame(flowControl.receiveLimit()));
			}
			return;
		}
		if (frame instanceof MaxStreamDataFrame maxStreamData) {
			ReceivePart receivePart = receivePartOf(maxStreamData.streamId);
			Recyclers.recycle(frame);
			// A released stream needs no credit: the peer can never send another byte on it.
			if (receivePart != null && receivePart.flowControl().limit() == maxStreamData.maximum) {
				enqueueControlFrame(new MaxStreamDataFrame(
					receivePart.streamId(), receivePart.flowControl().limit()));
			}
			return;
		}
		if (frame instanceof MaxStreamsFrame maxStreams) {
			// The peer-initiated counter: initial_max_streams_* is a permission this endpoint gave its
			// peer, so it is that counter, not the locally-initiated one, whose limit MAX_STREAMS carries.
			StreamCounter counter = maxStreams.type == QuicStreamLimitType.BIDIRECTIONAL ? peerBidi : peerUni;
			Recyclers.recycle(frame);
			if (counter != null && counter.limit() == maxStreams.maximum) {
				enqueueControlFrame(new MaxStreamsFrame(counter.limit(), maxStreams.type));
			}
			return;
		}
		if (frame instanceof DataBlockedFrame blocked) {
			ConnectionFlowController flowControl = connectionFlowControl;
			Recyclers.recycle(frame);
			if (dataBlockedAnnouncedAt != blocked.limit) return;
			if (flowControl == null || flowControl.sendAvailable() > 0) return;
			dataBlockedAnnouncedAt = -1;
			// Every writer, because the limit is shared: whichever one is still held by it re-announces,
			// and one that is not stays silent (FR-027).
			retryBlockedWriters();
			return;
		}
		if (frame instanceof StreamDataBlockedFrame blocked) {
			SendPart sendPart = sendPartOf(blocked.streamId);
			Recyclers.recycle(frame);
			if (sendPart != null) {
				sendPart.onStreamDataBlockedLost(blocked.limit);
			}
			return;
		}
		if (frame instanceof StreamsBlockedFrame blocked) {
			StreamDirection direction = blocked.type == QuicStreamLimitType.BIDIRECTIONAL
				? StreamDirection.BIDIRECTIONAL
				: StreamDirection.UNIDIRECTIONAL;
			Recyclers.recycle(frame);
			long announcedAt = direction == StreamDirection.BIDIRECTIONAL
				? bidiStreamsBlockedAnnouncedAt
				: uniStreamsBlockedAnnouncedAt;
			if (announcedAt != blocked.limit) return;
			if (direction == StreamDirection.BIDIRECTIONAL) {
				bidiStreamsBlockedAnnouncedAt = -1;
			} else {
				uniStreamsBlockedAnnouncedAt = -1;
			}
			// Re-announces from the tail of the drain, and only for opens that are still withheld — an
			// open that has since been served is not blocked and has nothing to announce (FR-028).
			drainPendingOpens(direction);
			return;
		}
		// Nothing else reaches a handler on this path, but a frame added to the transport's repertoire
		// later must not leak while this method is being taught about it.
		Recyclers.recycle(frame);
	}

	private @Nullable SendPart sendPartOf(long streamId) {
		QuicStream stream = streams.get(streamId);
		return stream == null ? null : stream.sendPart();
	}

	private @Nullable ReceivePart receivePartOf(long streamId) {
		QuicStream stream = streams.get(streamId);
		return stream == null ? null : stream.receivePart();
	}

	/**
	 * The handshake completed, so the peer's RFC 9000 §18.2 transport parameters are available: every
	 * window, every stream count and the connection-level flow controller are sized from them here, and
	 * the opens withheld until now are served (FR-042). Holds no buffer, so there is nothing to own.
	 */
	@Override
	public void onEstablished(QuicConnection c) {
		checkInReactorThread(this);
		QuicTransportParameters peer = connection.peerTransportParameters();
		if (peer == null) {
			// Unreachable: the connection reaches ESTABLISHED only after validating the peer's parameters.
			connection.closeWith(QuicTransportErrors.INTERNAL_ERROR,
				"the connection was established without the peer's transport parameters");
			return;
		}
		try {
			if (peerParameters == null) {
				initializeFrom(peer);
			} else {
				// Already sized from the limits remembered with a session ticket (RFC 9000 §7.4.1). The
				// handshake's are the real ones and can only be larger, so this is a widening, never a
				// rebuild — the streams, offsets and consumed credit of the early data all stand.
				raiseLimitsFrom(peer);
			}
		} catch (QuicTransportException e) {
			// onEstablished cannot declare a checked exception (QuicFrameHandler's signature), so the
			// standard "throw and let the caller close" pattern used everywhere else in this class is
			// done by hand here instead.
			connection.closeWith(e.errorCode(), e.reasonPhrase());
			return;
		}
		drainPendingOpens(null);
	}

	// ---------------------------------------------------------------- early-data rejection (FR-055)

	/**
	 * The peer refused this connection's early data. Forwarded, and nothing else.
	 * <p>
	 * <b>This layer discards no stream of its own here, deliberately.</b> A stream created in early data
	 * may be work that has to be re-created on a fresh identifier — an HTTP/3 request the server never
	 * saw — or work that merely has to be carried to 1-RTT and retransmitted, which
	 * {@code QuicConnection} has already arranged by re-levelling the send queue. Only the application
	 * protocol knows which of the two a given stream is, and a transport that guessed would be wrong for
	 * the other one.
	 */
	@Override
	public void onEarlyDataRejected(QuicConnection c) {
		checkInReactorThread(this);
		if (earlyDataRejectionListener != null) {
			earlyDataRejectionListener.run();
		}
	}

	/** The still-open streams that were created while application data would have left at 0-RTT. */
	public Set<Long> earlyDataStreams() {
		checkInReactorThread(this);
		Set<Long> ids = new LinkedHashSet<>();
		for (Map.Entry<Long, QuicStream> entry : streams.entrySet()) {
			if (entry.getValue().isEarlyData()) ids.add(entry.getKey());
		}
		return ids;
	}

	/**
	 * Abandons {@code streamId} <b>silently</b>: both halves become terminal, every buffer they hold is
	 * released, everything still queued for the stream is purged, and the stream is released — with
	 * <b>no frame of any kind on the wire</b> (spec FR-055).
	 * <p>
	 * That silence is the point, and it is what makes this different from {@link QuicStream#reset}. The
	 * one caller is a peer's refusal of early data: the peer dropped the stream's 0-RTT packets
	 * undecrypted, so it has never heard of the stream, and RFC 9000 §2.1 opens every lower-numbered
	 * stream of a type on first mention — a {@code RESET_STREAM} naming it would <i>create</i> it at the
	 * peer for the sole purpose of aborting it.
	 * <p>
	 * Idempotent, and safe for an unknown identifier. The local stream counter is <b>not</b> rewound:
	 * identifiers are handed out once (RFC 9000 §2.1), so the work is re-created on the next one.
	 * <p>
	 * <b>Send-side connection credit consumed by this stream is not reclaimed</b>, matching the rule that
	 * governs every other abandonment here: bytes given an offset stay spent, because a peer able to
	 * reclaim its spend by abandoning a stream could buffer unboundedly at the receiver. Frames of this
	 * stream already in flight need nothing: once it leaves {@link #streams} they find no part to go back
	 * to, and {@link #onFrameLost} releases them.
	 *
	 * @param cause what the stream's pending reads, writes and {@link QuicStream#whenClosed()} fail with.
	 *              Supplied by the caller, because the reason belongs to the protocol above this one
	 */
	public void discardStream(long streamId, Exception cause) {
		checkInReactorThread(this);
		QuicStream stream = streams.get(streamId);
		if (stream == null) return;
		// The order is the substance of this method, and it is the guard rail this class already follows
		// for every other abandonment: the queue is purged, the stream leaves the map and its credit is
		// returned FIRST, and only then is anything failed. Failing runs the application's continuation
		// synchronously, and that continuation routinely discards, resets or closes again — each of which
		// must find a stream that is already gone rather than one that is half gone. A second discard
		// reaching the release bookkeeping would return the same concurrency credit twice.
		connection.dropQueuedFrames(frame -> belongsTo(streamId, frame));
		unregister(streamId, stream);
		// onConnectionClosed rather than closeEx: it is also the end of any retransmission this stream
		// would otherwise still owe, which is exactly the silence this method exists for. It is what fails
		// the parked reads and writes, and it fails whenClosed() with `cause` — unlike a release, which
		// completes it successfully, because this stream did not finish.
		stream.onConnectionClosed(cause);
		logger.trace("Discarded early-data stream {}", streamId);
	}

	/** The five frame types this layer can have queued that name one stream. */
	private static boolean belongsTo(long streamId, QuicFrame frame) {
		if (frame instanceof StreamFrame stream) return stream.streamId == streamId;
		if (frame instanceof ResetStreamFrame reset) return reset.streamId == streamId;
		if (frame instanceof StopSendingFrame stopSending) return stopSending.streamId == streamId;
		if (frame instanceof MaxStreamDataFrame maxStreamData) return maxStreamData.streamId == streamId;
		if (frame instanceof StreamDataBlockedFrame blocked) return blocked.streamId == streamId;
		return false;
	}

	/**
	 * The connection closed, for any reason (RFC 9000 §10): every pending read, write and open fails
	 * with the connection's own {@code QuicTransportException} <b>unwrapped</b>, so the RFC 9000 §20
	 * code stays visible to the layer above (FR-041). Idempotent under repeated close (WI-9).
	 * <p>
	 * <b>Buffer ownership</b>: every buffer this layer still holds — buffered receive slices and
	 * withheld writes alike — is released here, which is what makes this the last chance for any of
	 * them and why it must not be skipped on a teardown path.
	 */
	@Override
	public void onClosed(QuicConnection c) {
		checkInReactorThread(this);
		if (closed) return;
		closed = true;
		Exception e = connectionException();
		// A copy: releasing a stream can reach back into this map through a failed promise's continuation.
		for (QuicStream stream : new ArrayList<>(streams.values())) {
			stream.onConnectionClosed(e);
		}
		streams.clear();
		while (!pendingOpens.isEmpty()) {
			pendingOpens.pollFirst().promise().trySetException(e);
		}
	}

	/**
	 * The connection's own failure, <b>unwrapped</b> (FR-041): feature 05 depends on the RFC 9000 §20
	 * code staying visible, so it is never wrapped in a {@link QuicStreamException}.
	 */
	private QuicTransportException connectionException() {
		QuicConnection.PeerClose peerClose = connection.peerClose();
		if (peerClose != null) {
			return new QuicTransportException(peerClose.errorCode(),
				"the peer closed the connection: " + peerClose.reason());
		}
		return new QuicTransportException(QuicTransportErrors.NO_ERROR, "the connection closed");
	}

	// ---------------------------------------------------------------- initialisation from the handshake

	/**
	 * Sizes this manager from the limits that bind <b>early data</b>, if there are any (RFC 9000
	 * §7.4.1, spec FR-053). Returns whether it did.
	 * <p>
	 * The values come from {@link QuicConnection#earlyTransportParameters()}, which is the connection's
	 * to answer: a client obeys what it remembered with its session ticket, a server has been promised
	 * nothing and may therefore send nothing. Once the handshake supplies the real parameters,
	 * {@link #raiseLimitsFrom} replaces these — always upwards, because RFC 9000 §7.4.1 forbids the
	 * reduction that would make it a retraction.
	 * <p>
	 * A {@link QuicTransportException} here would have to be reported from inside an {@code open()} or
	 * a frame route, so it is deliberately impossible instead: the remembered parameters were validated
	 * on the connection that issued them, and a client that stored something invalid has only made
	 * itself resume nothing.
	 */
	private boolean initializeFromEarlyParameters() {
		QuicTransportParameters early = connection.earlyTransportParameters();
		if (early == null) return false;
		try {
			initializeFrom(early);
		} catch (QuicTransportException e) {
			// A remembered stream count above 2^60 is our own stored value, not the peer's word for it.
			logger.warn("Ignoring unusable remembered transport parameters: {}", e.getMessage());
			return false;
		}
		return peerParameters != null;
	}

	/** Package-private rather than {@code private} only so a test can drive it with a hand-built peer. */
	void initializeFrom(QuicTransportParameters peer) throws QuicTransportException {
		if (peerParameters != null) return;
		// RFC 9000 §18.2: a stream count above 2^60 could name a stream identifier that does not exist —
		// the same bound onMaxStreamsFrame already enforces for the wire frame, applied here to the
		// transport parameter the peer advertises once, at the handshake. Checked before anything is
		// built from it (SI-4).
		if (!StreamCounter.isValidStreamCount(peer.initialMaxStreamsBidi())) {
			throw new QuicTransportException(QuicTransportErrors.TRANSPORT_PARAMETER_ERROR,
				"initial_max_streams_bidi (" + peer.initialMaxStreamsBidi() + ") exceeds the 2^60 maximum");
		}
		if (!StreamCounter.isValidStreamCount(peer.initialMaxStreamsUni())) {
			throw new QuicTransportException(QuicTransportErrors.TRANSPORT_PARAMETER_ERROR,
				"initial_max_streams_uni (" + peer.initialMaxStreamsUni() + ") exceeds the 2^60 maximum");
		}
		peerParameters = peer;
		// Symmetric, unlike the per-stream windows: what we may send is what the peer granted, what the
		// peer may send is what we advertised.
		connectionFlowControl = ConnectionFlowController.create(peer.initialMaxData(), settings.initialMaxData());
		localBidi = StreamCounter.create(peer.initialMaxStreamsBidi());
		localUni = StreamCounter.create(peer.initialMaxStreamsUni());
		peerBidi = StreamCounter.create(settings.initialMaxStreamsBidi());
		peerUni = StreamCounter.create(settings.initialMaxStreamsUni());
	}

	/**
	 * Replaces the limits this manager was sized from with the handshake's, and wakes everything the
	 * increase may have unblocked (RFC 9000 §7.4.1, spec FR-053).
	 * <p>
	 * Only reachable on a connection that already sent or received early data. It is an <b>increase</b>
	 * by construction, which is the whole reason it can be a widening rather than a rebuild: the
	 * connection layer has already refused a server that reduced any of the seven limits
	 * ({@code TransportParameterValidation.validateNonReduction}), so there is no case in which a byte
	 * already given an offset would turn out to have been over the limit after all.
	 * <p>
	 * Every branch reuses the idiom its wire-frame twin uses — {@code onMaxData}, {@code onMaxStreams},
	 * {@code raiseLimit} — including the "only an actual increase is worth a wake" rule, because a
	 * limit raised with nobody retried is a stall rather than a wrong answer.
	 *
	 * @throws QuicTransportException {@code TRANSPORT_PARAMETER_ERROR} on a stream count above 2^60
	 */
	void raiseLimitsFrom(QuicTransportParameters peer) throws QuicTransportException {
		if (!StreamCounter.isValidStreamCount(peer.initialMaxStreamsBidi())) {
			throw new QuicTransportException(QuicTransportErrors.TRANSPORT_PARAMETER_ERROR,
				"initial_max_streams_bidi (" + peer.initialMaxStreamsBidi() + ") exceeds the 2^60 maximum");
		}
		if (!StreamCounter.isValidStreamCount(peer.initialMaxStreamsUni())) {
			throw new QuicTransportException(QuicTransportErrors.TRANSPORT_PARAMETER_ERROR,
				"initial_max_streams_uni (" + peer.initialMaxStreamsUni() + ") exceeds the 2^60 maximum");
		}
		peerParameters = peer;
		beginBatch();
		try {
			if (requireFlowControl().onMaxData(peer.initialMaxData())) {
				retryBlockedWriters();
			}
			if (localBidi != null && localBidi.onMaxStreams(peer.initialMaxStreamsBidi())) {
				drainPendingOpens(StreamDirection.BIDIRECTIONAL);
			}
			if (localUni != null && localUni.onMaxStreams(peer.initialMaxStreamsUni())) {
				drainPendingOpens(StreamDirection.UNIDIRECTIONAL);
			}
			// A copy: a resumed write completes its promise, whose continuation runs synchronously and
			// routinely writes again, opens a stream or releases one.
			for (QuicStream stream : new ArrayList<>(streams.values())) {
				SendPart sendPart = stream.sendPart();
				if (sendPart == null) continue;
				long window = peerSendWindowOf(peer, stream.id(), stream.isLocallyInitiated());
				if (sendPart.flowControl().raiseLimit(window)) {
					sendPart.pump();
				}
			}
		} finally {
			endBatch();
		}
	}

	/**
	 * The window the peer grants this endpoint on the sending half of {@code streamId}, per the
	 * RFC 9000 §18.2 table in this class's Javadoc.
	 * <p>
	 * The single source of truth for both uses of that number — the limit a sending part starts with,
	 * and the one a resumption's establishment raises it to — so the two cannot drift, exactly as
	 * {@link #receiveWindowOf} is for the receive side.
	 */
	private static long peerSendWindowOf(QuicTransportParameters peer, long streamId, boolean locallyInitiated) {
		if (!StreamIds.isBidirectional(streamId)) return peer.initialMaxStreamDataUni();
		return locallyInitiated
			? peer.initialMaxStreamDataBidiRemote()
			: peer.initialMaxStreamDataBidiLocal();
	}

	/**
	 * @throws QuicTransportException {@code PROTOCOL_VIOLATION} if application data arrived before
	 *                                either the handshake or a resumption supplied the peer's limits
	 */
	private void requirePeerParameters() throws QuicTransportException {
		if (peerParameters != null) return;
		QuicTransportParameters peer = connection.peerTransportParameters();
		if (peer == null) {
			// 0-RTT is the one legitimate route to application data before the handshake, and it comes
			// with its own limits (RFC 9000 §7.4.1). Anything else is a peer sending data under no
			// limit at all.
			if (initializeFromEarlyParameters()) return;
			throw new QuicTransportException(QuicTransportErrors.PROTOCOL_VIOLATION,
				"received stream data before the handshake or a resumption supplied the peer's limits");
		}
		initializeFrom(peer);
	}

	private ConnectionFlowController requireFlowControl() {
		ConnectionFlowController flowControl = connectionFlowControl;
		if (flowControl == null) {
			// Reachable only for a limit frame arriving before establishment, which nothing sends.
			flowControl = ConnectionFlowController.create(0, settings.initialMaxData());
			connectionFlowControl = flowControl;
		}
		return flowControl;
	}

	// ---------------------------------------------------------------- granting credit (FR-025)

	/**
	 * Grants stream-level credit if the application has read at least half the window advertised for
	 * this stream, and tells the peer with a {@code MAX_STREAM_DATA} (RFC 9000 §19.10, FR-025).
	 * <p>
	 * The trigger is the <b>reader</b>, not the wire and not the peer announcing that it is blocked: a
	 * receiver that granted on arrival would be advertising memory it is still holding, and one that
	 * granted only on {@code STREAM_DATA_BLOCKED} would cost the sender a round trip per window.
	 * <p>
	 * {@code grantedLimit} is computed once and used twice — for the frame and for this endpoint's own
	 * enforcement — so the advertised limit and the enforced one cannot drift.
	 */
	private void grantStreamCredit(ReceivePart part) {
		long window = receiveWindowOf(part.streamId());
		if (window <= 0) return;
		if (part.finalSize() != null) {
			// The final size is known, so the peer can never send another byte on this stream: the credit
			// would be unspendable, and the frame carrying it pure overhead (RFC 9000 §3.2).
			return;
		}
		StreamFlowController flowControl = part.flowControl();
		long consumed = part.consumedOffset();
		if (!flowControl.shouldGrantCredit(consumed, window)) return;
		long granted = flowControl.grantedLimit(consumed, window);
		if (!flowControl.raiseLimit(granted)) return;
		if (inspector != null) {
			inspector.onLimitGranted(part.streamId(), granted);
		}
		enqueueControlFrame(new MaxStreamDataFrame(part.streamId(), granted));
	}

	/**
	 * The connection-wide twin of {@link #grantStreamCredit}: a {@code MAX_DATA} once the application has
	 * taken at least half of the connection window (RFC 9000 §19.9, FR-025).
	 * <p>
	 * Driven by the same event, because it is the same act of reading — a byte consumed on any stream is
	 * a byte the connection is no longer holding.
	 */
	private void grantConnectionCredit() {
		ConnectionFlowController flowControl = connectionFlowControl;
		long window = settings.initialMaxData();
		if (flowControl == null || window <= 0) return;
		if (!flowControl.shouldGrantReceiveCredit(window)) return;
		long before = flowControl.receiveLimit();
		long granted = flowControl.grantReceiveCredit(window);
		if (granted == before) return;
		if (inspector != null) {
			inspector.onLimitGranted(CONNECTION_LEVEL, granted);
		}
		enqueueControlFrame(new MaxDataFrame(granted));
	}

	/**
	 * The stream-<em>count</em> twin of the two grants above: a {@code MAX_STREAMS} once at least half of
	 * the concurrency window this endpoint advertised has been given back by released streams
	 * (RFC 9000 §19.11, FR-028, by the FR-025 threshold rule).
	 * <p>
	 * Only a <b>peer-initiated</b> type has anything to grant: {@code initial_max_streams_*} is a
	 * permission this endpoint gave its peer, so it is the peer's streams closing that frees it. A
	 * locally-initiated stream closing frees a permission the peer never held.
	 * <p>
	 * The once-per-value discipline comes free: {@link StreamCounter#grantCredit} is idempotent without an
	 * intervening release, so an unchanged limit means there is nothing to announce — which is also what
	 * makes it safe to call this from a {@code STREAMS_BLOCKED} the peer may repeat.
	 */
	private void grantStreamCountCredit(QuicStreamLimitType type) {
		boolean bidirectional = type == QuicStreamLimitType.BIDIRECTIONAL;
		StreamCounter counter = bidirectional ? peerBidi : peerUni;
		long window = bidirectional ? settings.initialMaxStreamsBidi() : settings.initialMaxStreamsUni();
		if (counter == null || window <= 0) return;
		if (!counter.shouldGrantCredit(window)) return;
		long before = counter.limit();
		long granted = counter.grantCredit(window);
		if (granted == before) return;
		if (inspector != null) {
			// Connection-level, and a stream *count* rather than a byte offset — see Inspector#onLimitGranted.
			inspector.onLimitGranted(CONNECTION_LEVEL, granted);
		}
		enqueueControlFrame(new MaxStreamsFrame(granted, type));
	}

	/**
	 * Queues a frame this manager originated — a limit grant or a blocked announcement — and records
	 * that the batch needs a flush.
	 * <p>
	 * Unlike {@link SendSink#enqueueFrame} this never propagates the failure: every caller is a
	 * notification with nowhere to report to (an application read, a released stream, a blocked pump),
	 * and failing an unrelated write because a control frame would not fit is worse than losing the
	 * frame. A full send queue is connection-fatal anyway, which is what the posted close does.
	 *
	 * @return whether the frame was queued
	 */
	private boolean enqueueControlFrame(QuicFrame frame) {
		try {
			// Takes ownership, recycling the frame itself on both of its rejection paths.
			connection.enqueueFrame(frame);
			sendRequested = true;
			return true;
		} catch (QuicTransportException e) {
			// Posted rather than closed inline: this can run from the middle of a read or a write, and
			// closing here would free state the caller is still walking.
			reactor.post(() -> connection.closeWith(e.errorCode(), e.reasonPhrase()));
			return false;
		}
	}

	// ---------------------------------------------------------------- batching (research R-03)

	private void beginBatch() {
		batchDepth++;
	}

	/**
	 * Closes the innermost batch, and flushes once if anything was queued during any of them.
	 * <p>
	 * Always paired with {@link #beginBatch} in a {@code finally}: a batch left open by a throw would
	 * swallow every later flush on this connection, which fails as a silent stall rather than as an
	 * exception.
	 */
	private void endBatch() {
		if (--batchDepth > 0) return;
		if (!sendRequested) return;
		sendRequested = false;
		connection.requestSend();
	}

	// ---------------------------------------------------------------- release (FR-006)

	/**
	 * Removes a stream once every half it owns is terminal <b>and</b>, on the receive side, the
	 * application has drained it. Returns the stream's concurrency credit and folds its receive
	 * accounting back into the connection.
	 * <p>
	 * Two kinds of credit come back, and they go to different places: the connection-level <em>data</em>
	 * window is restored with a {@code MAX_DATA} (FR-025), and — for a <b>peer-initiated</b> stream only —
	 * the concurrency window is restored with a {@code MAX_STREAMS} (FR-028). One batch over both, since a
	 * release that crosses both thresholds is one event and belongs in one datagram (research R-03).
	 * <p>
	 * A third kind of credit comes back here too, for a sending part reaching {@code RESET_RECVD} with
	 * frames still outstanding: {@link #sendPartOf} will never find this part again once it leaves
	 * {@link #streams}, so an acknowledgement or loss for one of those frames would otherwise find no
	 * part to credit and leak its share of {@code maxOutstandingStreamBytes} forever. And precisely
	 * because no acknowledgement for those frames will ever arrive here again, credit settled this way
	 * is offered to the pending writers immediately — a writer withheld at the budget would otherwise
	 * be waiting on an event that can no longer happen.
	 */
	private void releaseIfTerminal(long streamId) {
		QuicStream stream = streams.get(streamId);
		if (stream == null || !stream.isFullyTerminal()) return;
		release(streamId, stream);
	}

	/**
	 * The release bookkeeping itself, with no condition in front of it — the single funnel every route
	 * out of {@link #streams} runs through, so the concurrency credit, the connection-level fold and the
	 * outstanding-byte settlement each happen exactly once per stream.
	 * <p>
	 * Two callers, and both guard it before calling: {@link #releaseIfTerminal} on the RFC 9000 §3
	 * terminal condition, and {@link #discardStream} on a stream that is being abandoned rather than
	 * finished. The guard differs; the bookkeeping must not. Removing the stream from the map first is
	 * what makes the second call for the same id a no-op rather than a second grant.
	 * <p>
	 * The two differ only in how the stream is <i>told</i>: a released stream completes
	 * {@link QuicStream#whenClosed()} successfully, a discarded one fails it, so
	 * {@link QuicStream#onReleased()} belongs to the first caller alone.
	 */
	private void release(long streamId, QuicStream stream) {
		unregister(streamId, stream);
		stream.onReleased();
	}

	private void unregister(long streamId, QuicStream stream) {
		streams.remove(streamId);
		SendPart sendPart = stream.sendPart();
		boolean freedOutstandingBudget = sendPart != null && sendPart.settleOutstandingOnRelease();
		StreamCounter counter = counterFor(streamId);
		counter.onStreamReleased();
		beginBatch();
		try {
			if (!isLocallyInitiated(streamId)) {
				// Only the peer's streams closing frees what we granted the peer. Granting on our own would
				// hand out the same credit twice, and the peer would then be free to exceed the count we
				// advertised — a limit is only as good as what is subtracted from it.
				grantStreamCountCredit(StreamIds.isBidirectional(streamId)
					? QuicStreamLimitType.BIDIRECTIONAL
					: QuicStreamLimitType.UNIDIRECTIONAL);
			}
			ReceivePart receivePart = stream.receivePart();
			if (receivePart != null && connectionFlowControl != null) {
				// Bytes charged but never taken count as consumed: the receiver is no longer holding them, and
				// not doing this would shrink the usable connection window by every released stream (FR-023).
				connectionFlowControl.onStreamReleased(
					receivePart.highestOffsetReceived(), receivePart.consumedOffset());
				// That jump in the consumed offset can be what crosses the FR-025 threshold — and if it is, no
				// later read will cross it, because this stream has no more reads to give.
				grantConnectionCredit();
			}
			if (freedOutstandingBudget) {
				// The freed budget is connection-wide, so the writer it can now fit is not necessarily one
				// of this stream's (the guard rail of FR-019: every event that can unblock a writer must
				// pump the writers it might have released).
				retryBlockedWriters();
			}
		} finally {
			endBatch();
		}
		if (inspector != null) {
			inspector.onStreamClosed(streamId);
		}
	}

	// ---------------------------------------------------------------- the two seams the halves emit through

	/** One instance, shared by every sending part; the frame's stream id is what identifies the caller. */
	private final class SendSink implements SendPart.Sink {
		@Override
		public long outstandingBytesAvailable() {
			return Math.max(0, maxOutstandingStreamBytes - outstandingStreamBytes);
		}

		@Override
		public void onOutstandingBytesChanged(long delta) {
			outstandingStreamBytes += delta;
			if (delta > 0) {
				bytesSent += delta;
			}
		}

		@Override
		public void onPendingWriteChanged(SendPart part, boolean pending) {
			if (pending) {
				pendingWriters.add(part);
			} else {
				pendingWriters.remove(part);
			}
		}

		@Override
		public void enqueueFrame(StreamFrame frame) throws QuicTransportException {
			try {
				// Takes ownership, recycling the frame itself on both of its rejection paths.
				connection.enqueueFrame(frame);
			} catch (QuicTransportException e) {
				// The send queue is full — a local fault the connection cannot survive. Posted rather than
				// closed inline: this runs from the middle of a write, and closing here would free state the
				// caller is still walking.
				reactor.post(() -> connection.closeWith(e.errorCode(), e.reasonPhrase()));
				throw e;
			}
		}

		@Override
		public void requeueFrame(StreamFrame frame) throws QuicTransportException {
			try {
				// Takes ownership, recycling the frame itself on both of its rejection paths. Unlike
				// enqueueFrame this goes to the *front* of the send queue: what is being retransmitted is
				// older than everything queued behind it, and the receiver cannot deliver a byte past the
				// gap it fills (FR-017, RFC 9002 §6.5).
				connection.requeueFrame(frame);
			} catch (QuicTransportException e) {
				// See enqueueFrame: a full send queue is a local fault the connection cannot survive, and
				// closing inline would free state the caller is still walking.
				reactor.post(() -> connection.closeWith(e.errorCode(), e.reasonPhrase()));
				throw e;
			}
		}

		@Override
		public void requestSend() {
			// Inside a batch this only records that a flush is due; the outermost endBatch performs it.
			if (batchDepth > 0) {
				sendRequested = true;
				return;
			}
			sendRequested = false;
			connection.requestSend();
		}

		@Override
		public boolean onStreamDataBlocked(long streamId, long limit) {
			// De-duplicated by the part itself: the limit belongs to one stream, so one announcer.
			timesBlockedByStreamLimit++;
			if (inspector != null) {
				inspector.onFlowControlBlocked(streamId, BlockedBy.STREAM_DATA);
			}
			return enqueueControlFrame(new StreamDataBlockedFrame(streamId, limit));
		}

		@Override
		public boolean onDataBlocked(long limit) {
			// De-duplicated here, connection-wide: every sending part shares this limit, and FR-027 counts
			// announcements of a *limit value*, not of writers waiting on it.
			if (dataBlockedAnnouncedAt == limit) return false;
			dataBlockedAnnouncedAt = limit;
			timesBlockedByConnectionLimit++;
			if (inspector != null) {
				inspector.onFlowControlBlocked(CONNECTION_LEVEL, BlockedBy.CONNECTION_DATA);
			}
			return enqueueControlFrame(new DataBlockedFrame(limit));
		}

		@Override
		public boolean enqueueReset(long streamId, long applicationErrorCode, long finalSize) {
			// Its own batch, because a reset originates from QuicStream.reset — an application call that is
			// not already inside one — and from onFrameLost, which is not either. Nested when it is, so a
			// STOP_SENDING that arrives alongside other frames still costs one datagram (research R-03).
			beginBatch();
			try {
				return enqueueControlFrame(new ResetStreamFrame(streamId, applicationErrorCode, finalSize));
			} finally {
				endBatch();
			}
		}

		@Override
		public void onReset(SendPart part) {
			streamsResetLocally++;
			if (inspector != null) {
				Long code = part.resetErrorCode();
				// Set before onReset fires, by construction: SendPart.doReset assigns it first. Defended
				// anyway, because an inspector must never be the reason a reset throws.
				inspector.onStreamReset(part.streamId(), false, code == null ? 0 : code);
			}
		}

		@Override
		public void onTerminal(SendPart part) {
			releaseIfTerminal(part.streamId());
		}
	}

	/** One instance, shared by every receiving part. */
	private final class ReceiveListener implements ReceivePart.Listener {
		@Override
		public boolean enqueueStopSending(long streamId, long applicationErrorCode) {
			// See SendSink.enqueueReset for why this owns its batch.
			beginBatch();
			try {
				return enqueueControlFrame(new StopSendingFrame(streamId, applicationErrorCode));
			} finally {
				endBatch();
			}
		}

		@Override
		public void onBytesConsumed(ReceivePart part, long bytes) {
			bytesDelivered += bytes;
			// A batch, because both grants can fire on the same read and belong in the same datagram; and
			// because this runs from the application's read, which is outside any frame handler callback.
			beginBatch();
			try {
				grantStreamCredit(part);
				if (connectionFlowControl != null) {
					connectionFlowControl.onBytesConsumed(bytes);
					grantConnectionCredit();
				}
			} finally {
				endBatch();
			}
		}

		@Override
		public void onTerminal(ReceivePart part) {
			releaseIfTerminal(part.streamId());
		}
	}

	// ---------------------------------------------------------------- accessors and counters (FR-043)

	/** The open stream with this id, or {@code null} if it is unknown or already released. */
	public @Nullable QuicStream streamOf(long streamId) {
		checkInReactorThread(this);
		return streams.get(streamId);
	}

	/**
	 * Streams that still have per-stream state: opened, not yet terminal in every half this endpoint
	 * owns, or terminal but not yet drained by the application (RFC 9000 §3, FR-006).
	 */
	public int openStreamCount() {
		checkInReactorThread(this);
		return streams.size();
	}

	/** Streams this endpoint opened itself, of either direction (RFC 9000 §2.1). */
	public long streamsOpenedLocally() {
		checkInReactorThread(this);
		return streamsOpenedLocally;
	}

	/**
	 * Streams created because the peer named them (RFC 9000 §2.1), counting every lower-numbered stream
	 * an identifier implicitly opened (FR-003) — so one frame can move this by more than one.
	 */
	public long streamsAcceptedFromPeer() {
		checkInReactorThread(this);
		return streamsAcceptedFromPeer;
	}

	/**
	 * Streams this endpoint aborted with {@code RESET_STREAM} (RFC 9000 §19.4) — counting both the ones
	 * the application aborted itself and the ones a peer's {@code STOP_SENDING} forced it to
	 * (RFC 9000 §3.5), because the frame on the wire is the same and so is what it costs. Counted once
	 * per stream, never per retransmission.
	 */
	public long streamsResetLocally() {
		checkInReactorThread(this);
		return streamsResetLocally;
	}

	/**
	 * Streams the peer aborted with {@code RESET_STREAM}. Counted once per stream: a retransmitted abort
	 * carrying the same final size is the same abort.
	 */
	public long streamsResetByPeer() {
		checkInReactorThread(this);
		return streamsResetByPeer;
	}

	/** Stream bytes the application has taken from a reader. */
	public long bytesDelivered() {
		checkInReactorThread(this);
		return bytesDelivered;
	}

	/** Stream bytes given an offset and handed to the transport; retransmission does not count twice. */
	public long bytesSent() {
		checkInReactorThread(this);
		return bytesSent;
	}

	/**
	 * How many times a writer was held by the <b>connection</b> limit, counted per {@code DATA_BLOCKED}
	 * announcement — that is, once per limit value at which anything was blocked (FR-027).
	 * <p>
	 * Which is also the count of blocking <i>episodes</i>: a writer blocked at a limit stays blocked at
	 * that limit until it rises, so distinct values and distinct episodes are the same thing. Several
	 * streams waiting on one limit are one episode, deliberately — the connection was blocked once.
	 */
	public long timesBlockedByConnectionLimit() {
		checkInReactorThread(this);
		return timesBlockedByConnectionLimit;
	}

	/**
	 * How many times a writer was held by <b>its own stream's</b> limit, counted per
	 * {@code STREAM_DATA_BLOCKED} announcement — once per limit value per stream (FR-027). Unlike
	 * {@link #timesBlockedByConnectionLimit()}, two streams blocked at once are two.
	 */
	public long timesBlockedByStreamLimit() {
		checkInReactorThread(this);
		return timesBlockedByStreamLimit;
	}

	/**
	 * How many times an open was held by a <b>stream-count</b> limit, counted per
	 * {@code STREAMS_BLOCKED} announcement — once per limit value per direction (FR-028), by the same
	 * reasoning as {@link #timesBlockedByConnectionLimit()}: several opens waiting on one limit are one
	 * blocking episode, and the two directions are counted apart because RFC 9000 §4.6 limits them apart.
	 * <p>
	 * An open withheld before the handshake completed (FR-042) is <em>not</em> counted: no limit is known
	 * yet, so there is nothing to be blocked at.
	 */
	public long timesBlockedByStreamCountLimit() {
		checkInReactorThread(this);
		return timesBlockedByStreamCountLimit;
	}

	/** Stream bytes enqueued and not yet acknowledged, bounded by {@code maxOutstandingStreamBytes}. */
	public long outstandingStreamBytes() {
		checkInReactorThread(this);
		return outstandingStreamBytes;
	}

	@Override
	public String toString() {
		return "QuicStreamManager{" + role +
			", streams=" + streams.size() +
			", sent=" + bytesSent +
			", delivered=" + bytesDelivered +
			'}';
	}
}
