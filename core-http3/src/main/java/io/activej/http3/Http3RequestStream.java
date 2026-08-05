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

package io.activej.http3;

import io.activej.bytebuf.ByteBuf;
import io.activej.bytebuf.ByteBufPool;
import io.activej.common.builder.AbstractBuilder;
import io.activej.common.recycle.Recyclers;
import io.activej.csp.consumer.AbstractChannelConsumer;
import io.activej.csp.consumer.ChannelConsumer;
import io.activej.csp.consumer.ChannelConsumers;
import io.activej.csp.supplier.AbstractChannelSupplier;
import io.activej.csp.supplier.ChannelSupplier;
import io.activej.http.HttpError;
import io.activej.http.HttpHeaders;
import io.activej.http.HttpMessage;
import io.activej.http.HttpRequest;
import io.activej.http.HttpResponse;
import io.activej.http.MalformedHttpException;
import io.activej.http3.Http3Headers.Field;
import io.activej.http3.frame.DataFrame;
import io.activej.http3.frame.HeadersFrame;
import io.activej.http3.frame.Http3Frame;
import io.activej.http3.frame.Http3FrameReader;
import io.activej.http3.frame.Http3FrameSequence;
import io.activej.http3.frame.Http3Frames;
import io.activej.http3.frame.UnknownFrame;
import io.activej.http3.qpack.QpackDecoder;
import io.activej.http3.qpack.QpackEncoder;
import io.activej.http3.qpack.QpackException;
import io.activej.http3.qpack.QpackStaticDecoder;
import io.activej.http3.qpack.QpackStaticEncoder;
import io.activej.promise.Promise;
import io.activej.promise.SettablePromise;
import io.activej.quic.codec.QuicVarInts;
import io.activej.quic.stream.QuicStream;
import io.activej.quic.stream.QuicStreamException;
import io.activej.quic.tls.EncryptionLevel;
import io.activej.reactor.AbstractReactive;
import io.activej.reactor.Reactor;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

import static io.activej.common.Utils.nullify;
import static io.activej.reactor.Reactive.checkInReactorThread;

/**
 * The HTTP/3 layer of one bidirectional QUIC stream (RFC 9114 §4.1): one request, one response, and the
 * frame sequence both must obey.
 *
 * <h2>Two roles, one state machine</h2>
 * A request stream is symmetric, and so is this class: a server calls {@link #receiveRequest()} then
 * {@link #sendResponse}, a client calls {@link #sendRequest} then {@link #receiveResponse()}. The
 * halves share every mechanism — the frame sequence, the QPACK codecs, the body channels, the abort
 * path — and differ only in which direction carries which message. Two parallel classes would have been
 * two copies of that machinery, drifting.
 *
 * <h2>What it owns</h2>
 * <ul>
 *   <li>the RFC 9114 §4.1 sequence, one {@link Http3FrameSequence} per direction — this class does not
 *       re-decide which frame may follow which, it drives that validator;</li>
 *   <li>the inbound body as an {@link AbstractChannelSupplier} that de-frames DATA off
 *       {@link QuicStream#reader()}, and the outbound body as an {@link AbstractChannelConsumer} that
 *       frames DATA onto {@link QuicStream#writer()} (FR-053, WI-3);</li>
 *   <li>the message it decodes from the peer's HEADERS and the message it is handed to send — both are
 *       released here when the exchange completes, is aborted, or the connection ends, <b>unless</b>
 *       their body was taken, in which case ownership went with it (FR-057a). The one message that
 *       leaves this ownership is the {@link HttpResponse} {@link #receiveResponse()} delivers: a client
 *       hands it to its caller, and the caller owns it from that moment.</li>
 * </ul>
 *
 * <h2>State</h2>
 * <pre>{@code
 * IDLE ──HEADERS──► HEADERS_DONE ──DATA──► BODY        (repeatable; a zero-length DATA is legal)
 * HEADERS_DONE | BODY ──trailing HEADERS──► TRAILERS_DONE
 * any ──FIN──► COMPLETE
 * any ──RESET_STREAM / STOP_SENDING / local H3 error──► RESET
 * }</pre>
 * The state tracks the <b>receive</b> direction plus the two terminal states, because that is the
 * direction a peer drives and therefore the one worth reporting.
 *
 * <h2>Failures</h2>
 * A violation of the <i>message</i> aborts <i>this stream</i> with its RFC 9114 §8.1 code and leaves the
 * connection alone (FR-037): a malformed message is one client's problem, not the connection's. A
 * violation of the <i>framing</i> is not — a PUSH_PROMISE against a push limit of 0
 * ({@code H3_ID_ERROR}) and every frame RFC 9114 §7.2's table does not permit on a request stream
 * ({@code H3_FRAME_UNEXPECTED}) are reported to the
 * {@linkplain Builder#withConnectionErrorListener connection-error listener} as well, and close the
 * connection (FR-024, FR-025); see {@link #isConnectionError}. A
 * failure the stream layer reports — {@code QuicStreamResetException},
 * {@code QuicStreamStopSendingException} — reaches the caller <b>unwrapped</b>, carrying the peer's own
 * application error code (FR-058c); wrapping it would hide exactly the code the peer chose to send.
 *
 * <h2>Buffer ownership</h2>
 * Every buffer taken from {@link QuicStream#reader()} is recycled here or handed to the body supplier's
 * consumer, which then owns it. Every buffer given to {@link QuicStream#writer()} is owned by the
 * writer on every path, failures included. Nothing is buffered between HTTP and QUIC: the response body
 * consumer propagates the writer's own promise, so backpressure is QUIC's stream flow control (FR-056).
 *
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9114#section-4.1">RFC 9114 §4.1 — HTTP Message Framing</a>
 */
public final class Http3RequestStream extends AbstractReactive {
	private static final Logger logger = LoggerFactory.getLogger(Http3RequestStream.class);

	/** {@link #declaredContentLength} when the message declared none. */
	private static final long NO_CONTENT_LENGTH = -1;

	/**
	 * RFC 8470 §5.1's request field and its one legal value, in the lowercase spelling RFC 9114 §4.1.1
	 * requires on the wire — attached to a request accepted from early data (spec FR-066).
	 */
	private static final String EARLY_DATA_FIELD = "early-data";
	private static final String EARLY_DATA_INDICATION = "1";

	/** RFC 8470 §5.2's status for a request refused because it arrived in early data. */
	private static final int TOO_EARLY = 425;

	public enum State {
		/** Nothing has been received on this stream yet. */
		IDLE,
		/** The leading HEADERS frame has been decoded into a message. */
		HEADERS_DONE,
		/** At least one DATA frame has been received since the leading HEADERS. */
		BODY,
		/** The optional trailing HEADERS frame has been received. */
		TRAILERS_DONE,
		/** The peer FINed the stream and everything it sent has been accounted for. Terminal. */
		COMPLETE,
		/** Aborted, by the peer or locally. Terminal. */
		RESET,
	}

	private final QuicStream stream;

	private Http3Settings settings = Http3Settings.create();
	private QpackEncoder qpackEncoder = new QpackStaticEncoder();
	private @Nullable QpackDecoder qpackDecoder;
	private @Nullable Http3FieldSectionDecoder fieldSectionDecoder;
	private Consumer<Http3Exception> connectionErrorListener = e -> {};
	private Http3EventListener eventListener = Http3EventListener.NONE;

	/**
	 * Default-deny, and deliberately defaulted <b>here</b> rather than only on {@link Http3Server}: this
	 * is the innermost layer a request passes through, so a consumer that wires an
	 * {@link Http3Connection} by hand gets FR-064's protection without asking for it.
	 */
	private Http3EarlyDataPolicy earlyDataPolicy = Http3EarlyDataPolicy.DEFAULT_POLICY;

	private final Http3FrameSequence inbound = new Http3FrameSequence();
	private final Http3FrameSequence outbound = new Http3FrameSequence();
	private @Nullable Http3FrameReader frameReader;
	private @Nullable ChannelSupplier<ByteBuf> reader;

	private State state = State.IDLE;
	private boolean inboundRequested;
	private boolean outboundSent;

	/**
	 * Applied to every failure the inbound body channel reports. Identity for a received <b>request</b>
	 * (a server has no caller-facing promise to shape); {@link #clientVisible} for a received
	 * <b>response</b>, which only a client ever builds.
	 */
	private UnaryOperator<Exception> inboundFailureMapper = UnaryOperator.identity();

	/** Whatever of the last read remains undecoded; owned here and recycled on every terminal path. */
	private @Nullable ByteBuf pendingInput;

	/** Whether the frame reader stopped inside a frame — what makes an end of input a truncation. */
	private boolean midFrame;
	private boolean endOfInput;

	/** The read {@link #watchWhileBlocked()} armed, or {@code null}; never more than one at a time. */
	private @Nullable Promise<ByteBuf> blockedRead;

	/** That watch reached end of input while a section was still held; {@link #nextFrame()} finishes it. */
	private boolean blockedEndOfInput;

	private long bodyBytesReceived;
	private long bodyBytesSent;
	private long declaredContentLength = NO_CONTENT_LENGTH;

	/** Informational (1xx) responses consumed on this exchange, against {@code maxInterimResponses}. */
	private int interimResponsesReceived;

	/** Whatever this stream decoded from the peer's HEADERS; kept for its trailers even once handed over. */
	private @Nullable HttpMessage inboundMessage;

	/** Latched by {@link #readHeaders} off the transport; see {@link #headersArrivalLevel()}. */
	private @Nullable EncryptionLevel headersArrivalLevel;

	/**
	 * True once {@link #receiveResponse()} delivered the inbound message to a caller who now owns it, so
	 * this stream no longer releases it — the one place ownership leaves here (FR-057a).
	 */
	private boolean inboundHandedOver;

	/**
	 * True once the inbound body supplier has been asked for a chunk — what tells a message somebody is
	 * reading from one nobody ever touched. See {@link #isReceivingBody()}.
	 */
	private boolean bodyRequested;

	/** {@link #whenReceiveComplete()}'s promise; allocated only if somebody asks for it. */
	private @Nullable SettablePromise<Void> receiveComplete;

	private @Nullable InboundBodySupplier bodySupplier;
	private @Nullable Exception terminalException;

	/**
	 * The RFC 9297 datagram handle bound to this exchange, or {@code null} when
	 * {@link Http3Settings#datagramsEnabled()} is off — which is the default, and which is why an ordinary
	 * exchange allocates no datagram queue at all (FR-086).
	 */
	private @Nullable Http3DatagramChannel datagramChannel;

	private Http3RequestStream(Reactor reactor, QuicStream stream) {
		super(reactor);
		this.stream = stream;
	}

	public static Builder builder(Reactor reactor, QuicStream stream) {
		return new Http3RequestStream(reactor, stream).new Builder();
	}

	public static Http3RequestStream create(Reactor reactor, QuicStream stream) {
		return builder(reactor, stream).build();
	}

	public final class Builder extends AbstractBuilder<Builder, Http3RequestStream> {
		private Builder() {}

		public Builder withSettings(Http3Settings settings) {
			checkNotBuilt(this);
			Http3RequestStream.this.settings = settings;
			return this;
		}

		/** The connection's encoder — QPACK state is per connection, never per stream (RFC 9204 §4.2). */
		public Builder withQpackEncoder(QpackEncoder qpackEncoder) {
			checkNotBuilt(this);
			Http3RequestStream.this.qpackEncoder = qpackEncoder;
			return this;
		}

		/** The connection's decoder; one bounded by {@code settings} is built if none is supplied. */
		public Builder withQpackDecoder(QpackDecoder qpackDecoder) {
			checkNotBuilt(this);
			Http3RequestStream.this.qpackDecoder = qpackDecoder;
			return this;
		}

		/**
		 * The seam a connection that can <b>hold</b> a blocked field section uses instead of
		 * {@link #withQpackDecoder} (US2, FR-033): its promise stays pending while the section waits for
		 * an insertion the peer's encoder stream has not sent yet.
		 * <p>
		 * Package-private, and it supersedes {@link #withQpackDecoder} when both are given — a connection
		 * with a dynamic table lends both halves of the same decoder, and only this one can wait.
		 */
		Builder withQpackFieldSectionDecoder(Http3FieldSectionDecoder fieldSectionDecoder) {
			checkNotBuilt(this);
			Http3RequestStream.this.fieldSectionDecoder = fieldSectionDecoder;
			return this;
		}

		/**
		 * What this stream is willing to run from early data (FR-064, FR-065), consulted only for an
		 * exchange whose leading HEADERS arrived at {@code ZERO_RTT}. Defaults to
		 * {@link Http3EarlyDataPolicy#DEFAULT_POLICY} — RFC 9110 §9.2.1 safe methods and nothing else.
		 */
		public Builder withEarlyDataPolicy(Http3EarlyDataPolicy earlyDataPolicy) {
			checkNotBuilt(this);
			Http3RequestStream.this.earlyDataPolicy = earlyDataPolicy;
			return this;
		}

		/**
		 * Where a violation this stream observes but the <b>connection</b> owns is reported — see
		 * {@link #isConnectionError}. The listener is invoked after the stream has been aborted with the
		 * same code, so an owner that closes the connection finds nothing half-done.
		 */
		public Builder withConnectionErrorListener(Consumer<Http3Exception> listener) {
			checkNotBuilt(this);
			Http3RequestStream.this.connectionErrorListener = listener;
			return this;
		}

		/**
		 * The connection's observability seam, so a reset or a discarded frame on this stream reaches the
		 * {@link Http3Server.Inspector} or {@link Http3Client.Inspector} above it (FR-062). Package-private
		 * for the reason {@link Http3EventListener} gives.
		 */
		Builder withEventListener(Http3EventListener eventListener) {
			checkNotBuilt(this);
			Http3RequestStream.this.eventListener = eventListener;
			return this;
		}

		/**
		 * The RFC 9297 datagram handle bound to this exchange, set by {@code Http3Connection} only when
		 * {@link Http3Settings#datagramsEnabled()}. Package-private, because only a connection can bind a
		 * transport to a request stream.
		 * <p>
		 * The ownership split is that the <b>channel</b> owns the queue — it is what an application polls —
		 * and this <b>stream</b> owns the channel's lifecycle, because it is what knows when the exchange
		 * ends.
		 */
		Builder withDatagramChannel(Http3DatagramChannel datagramChannel) {
			checkNotBuilt(this);
			Http3RequestStream.this.datagramChannel = datagramChannel;
			return this;
		}

		@Override
		protected Http3RequestStream doBuild() {
			if (qpackDecoder == null) {
				qpackDecoder = new QpackStaticDecoder(settings.maxFieldSectionSize());
			}
			if (fieldSectionDecoder == null) {
				// A decoder that cannot wait is one whose answer is always already in hand; it owns and
				// recycles its input on every path, this throw included, so nothing here recycles after it.
				QpackDecoder decoder = qpackDecoder;
				fieldSectionDecoder = section -> {
					try {
						return Promise.of(decoder.decode(section));
					} catch (QpackException e) {
						return Promise.ofException(e);
					}
				};
			}
			// Each frame type is bounded by what this endpoint accepts of that type, at the moment its
			// declared length parses and before a byte of its payload is allocated — the field section to
			// maxFieldSectionSize, the body to maxBodySize. One bound covering both would have to be the
			// wider of them, and a HEADERS frame is buffered whole: a peer would buy a body's worth of
			// allocation per stream with a frame header it never follows through on (T116).
			frameReader = new Http3FrameReader(
				settings.maxFieldSectionSize(), settings.maxBodySize(), Http3Errors.H3_EXCESSIVE_LOAD);
			return Http3RequestStream.this;
		}
	}

	// ---------------------------------------------------------------- accessors

	public long id() {
		checkInReactorThread(this);
		return stream.id();
	}

	public State state() {
		checkInReactorThread(this);
		return state;
	}

	/** The stream this exchange runs on, for the connection that owns both. */
	public QuicStream quicStream() {
		checkInReactorThread(this);
		return stream;
	}

	// ---------------------------------------------------------------- HTTP/3 datagrams (RFC 9297)

	/**
	 * Whether an inbound HTTP/3 datagram routed here would be delivered rather than dropped: this exchange
	 * has a channel and has not ended.
	 * <p>
	 * Read by {@code Http3Connection} <b>before</b> it slices the borrowed DATAGRAM frame, so a datagram for
	 * a finished exchange — normal on an unreliable channel, FR-082 — costs no allocation.
	 */
	boolean acceptsDatagrams() {
		Http3DatagramChannel channel = datagramChannel;
		return channel != null && !channel.isClosed();
	}

	/** One inbound HTTP/3 datagram for this exchange; <b>ownership passes in</b> on every path. */
	void onDatagram(ByteBuf owned) {
		checkInReactorThread(this);
		Http3DatagramChannel channel = datagramChannel;
		if (channel == null) {
			owned.recycle();
			return;
		}
		channel.onDatagram(owned);
	}

	/**
	 * The exchange has ended: whatever it never polled is drained and recycled (FR-085), and nothing
	 * further is accepted or sent. Idempotent.
	 * <p>
	 * Reached from the continuation {@code Http3Connection} registers on {@code QuicStream.whenClosed()},
	 * which covers every terminal path of one exchange — a clean completion, a local or peer abort, and an
	 * early-data discard ({@code QuicStreamManager.discardStream} fails {@code whenClosed()} rather than
	 * completing it) — and, separately, from the connection's own close. That second site is not redundant:
	 * a locally-aborted stream closes only once the peer has answered its {@code RESET_STREAM}, which is
	 * after the connection that was waiting for it is gone.
	 * <p>
	 * Deliberately <b>not</b> called from {@link #receiveFinished()}: the peer FINing the receive direction
	 * is not the exchange ending, and an application that has not polled yet would lose datagrams it is
	 * still owed.
	 */
	void closeDatagrams() {
		checkInReactorThread(this);
		Http3DatagramChannel channel = datagramChannel;
		if (channel != null) channel.close();
	}

	/**
	 * Binds this exchange's datagram channel to {@code message}, so a caller reaches it through
	 * {@link Http3Datagrams#of} on the message it already holds (FR-084) — a servlet on the request it is
	 * serving, a client on the request it issued and on the response it received.
	 */
	private void attachDatagrams(HttpMessage message) {
		Http3DatagramChannel channel = datagramChannel;
		if (channel != null) Http3Datagrams.set(message, channel);
	}

	/** Whether this exchange has reached {@link State#COMPLETE} or {@link State#RESET}. */
	public boolean isTerminated() {
		checkInReactorThread(this);
		return state == State.RESET || state == State.COMPLETE;
	}

	/**
	 * Whether this exchange was opened while its bytes would have left in a 0-RTT packet — which is to
	 * say, whether it is at risk if the server refuses the early data (spec FR-055).
	 * <p>
	 * Latched by the transport at the moment the stream was created and never revoked, so it stays true
	 * after the handshake completes: it records where this exchange came from, not where it is now.
	 */
	public boolean isEarlyData() {
		checkInReactorThread(this);
		return stream.isEarlyData();
	}

	/**
	 * The {@link EncryptionLevel} this exchange's <b>leading HEADERS</b> arrived at (spec FR-064a) —
	 * {@code ZERO_RTT} for a message a peer sent in early data, {@code ONE_RTT} for an ordinary one, and
	 * {@code null} until the HEADERS frame is in hand.
	 * <p>
	 * Latched at the moment that frame is read rather than derived on demand, because a stream outlives
	 * its head: DATA keeps arriving afterwards, at a level of its own, and an early-data policy asked
	 * about a request must be told where the <em>request</em> came from, not where the last body chunk
	 * did. It is the receiving counterpart of {@link #isEarlyData()}, which reports the sending side and
	 * is therefore always {@code false} on a server.
	 *
	 * @see <a href="https://www.rfc-editor.org/rfc/rfc8470">RFC 8470 — Using Early Data in HTTP</a>
	 */
	public @Nullable EncryptionLevel headersArrivalLevel() {
		checkInReactorThread(this);
		return headersArrivalLevel;
	}

	/**
	 * Whether a consumer is still reading the message this stream received: its body supplier has been
	 * asked for at least one chunk, and the receive direction has neither finished nor been aborted.
	 * <p>
	 * This is the distinction {@link Http3Client}'s pool needs (FR-049): an exchange ends at the response
	 * <b>head</b>, so a connection with no exchange left on it may still be streaming a body into a
	 * caller's hands, and evicting it would sever exactly that. A message nobody ever began reading is
	 * deliberately <b>not</b> reported here — nothing of it is in transfer, and it produces no completion
	 * event of its own either, since only a read can reach the end of the stream.
	 */
	public boolean isReceivingBody() {
		checkInReactorThread(this);
		return bodyRequested && state != State.COMPLETE && state != State.RESET;
	}

	/**
	 * Completes once this stream stops receiving — the peer FINed it and everything it sent was accounted
	 * for, or either side aborted it. It never fails: what it reports is that nothing more will arrive
	 * here, not whether what arrived was whole, which is {@link #terminalException()}'s answer.
	 * <p>
	 * A message a consumer never reads reaches neither of those, so this is not the whole of "is this
	 * stream still busy"; {@link #isReceivingBody()} is the other half of it.
	 */
	public Promise<Void> whenReceiveComplete() {
		checkInReactorThread(this);
		if (state == State.COMPLETE || state == State.RESET) return Promise.complete();
		if (receiveComplete == null) receiveComplete = new SettablePromise<>();
		return receiveComplete;
	}

	/** Why this exchange was aborted, or {@code null} if it was not. */
	public @Nullable Exception terminalException() {
		checkInReactorThread(this);
		return terminalException;
	}

	/** DATA-frame payload bytes taken off this stream so far — body bytes only, framing excluded. */
	public long bodyBytesReceived() {
		checkInReactorThread(this);
		return bodyBytesReceived;
	}

	/** DATA-frame payload bytes handed to this stream's writer so far — body bytes only, framing excluded. */
	public long bodyBytesSent() {
		checkInReactorThread(this);
		return bodyBytesSent;
	}

	// ---------------------------------------------------------------- receiving a message

	/**
	 * Reads the leading HEADERS frame and maps it to an {@link HttpRequest} whose body streams the DATA
	 * frames that follow — the server half. May be called once.
	 * <p>
	 * The returned message is owned by this stream (FR-057a): it is released by {@link #sendResponse} or
	 * by {@link #abort}, so a servlet that never touches the body leaks nothing.
	 * <p>
	 * A request that arrived in <b>early data</b> is screened by the {@linkplain
	 * Builder#withEarlyDataPolicy early-data policy} before it is handed over (FR-064), so a caller
	 * cannot dispatch what the policy refused — the request never leaves this stream. Such a refusal
	 * fails this promise with an {@link HttpError} of code {@code 425}, leaving the stream <b>intact</b>
	 * rather than aborted: the exchange still owes the peer RFC 8470 §5.2's answer, and
	 * {@link #sendResponse} is what writes it.
	 *
	 * @return a promise failing with {@link Http3Exception} on an H3 protocol violation — the stream is
	 * aborted with its code first — or, unwrapped, with whatever the stream layer reported (FR-058c)
	 */
	public Promise<HttpRequest> receiveRequest() {
		checkInReactorThread(this);
		beginReceive("request");
		if (terminalException != null) return Promise.ofException(terminalException);
		// RFC 9114 §4.1: a stream that ends before a complete request is an incomplete request, not a
		// malformed one — the difference is whether the peer sent something wrong.
		return readHeaders(this::buildRequest, Http3Errors.H3_REQUEST_INCOMPLETE)
			.then(this::screenEarlyData);
	}

	/**
	 * FR-064's gate: the one place a request built from a 0-RTT flight becomes a request somebody may
	 * dispatch. It sits after the message mapping and before the promise resolves, which is what makes
	 * "the servlet is never invoked for a refused request" structural rather than a caller's obligation
	 * — there is no request to invoke it with.
	 * <p>
	 * An ordinary 1-RTT request never reaches the policy at all: the level is the exchange's own
	 * ({@link #headersArrivalLevel()}), not the connection's, so a request held back by a client on a
	 * connection that <i>did</i> accept early data is judged as what it is.
	 * <p>
	 * A policy that throws is a refusal. Failing open on a consumer's bug would turn it into a replay
	 * vector, which is precisely the thing this method exists to prevent.
	 */
	private Promise<HttpRequest> screenEarlyData(HttpRequest request) {
		if (headersArrivalLevel != EncryptionLevel.ZERO_RTT) return Promise.of(request);
		boolean accepted;
		try {
			accepted = earlyDataPolicy.acceptsInEarlyData(request);
		} catch (RuntimeException e) {
			logger.warn("The early-data policy failed on stream {}; refusing the request", stream.id(), e);
			accepted = false;
		}
		if (accepted) return Promise.of(request);
		logger.trace("HTTP/3 request stream {} refused: the early-data policy declined it", stream.id());
		// The request stays this stream's (FR-057a) — sendResponse or abort releases it, exactly as for a
		// request a servlet did receive.
		return Promise.ofException(HttpError.ofCode(TOO_EARLY,
			"The request arrived in early data and the early-data policy refused it (RFC 8470)"));
	}

	/**
	 * Reads the leading HEADERS frame and maps it to an {@link HttpResponse} whose body streams the DATA
	 * frames that follow — the client half of {@link #receiveRequest()}. May be called once.
	 * <p>
	 * <b>Ownership</b>: unlike the request a server receives, the response this delivers <b>leaves</b>
	 * this stream's ownership the moment the promise resolves — a client hands it to its caller, who then
	 * owns the message and whatever {@code loadBody()} produces from it. Until then, and on every failure
	 * path, it is released here like any other message this stream built.
	 *
	 * @return a promise failing as {@link #receiveRequest()} does
	 */
	public Promise<HttpResponse> receiveResponse() {
		checkInReactorThread(this);
		beginReceive("response");
		if (terminalException != null) return Promise.ofException(terminalException);
		// RFC 9114 §4.1.2: a response that is not fully formed is a malformed message, and every malformed
		// message is a stream error of type H3_MESSAGE_ERROR. H3_REQUEST_INCOMPLETE names a *client's*
		// stream and would misreport the direction.
		return readHeaders(this::buildResponse, Http3Errors.H3_MESSAGE_ERROR)
			.whenResult($ -> inboundHandedOver = true);
	}

	private void beginReceive(String what) {
		if (inboundRequested) {
			throw new IllegalStateException(
				"The " + what + " of stream " + stream.id() + " has already been requested");
		}
		inboundRequested = true;
	}

	/**
	 * Builds the inbound message from the leading HEADERS frame, taking ownership of it on every path.
	 * <p>
	 * Returns {@code null} for a field section that is <b>not</b> the message — an informational
	 * ({@code 1xx}) response, the one case RFC 9114 §4.1 has a HEADERS frame open nothing — which tells
	 * {@link #readHeaders} to consume it and keep reading.
	 */
	@FunctionalInterface
	private interface InboundMessage<T extends HttpMessage> {
		Promise<T> build(HeadersFrame headers);
	}

	private <T extends HttpMessage> Promise<T> readHeaders(InboundMessage<T> message, long incompleteErrorCode) {
		return nextFrame().then(frame -> {
			if (frame == null) {
				return Promise.ofException(abortWith(new Http3Exception(incompleteErrorCode,
					"The stream ended before its HEADERS frame")));
			}
			if (!(frame instanceof HeadersFrame headers)) {
				// Unreachable rather than tolerated: an unknown or GREASE type never leaves nextFrame()
				// (RFC 9114 §9, see its Javadoc), and DATA in IDLE is already H3_FRAME_UNEXPECTED from the
				// sequence validator. Stated as a failure rather than a skip so this can never become a
				// loop driven by what a peer sends.
				Recyclers.recycle(frame);
				return Promise.ofException(abortWith(new Http3Exception(Http3Errors.H3_FRAME_UNEXPECTED,
					"A frame other than HEADERS opened the message")));
			}
			// Latched off the transport before the field section is decoded, and only for the frame that
			// opens the message: an interim (1xx) response recurses through here, and the level this
			// exchange is judged by is the one its head arrived at (FR-064a).
			if (headersArrivalLevel == null) headersArrivalLevel = stream.arrivalLevel();
			return message.build(headers).then(
				built -> built == null ?
					// An informational response: consumed, and the message it precedes is still ahead.
					// Bounded recursion — buildResponse refuses more than settings.maxInterimResponses() of
					// them, which is what stops a server from making its 1xx count this thread's stack depth.
					readHeaders(message, incompleteErrorCode) :
					Promise.of(built),
				e -> Promise.ofException(abortWith(e)));
		});
	}

	/** Takes ownership of {@code headers} on every path. */
	private Promise<HttpRequest> buildRequest(HeadersFrame headers) {
		return decodeFieldSection(headers).map(fields -> {
			if (headersArrivalLevel == EncryptionLevel.ZERO_RTT) markEarlyData(fields);
			HttpRequest.Builder builder = Http3Headers.toRequestBuilder(fields);

			bodySupplier = new InboundBodySupplier();
			builder.withBodyStream(bodySupplier);
			builder.withMaxBodySize((int) Math.min(Integer.MAX_VALUE, settings.maxBodySize()));
			// Assigned before the last validation step, so that a rejection releases the message through the
			// one path that owns it rather than through a second, parallel one.
			HttpRequest request = builder.build();
			inboundMessage = request;
			attachDatagrams(request);
			readDeclaredContentLength(request);
			state = State.HEADERS_DONE;
			return request;
		});
	}

	/**
	 * Takes ownership of {@code headers} on every path.
	 *
	 * @return a promise of {@code null} for an informational ({@code 1xx}) response, which RFC 9114 §4.1
	 * has the client consume before reading on for the final one — see {@link InboundMessage#build}
	 */
	private Promise<HttpResponse> buildResponse(HeadersFrame headers) {
		return decodeFieldSection(headers).map(fields -> {
			if (Http3Headers.isInformationalStatus(fields)) {
				Http3Headers.validateInterimResponse(fields);
				if (++interimResponsesReceived > settings.maxInterimResponses()) {
					throw new Http3Exception(Http3Errors.H3_EXCESSIVE_LOAD,
						"More than " + settings.maxInterimResponses() + " informational responses on one exchange");
				}
				// The grammar has to be told, because it only ever sees a frame type: this HEADERS opened
				// nothing, and the next one is the message rather than its trailer section.
				inbound.withdrawInformationalHeaders();
				return null;
			}
			HttpResponse.Builder builder = Http3Headers.toResponseBuilder(fields);

			bodySupplier = new InboundBodySupplier();
			// Only a client receives a response, so this is the client-facing body channel by construction —
			// no role flag is needed to know that its failures are the ones FR-047 speaks about.
			inboundFailureMapper = Http3RequestStream::clientVisible;
			builder.withBodyStream(bodySupplier);
			builder.withMaxBodySize((int) Math.min(Integer.MAX_VALUE, settings.maxBodySize()));
			HttpResponse response = builder.build();
			inboundMessage = response;
			attachDatagrams(response);
			readDeclaredContentLength(response);
			state = State.HEADERS_DONE;
			return response;
		});
	}

	/**
	 * FR-066: states in the message itself that this request arrived in early data, so a servlet can
	 * apply its own rule on top of the deployment's — and so can the policy, which sees the same fields.
	 * <p>
	 * <b>Replaces</b> rather than appends, because the indication a servlet reads must be this server's
	 * verdict and not a peer's claim: a client is free to send an {@code Early-Data} field of its own,
	 * and a second field line beside ours would leave the servlet reading whichever came first. A
	 * request arriving at any other level is left exactly as the peer sent it — RFC 8470 §5.1 has
	 * intermediaries add this field, and removing one there would drop a hop's own statement.
	 */
	private static void markEarlyData(List<Field> fields) {
		fields.removeIf(field -> field.name().equals(EARLY_DATA_FIELD));
		fields.add(new Field(EARLY_DATA_FIELD, EARLY_DATA_INDICATION));
	}

	/** FR-039: what the message says its body will weigh, reconciled against the DATA at end of input. */
	private void readDeclaredContentLength(HttpMessage message) throws Http3Exception {
		String contentLength = message.getHeader(HttpHeaders.CONTENT_LENGTH);
		if (contentLength == null) return;
		try {
			declaredContentLength = Long.parseLong(contentLength.trim());
		} catch (NumberFormatException e) {
			throw new Http3Exception(Http3Errors.H3_MESSAGE_ERROR, "Content-Length is not a number");
		}
		if (declaredContentLength < 0) {
			throw new Http3Exception(Http3Errors.H3_MESSAGE_ERROR, "Content-Length is negative");
		}
	}

	/**
	 * Bounds the encoded field section before decoding it (FR-030), then hands the payload to the QPACK
	 * decoder, <b>which owns and recycles it on every path</b>.
	 * <p>
	 * The pre-decode bound is what keeps {@code QpackBlockedSections}' derived byte bound
	 * ({@code blocked streams × maxFieldSectionSize}) sound: no section reaches the decoder — and so
	 * none is ever held — above the size that product is computed from.
	 * <p>
	 * The promise stays <b>pending</b> while the connection holds the section on an insertion the peer's
	 * encoder stream has not sent yet (FR-033). This stream issues no second decode in the meantime and
	 * does not even read the frame behind it, which is the structural half of FR-037's per-stream arrival
	 * order; {@code QpackBlockedSections.release} draining each stream from its head is the other.
	 */
	private Promise<List<Field>> decodeFieldSection(HeadersFrame headers) {
		int encodedLength = headers.fieldSection.readRemaining();
		if (encodedLength > settings.maxFieldSectionSize()) {
			headers.recycle();
			return Promise.ofException(new Http3Exception(Http3Errors.H3_EXCESSIVE_LOAD,
				"A field section of " + encodedLength + " bytes exceeds the " +
				settings.maxFieldSectionSize() + "-byte bound"));
		}
		Promise<List<Field>> decoded = fieldSectionDecoder.decode(headers.fieldSection)
			.mapException(Http3RequestStream::qpackFailure)
			.map(Http3Headers::fromQpack);
		if (!decoded.isComplete()) watchWhileBlocked();
		return decoded;
	}

	/**
	 * The QPACK codes are a space of their own (RFC 9204 §6); the code the decoder chose is the code the
	 * abort carries. The <i>scope</i>, however, does not follow from the code — RFC 9204 assigns it per
	 * cause while every cause shares {@code QPACK_DECOMPRESSION_FAILED} — so the decoder's verdict is
	 * carried across rather than re-derived here.
	 */
	private static Exception qpackFailure(Exception e) {
		if (!(e instanceof QpackException qpack)) return e;
		return qpack.isConnectionError() ?
			Http3Exception.connectionScoped(qpack.errorCode(), qpack.getMessage()) :
			new Http3Exception(qpack.errorCode(), qpack.getMessage());
	}

	/**
	 * Arms the one read that exists while a field section of this stream is held (FR-025).
	 * <p>
	 * With no frame being read there is nothing else here to observe a peer's {@code RESET_STREAM}: the
	 * QUIC receive half fails a <i>parked</i> read and closes nothing when none is parked, so a stream
	 * abandoned while blocked would wait out the blocked-section timeout and take the whole connection
	 * with it. Exactly one read is armed and whatever it yields is parked in {@link #pendingInput} — a
	 * one-slice watch rather than a read-ahead, since reading on would refresh the stream's flow-control
	 * window and let a peer buffer a body's worth here behind a section it never unblocks.
	 */
	private void watchWhileBlocked() {
		if (blockedRead != null || pendingInput != null || endOfInput || blockedEndOfInput) return;
		if (state == State.RESET || state == State.COMPLETE) return;
		Promise<ByteBuf> armed = reader().get();
		blockedRead = armed;
		armed.whenComplete((buf, e) -> {
			blockedRead = null;
			if (e != null) {
				onReadFailure(e);
				return;
			}
			if (buf == null) {
				// Not endOfInput itself: onEndOfInput() reconciles Content-Length and finishes the receive
				// direction, and neither may happen while the message this stream carries is still blocked.
				blockedEndOfInput = true;
				return;
			}
			if (state == State.RESET) {
				buf.recycle();
				return;
			}
			pendingInput = buf;
		});
	}

	// ---------------------------------------------------------------- sending a message

	/**
	 * Writes {@code response} as a HEADERS frame, its body as DATA frames, and FIN after the last of
	 * them (FR-043) — the server half.
	 * <p>
	 * Takes ownership of {@code response} — and releases the {@link HttpRequest} this stream built, the
	 * moment the servlet's answer is in hand, exactly as {@code HttpServerConnection} does. On an
	 * already-aborted stream the response is released and the promise fails with the abort's own
	 * exception, so a servlet that answers a request nobody is waiting for still leaks nothing.
	 */
	public Promise<Void> sendResponse(HttpResponse response) {
		checkInReactorThread(this);
		beginSend(response, "response");
		// The servlet's answer is in hand, so nothing more will be asked of the request it answers.
		releaseInbound();
		return sendMessage(response, Http3Headers.fromResponse(response));
	}

	/**
	 * Writes {@code request} as a HEADERS frame, its body as DATA frames, and FIN after the last of them
	 * — the client half of {@link #sendResponse}. May be called once.
	 * <p>
	 * Takes ownership of {@code request} on every path, an already-aborted stream included, so a caller
	 * that races a reset never has to work out which of them owed the body a release.
	 */
	public Promise<Void> sendRequest(HttpRequest request) {
		checkInReactorThread(this);
		beginSend(request, "request");
		attachDatagrams(request);
		return sendMessage(request, Http3Headers.fromRequest(request));
	}

	private void beginSend(HttpMessage message, String what) {
		if (outboundSent) {
			releaseMessage(message);
			throw new IllegalStateException("A " + what + " has already been sent on stream " + stream.id());
		}
		outboundSent = true;
	}

	/** Takes ownership of {@code message} on every path; {@code fields} is its already-mapped field list. */
	private Promise<Void> sendMessage(HttpMessage message, List<Field> fields) {
		if (terminalException != null) {
			releaseMessage(message);
			return Promise.ofException(terminalException);
		}

		if (message.hasBody() && !message.hasHeader(HttpHeaders.CONTENT_LENGTH)) {
			// The length is known exactly here, and stating it keeps an HTTP/3 message the same message an
			// HTTP/1.1 one would have been (SC-013). A streamed body has no length to state.
			fields.add(new Field("content-length", Integer.toString(message.getBody().readRemaining())));
		}
		ChannelSupplier<ByteBuf> body = takeBodyOrNull(message);

		ByteBuf headersFrame;
		try {
			outbound.accept(HeadersFrame.TYPE);
			headersFrame = encodeHeadersFrame(fields);
		} catch (Http3Exception e) {
			if (body != null) body.streamTo(ChannelConsumers.recycling());
			return Promise.ofException(abortWith(e));
		}

		ChannelConsumer<ByteBuf> writer = stream.writer();
		return writer.accept(headersFrame)
			.then(
				$ -> body == null ?
					writer.accept(null) :
					body.streamTo(new OutboundBodyConsumer(writer)),
				// The body never reached a consumer that would own it, so this is the path that owes it a
				// release — the one every abort of a stream mid-message takes.
				e -> {
					if (body != null) body.streamTo(ChannelConsumers.recycling());
					return Promise.ofException(e);
				})
			.whenException(this::onWriteFailure);
	}

	/**
	 * Aborts this exchange with an RFC 9114 §8.1 code: both halves of the stream are aborted, every
	 * buffer this stream owns is recycled and every pending promise fails. Idempotent.
	 */
	public void abort(long errorCode, String reason) {
		checkInReactorThread(this);
		abortWith(new Http3Exception(errorCode, reason));
	}

	/**
	 * Abandons this exchange <b>silently</b> because the server refused the early data it went out in
	 * (spec FR-055): every buffer this stream owns is recycled and every pending promise fails, but
	 * <b>nothing is put on the wire</b> — no {@code RESET_STREAM}, no {@code STOP_SENDING}, and no
	 * {@code Stream Cancellation} on the QPACK decoder stream. The peer dropped this stream's 0-RTT
	 * packets undecrypted and has never heard of it; every one of those frames would be the first it
	 * ever did hear, and would open a request stream at the server for nothing.
	 * <p>
	 * The state is moved <b>before</b> anything is failed, and that ordering is load-bearing: the QUIC
	 * stream is discarded by the caller immediately afterwards, which fails whatever read or write is
	 * parked on it, and those continuations re-enter here. Finding this stream already terminal is what
	 * makes each of them a no-op rather than a second abort with a different exception.
	 * <p>
	 * Idempotent. Only {@link Http3Connection} calls it, and only as one half of its own discard —
	 * this leaves the QUIC stream alive, which alone would strand it.
	 */
	void discardEarlyData(Exception cause) {
		checkInReactorThread(this);
		if (state == State.RESET) return;
		state = State.RESET;
		terminalException = cause;
		pendingInput = nullify(pendingInput, ByteBuf::recycle);
		// A stream abandoned part-way through a frame leaves its reader holding the payload it had begun
		// filling; nothing will ever finish that frame, so this is the path that owes it a release (DI-1).
		frameReader.recycle();
		bodySupplier = nullify(bodySupplier, supplier -> supplier.closeEx(cause));
		releaseInbound();
		logger.trace("HTTP/3 request stream {} discarded: its early data was refused", stream.id());
		receiveFinished();
	}

	// ---------------------------------------------------------------- reading frames

	/**
	 * The next frame the peer sent <b>that this layer has semantics for</b>, or {@code null} once the
	 * stream is FINed. The caller owns the frame.
	 * <p>
	 * Loops synchronously while input is already in hand — a buffer routinely carries several frames, and
	 * recursing per frame would grow the stack with the peer's chunk size. An unknown or GREASE frame
	 * (RFC 9114 §9, FR-062) is discarded <b>inside that loop</b> rather than handed up for a caller to
	 * skip and ask again: a completed {@link Promise} runs its continuation synchronously, so a caller
	 * that recursed per skipped frame would take one stack frame per GREASE frame the peer chose to send
	 * — and a 2-byte frame type with a zero length means one maximum-size datagram is tens of thousands
	 * of them. The control stream drains the same way ({@code Http3Connection.feedControlStream}).
	 * <p>
	 * A long DATA frame arrives as several {@link DataFrame} instalments (see {@link Http3FrameReader}),
	 * so a frame in hand no longer means the reader is between frames — {@code isMidFrame()} is what says
	 * that, and it is the reader's own state rather than anything inferred from what it consumed.
	 */
	private Promise<Http3Frame> nextFrame() {
		while (true) {
			if (pendingInput != null) {
				Http3Frame frame;
				try {
					frame = frameReader.feed(pendingInput);
					midFrame = frameReader.isMidFrame();
				} catch (Http3Exception e) {
					return Promise.ofException(abortWith(e));
				}
				if (!pendingInput.canRead()) {
					pendingInput = nullify(pendingInput, ByteBuf::recycle);
				}
				if (frame == null) continue;
				try {
					inbound.accept(frame.type());
				} catch (Http3Exception e) {
					Recyclers.recycle(frame);
					return Promise.ofException(abortWith(e));
				}
				if (frame instanceof UnknownFrame) {
					// RFC 9114 §9: it carries no payload this layer holds, and the sequence validator has
					// already refused everything that is not tolerable here. Skipped without unwinding, so
					// the peer's GREASE frame count is not this thread's stack depth.
					discarded(frame);
					continue;
				}
				return Promise.of(frame);
			}
			if (endOfInput) return Promise.of(null);
			Promise<ByteBuf> armed = blockedRead;
			if (armed != null) {
				// A section unblocked while the watch read was still in flight. Two concurrent get()s on one
				// CSP supplier would overwrite each other's parked read, so this one waits for that one.
				return armed.then(
					$ -> nextFrame(),
					e -> Promise.ofException(terminalException != null ? terminalException : e));
			}
			if (blockedEndOfInput) {
				blockedEndOfInput = false;
				return onEndOfInput();
			}

			Promise<ByteBuf> next = reader().get();
			if (next.isResult()) {
				ByteBuf buf = next.getResult();
				if (buf == null) return onEndOfInput();
				pendingInput = buf;
				continue;
			}
			if (next.isException()) return Promise.ofException(onReadFailure(next.getException()));
			return next.then(
				buf -> {
					if (buf == null) return onEndOfInput();
					pendingInput = buf;
					return nextFrame();
				},
				e -> Promise.ofException(onReadFailure(e)));
		}
	}

	private Promise<Http3Frame> onEndOfInput() {
		endOfInput = true;
		if (midFrame) {
			// RFC 9114 §7.1: a stream that ends in the middle of a frame ended in the middle of a message.
			return Promise.ofException(abortWith(new Http3Exception(Http3Errors.H3_FRAME_ERROR,
				"The stream ended in the middle of a frame")));
		}
		if (declaredContentLength != NO_CONTENT_LENGTH) {
			try {
				Http3Headers.checkContentLength(declaredContentLength, bodyBytesReceived);
			} catch (Http3Exception e) {
				return Promise.ofException(abortWith(e));
			}
		}
		if (state != State.RESET) state = State.COMPLETE;
		receiveFinished();
		return Promise.of(null);
	}

	/**
	 * What a caller sees instead of what this module raised, for the one failure {@code core-http} already
	 * has a name for: a response that is not a well-formed HTTP message is a {@link MalformedHttpException}
	 * here exactly as it is from {@code HttpClient}, so code written against {@code IHttpClient} catches the
	 * same type whichever implementation is under it (FR-047).
	 * <p>
	 * Exactly the message-error class is translated. {@code H3_MESSAGE_ERROR} is the code RFC 9114 §4.1.2
	 * reserves for a message that is not fully formed — a missing or duplicated pseudo-header, an uppercase
	 * field name, a connection-specific field, a {@code Content-Length} that disagrees with the body — and
	 * nothing else raised here means "the response was malformed": a limit, a timeout, a rejection and a
	 * transport failure each say something a caller would act on differently, and each keeps its own type
	 * (FR-058c). The {@link Http3Exception} is the cause, so its error code is not lost.
	 * <p>
	 * Applied on <b>both</b> paths a malformed response can surface through — the head, by
	 * {@code Http3Client}, and the body, by {@link #inboundFailureMapper} below. A {@code Content-Length}
	 * that disagrees with the body is only discovered when the body ends, so before this was applied to the
	 * body channel the documented type held for a bad {@code :status} but not for a short body, which is
	 * the harder half to get right at a call site.
	 * <p>
	 * Client-side only, as {@code contracts/java-api.md} §2.4 states it: a server answers a malformed
	 * request with a stream reset carrying the same code, and has no promise to fail.
	 */
	static Exception clientVisible(Exception e) {
		if (e instanceof Http3Exception h3 && h3.errorCode() == Http3Errors.H3_MESSAGE_ERROR) {
			return new MalformedHttpException(h3.reason(), h3);
		}
		return e;
	}

	/** Announces that nothing more will be received here; every path into COMPLETE or RESET passes through. */
	private void receiveFinished() {
		receiveComplete = nullify(receiveComplete, promise -> promise.trySet(null));
	}

	private ChannelSupplier<ByteBuf> reader() {
		if (reader == null) reader = stream.reader();
		return reader;
	}

	// ---------------------------------------------------------------- the inbound body

	/**
	 * De-frames DATA off {@link QuicStream#reader()}: each payload slice is handed straight to the
	 * consumer, which then owns it. A trailing HEADERS section is decoded and attached to the message
	 * (FR-038) rather than surfacing as body bytes.
	 */
	private Promise<ByteBuf> readBody() {
		return nextFrame().then(frame -> {
			if (frame == null) return Promise.of(null);
			if (frame instanceof DataFrame data) {
				bodyBytesReceived += data.data.readRemaining();
				if (bodyBytesReceived > settings.maxBodySize()) {
					data.recycle();
					return Promise.ofException(abortWith(new Http3Exception(Http3Errors.H3_EXCESSIVE_LOAD,
						"A message body of over " + settings.maxBodySize() + " bytes")));
				}
				state = State.BODY;
				return Promise.of(data.data);
			}
			if (frame instanceof HeadersFrame trailers) {
				return decodeFieldSection(trailers).then(
					fields -> {
						try {
							Http3Headers.validateTrailers(fields);
							if (inboundMessage != null) Http3Trailers.set(inboundMessage, fields);
						} catch (Http3Exception e) {
							return Promise.ofException(abortWith(e));
						}
						state = State.TRAILERS_DONE;
						// Depth 1, not a peer-driven loop: the sequence validator refuses a third HEADERS, so
						// the next frame this reads can only be DATA (refused after trailers) or end of input.
						return readBody();
					},
					e -> Promise.ofException(abortWith(e)));
			}
			// Unreachable: nextFrame() discards unknown and GREASE types itself, and the sequence validator
			// refuses every other type on a request stream. Stated rather than skipped, for the reason
			// readHeaders states.
			Recyclers.recycle(frame);
			return Promise.ofException(abortWith(new Http3Exception(Http3Errors.H3_FRAME_UNEXPECTED,
				"A frame this layer has no semantics for reached the body reader")));
		});
	}

	/** Recycles a frame this layer ignores and reports it, per RFC 9114 §9's GREASE rule (FR-062). */
	private void discarded(Http3Frame frame) {
		long declaredLength = frame instanceof UnknownFrame unknown ? unknown.declaredLength : 0;
		Recyclers.recycle(frame);
		eventListener.onFrameDiscarded(frame.type(), declaredLength);
	}

	private final class InboundBodySupplier extends AbstractChannelSupplier<ByteBuf> {
		@Override
		protected Promise<ByteBuf> doGet() {
			// The first ask is what makes this a message somebody is reading; see isReceivingBody().
			bodyRequested = true;
			return readBody().mapException(inboundFailureMapper::apply);
		}

		@Override
		protected void onClosed(Exception e) {
			// FR-057: a consumer that walks away from a body still owes the peer an answer, and the only
			// honest one is an abort — the bytes it did not read are still arriving.
			if (state == State.COMPLETE || state == State.RESET) return;
			abortWith(e instanceof Http3Exception || e instanceof QuicStreamException ?
				e :
				new Http3Exception(Http3Errors.H3_REQUEST_CANCELLED, "The message body was abandoned by its consumer"));
		}
	}

	// ---------------------------------------------------------------- the outbound body

	/**
	 * Frames each chunk as a DATA frame and propagates {@link QuicStream#writer()}'s own promise
	 * untouched — the whole of this layer's backpressure (FR-056). The payload is written as its own
	 * buffer rather than copied behind the frame header, so a body is never duplicated in memory.
	 */
	private final class OutboundBodyConsumer extends AbstractChannelConsumer<ByteBuf> {
		private final ChannelConsumer<ByteBuf> writer;

		OutboundBodyConsumer(ChannelConsumer<ByteBuf> writer) {
			this.writer = writer;
		}

		@Override
		protected Promise<Void> doAccept(@Nullable ByteBuf value) {
			if (value == null) return writer.accept(null);
			if (!value.canRead()) {
				// A zero-length DATA frame is legal but says nothing; RFC 9114 §7.2.1 gives it no meaning.
				value.recycle();
				return Promise.complete();
			}
			int payloadLength = value.readRemaining();
			ByteBuf header = dataFrameHeader(payloadLength);
			bodyBytesSent += payloadLength;
			return writer.accept(header)
				.whenException(e -> value.recycle())
				.then(() -> writer.accept(value));
		}
	}

	private static ByteBuf dataFrameHeader(int payloadLength) {
		ByteBuf buf = ByteBufPool.allocate(
			QuicVarInts.encodedLength(DataFrame.TYPE) + QuicVarInts.encodedLength(payloadLength));
		QuicVarInts.write(buf, DataFrame.TYPE);
		QuicVarInts.write(buf, payloadLength);
		return buf;
	}

	private ByteBuf encodeHeadersFrame(List<Field> fields) {
		HeadersFrame frame = new HeadersFrame(qpackEncoder.encode(Http3Headers.toQpack(fields)));
		try {
			ByteBuf buf = ByteBufPool.allocate(Http3Frames.encodedLength(frame));
			Http3Frames.write(buf, frame);
			return buf;
		} finally {
			frame.recycle();
		}
	}

	// ---------------------------------------------------------------- failure paths

	/**
	 * Aborts both halves with {@code e}'s code and returns {@code e}, so a caller can
	 * {@code return Promise.ofException(abortWith(e))} without the abort and the report drifting apart.
	 */
	private Exception abortWith(Exception e) {
		if (state == State.RESET) return terminalException != null ? terminalException : e;
		state = State.RESET;
		terminalException = e;
		pendingInput = nullify(pendingInput, ByteBuf::recycle);
		// A stream aborted part-way through a frame leaves its reader holding the payload it had begun
		// filling; nothing will ever finish that frame, so this is the path that owes it a release (DI-1).
		frameReader.recycle();

		long errorCode = e instanceof Http3Exception h3 ? h3.errorCode() : Http3Errors.H3_REQUEST_CANCELLED;
		// Both verbs are idempotent, and a stream aborted by its peer is already terminal on that side.
		// They also fail whatever read or write was in flight, which is what cancels a servlet waiting on
		// a body that will never arrive (FR-046a).
		if (stream.hasSendPart()) stream.reset(errorCode);
		if (stream.hasReceivePart()) stream.stopSending(errorCode);
		eventListener.onStreamReset(stream.id(), errorCode);
		// Closed before the message is released, so the release cannot start a drain of a stream that has
		// just been told to stop.
		bodySupplier = nullify(bodySupplier, supplier -> supplier.closeEx(e));
		releaseInbound();
		logger.trace("HTTP/3 request stream {} aborted: {}", stream.id(), e.toString());
		// Nothing more will arrive here, whatever the reason it stopped: a client's connection pool frees
		// the slot this exchange held on the strength of this, so it fires on the abort path as it does on
		// the clean one (T112).
		receiveFinished();
		// Last, and after this stream is wholly terminal: the listener routinely closes the connection,
		// which aborts every stream it owns — this one included, whose second abort must find nothing left
		// to do.
		if (e instanceof Http3Exception h3 && isConnectionError(h3)) {
			connectionErrorListener.accept(h3);
		}
		return e;
	}

	/**
	 * Whether a violation observed on a request stream is nonetheless a <b>connection</b> error.
	 * <p>
	 * Exactly two codes are, and neither is about the message this stream carries:
	 * <ul>
	 *   <li>{@code H3_ID_ERROR} — RFC 9114 §7.2.5's PUSH_PROMISE, which {@link Http3FrameSequence}
	 *       refuses because it is judged against the connection-wide push limit rather than against this
	 *       stream: a peer that promised a push against a limit of 0 has misread the connection, not the
	 *       exchange (FR-040);</li>
	 *   <li>{@code H3_FRAME_UNEXPECTED} — a frame RFC 9114 §7.2's table does not permit here at all: a
	 *       reserved HTTP/2 type ({@code 0x02}, {@code 0x06}, {@code 0x08}, {@code 0x09}, refused by
	 *       {@link io.activej.http3.frame.Http3FrameReader}), a control-only frame on a request stream,
	 *       or a §4.1 sequence the grammar does not admit — DATA before HEADERS, a third HEADERS.
	 *       FR-024 and FR-025 make every one of those a connection error: a peer that frames the
	 *       protocol wrongly is not framing <i>this</i> exchange wrongly, and the bytes that follow on
	 *       every other stream are no more trustworthy than these.</li>
	 * </ul>
 * Those two are derivable from the code, and both are raised by the frame layer rather than here.
	 * A third group is <b>not</b> derivable from the code and arrives pre-classified instead: QPACK
	 * failures, where RFC 9204 assigns the scope per cause while every cause shares
	 * {@code QPACK_DECOMPRESSION_FAILED} (0x0200). An invalid static-table index (§3.1), a reference to
	 * an unavailable dynamic-table entry (§2.2.3) and an unexpected Required Insert Count (§4.5.1) are
	 * connection errors, because they mean the peer's encoder and this decoder disagree about the
	 * format itself; a value too large to decode or a truncated section (§7) stays a stream error,
	 * because it is a local limit and the static table carries no cross-section state to corrupt.
	 * {@link Http3Exception#isConnectionScoped()} carries that verdict.
	 * <p>
	 * Everything a request stream refuses about the <i>message</i> — a missing or duplicated
	 * pseudo-header, a connection-specific field, a {@code Content-Length} that disagrees with the body,
	 * an oversized field section, a broken frame, an abandoned body — is a stream error and leaves the
	 * connection alone (FR-037).
	 */
	private static boolean isConnectionError(Http3Exception e) {
		return e.errorCode() == Http3Errors.H3_ID_ERROR
			|| e.errorCode() == Http3Errors.H3_FRAME_UNEXPECTED
			|| e.isConnectionScoped();
	}

	/**
	 * FR-058c: the stream layer's own failures reach the caller unwrapped, carrying the peer's
	 * application error code. Anything else is a local failure of ours, reported as one.
	 */
	private Exception onReadFailure(Exception e) {
		if (e instanceof QuicStreamException) {
			return abortWith(e);
		}
		return abortWith(new Http3Exception(Http3Errors.H3_INTERNAL_ERROR, "Reading the request stream failed: " + e));
	}

	private void onWriteFailure(Exception e) {
		if (state == State.RESET) return;
		abortWith(e instanceof QuicStreamException ?
			e :
			new Http3Exception(Http3Errors.H3_INTERNAL_ERROR, "Writing the message failed: " + e));
	}

	// ---------------------------------------------------------------- message ownership (FR-057a)

	/**
	 * Releases the message this stream decoded from the peer's HEADERS, unless
	 * {@link #receiveResponse()} already handed it — and with it its body — to a caller who now owns it.
	 */
	private void releaseInbound() {
		HttpMessage owned = inboundMessage;
		inboundMessage = null;
		if (owned != null && !inboundHandedOver) releaseMessage(owned);
	}

	/**
	 * Releases whatever pooled bytes {@code message} still owns. A message whose body was taken owns
	 * nothing, and the single-take contract is the only way to ask: {@code takeBodyStream()} throws once
	 * somebody else holds it, which is precisely the answer "there is nothing here to release".
	 */
	static void releaseMessage(HttpMessage message) {
		ChannelSupplier<ByteBuf> body = takeBodyOrNull(message);
		if (body != null) body.streamTo(ChannelConsumers.recycling());
	}

	private static @Nullable ChannelSupplier<ByteBuf> takeBodyOrNull(HttpMessage message) {
		try {
			return message.takeBodyStream();
		} catch (IllegalStateException e) {
			// "Body stream is missing or already consumed" — either way it is not ours to release.
			return null;
		}
	}

	@Override
	public String toString() {
		return "Http3RequestStream{" + stream.id() + ", " + state +
			(terminalException == null ? "" : ", " + terminalException) + '}';
	}
}
