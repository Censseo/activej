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

package io.activej.jsonrpc.transport.http;

import io.activej.async.exception.AsyncCloseException;
import io.activej.bytebuf.ByteBuf;
import io.activej.common.MemSize;
import io.activej.common.builder.AbstractBuilder;
import io.activej.http.HttpError;
import io.activej.http.HttpRequest;
import io.activej.http.HttpResponse;
import io.activej.http.IHttpClient;
import io.activej.http.MalformedHttpException;
import io.activej.jsonrpc.JsonRpcLimits;
import io.activej.jsonrpc.transport.JsonRpcTransport;
import io.activej.promise.Promise;
import io.activej.promise.SettablePromise;
import io.activej.reactor.AbstractReactive;
import io.activej.reactor.Reactor;
import org.jetbrains.annotations.Nullable;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import static io.activej.common.Checks.checkArgument;
import static io.activej.common.Checks.checkState;
import static io.activej.http.HttpHeaders.CONTENT_TYPE;
import static io.activej.reactor.Reactive.checkInReactorThread;

/**
 * The client half of the JSON-RPC-over-HTTP transport (FR-030…FR-043): a {@link JsonRpcTransport}
 * that turns each {@code send(byte[] document)} into one {@code POST} to a configured URL and feeds
 * the response body to its listener.
 * <p>
 * <b>One send is one POST (FR-041).</b> There is no batching, no coalescing, no retry and no
 * reordering; connection reuse, keep-alive and pooling are entirely the injected {@link IHttpClient}'s
 * (FR-042) and are not re-implemented or overridden here. The injected client is <b>not owned</b>
 * and is never closed (FR-040) — {@code IHttpClient} structurally cannot be closed, and a second
 * transport on the same client keeps exchanging.
 * <p>
 * <b>The exchange, deliver-first (plan D2).</b> {@code send} builds a {@code POST} with
 * {@code Content-Type: application/json} (FR-032 — strict on the request) and the document as the
 * body, then wires the response-processing callback:
 * <ol>
 *     <li>network failure → {@code send}'s promise fails with the medium's exception;</li>
 *     <li>non-{@code 2xx} → {@code send}'s promise fails with {@link HttpError} immediately, and the
 *     response body is drained in the background so the connection completes its exchange instead of
 *     lingering (plan D11);</li>
 *     <li>{@code 2xx} → {@code loadBody(maxBodySize)} bounds the body <b>during</b> accumulation
 *     (obligation 2, FR-037), with a post-load check closing the already-accumulated hole (plan F4);
 *     an empty body (a {@code 204}, or any {@code 2xx} with a zero-length body) delivers <b>nothing</b>
 *     (obligation 3, FR-033) and completes the promise;</li>
 *     <li>a non-empty body is delivered — {@code listener.onDocument(doc)} <b>before</b>
 *     {@code sendPromise.set(null)} in the same callback, so the document arrives before or as
 *     {@code send}'s promise completes, never after (FR-035; safe because {@code JsonRpcClient}
 *     registers its correlation entry before calling {@code send}).</li>
 * </ol>
 * The body→{@code byte[]} conversion is the response equivalent of the servlet's (FR-026, plan F5):
 * {@code takeBody()} transfers ownership out of the response — which the client connection would
 * otherwise recycle with it — <b>then</b> {@code ByteBuf.asArray()} copies and recycles in one call.
 * Every branch recycles exactly once; {@code getArray()} never appears on this path.
 * <p>
 * <b>Response bounds (plan D3).</b> The bound is the transport's own per-instance
 * {@link #maxBodySize}, defaulting to {@link JsonRpcLimits#MAX_BODY_SIZE} — the exact mirror of the
 * servlet's {@code withMaxBodySize} (FR-020's per-instance override rule). No {@code ApplicationSettings}
 * key exists (Decision 8). The response's {@code Content-Type} is never inspected (FR-043 — lenient
 * on the response, strict on the request).
 * <p>
 * <b>Failure blast radius (FR-034, FR-036).</b> A failure of {@code send}'s promise fails exactly
 * the call that caused it and never reaches {@code onClosed} — the next {@code POST} on the same
 * transport may well succeed.
 * <p>
 * <b>Closing (FR-039, FR-040, plan D9).</b> A single {@code closed} latch: {@code onClosed} fires on
 * the false→true edge only, however often {@code close()}/{@code closeEx(e)} are called; {@code closeEx}
 * captures the close exception, fails every in-flight {@code send} promise with it and clears the
 * in-flight set; a post-close {@code send} fails immediately with the captured exception <b>before</b>
 * any request is built; a response callback that runs after close drains the body and delivers
 * nothing, and does not touch its already-failed promise (a second {@code set} would throw).
 * {@code close()} is inherited from {@code AsyncCloseable} and reports
 * {@code closeEx(new AsyncCloseException())} (plan F13).
 * <p>
 * <b>Threading (FR-031).</b> The transport is reactive; every public method opens with
 * {@code checkInReactorThread(this)}. The {@code IHttpClient} runs on the same reactor, so responses
 * already arrive on the right thread and no {@code Reactor.post} hop is needed — a transport handed a
 * client from another reactor violates that wiring, and {@code JsonRpcClient}'s callback guards turn
 * the mistake into an immediate, loud failure.
 * <p>
 * <b>{@code setListener} (FR-038).</b> Called once, before the first {@code send}; a missing listener
 * is a programmer error and is refused. An HTTP response can only arrive in reply to a send, so no
 * document can ever arrive before a listener exists — there is nothing to buffer and nothing to drop.
 * <p>
 * <b>Per-exchange allocation (FR-075 mirror).</b> One {@code SettablePromise} and one
 * {@code HttpRequest} per {@code send}; {@code withBody} copies the document {@code byte[]} into a
 * pooled buffer (the outbound copy), and {@code takeBody().asArray()} copies the response body
 * pooled-buffer → {@code byte[]} (the inbound copy) — exactly one copy per direction, none on the
 * failure paths, and no buffer outlives a single exchange (FR-075, spec performance table).
 */
public final class JsonRpcHttpClientTransport extends AbstractReactive implements JsonRpcTransport {
	private final IHttpClient httpClient;
	private final String url;

	/** Bounds the RESPONSE body. Defaults to {@link JsonRpcLimits#MAX_BODY_SIZE} (FR-037, plan D3). */
	private MemSize maxBodySize = JsonRpcLimits.MAX_BODY_SIZE;

	private @Nullable Listener listener;
	private boolean closed;
	private @Nullable Exception closeException;

	/** In-flight {@code send} promises — exists only for close semantics (plan D1, D9). */
	private final Set<SettablePromise<Void>> inFlight = new HashSet<>();

	private JsonRpcHttpClientTransport(Reactor reactor, IHttpClient httpClient, String url) {
		super(reactor);
		this.httpClient = httpClient;
		this.url = url;
	}

	/** The no-configuration shortcut: a transport over {@code httpClient} with all defaults. */
	public static JsonRpcHttpClientTransport create(Reactor reactor, IHttpClient httpClient, String url) {
		return builder(reactor, httpClient, url).build();
	}

	/**
	 * Starts a transport on {@code reactor} over {@code httpClient}, POSTing to {@code url}.
	 *
	 * @throws NullPointerException if any argument is {@code null}
	 */
	public static Builder builder(Reactor reactor, IHttpClient httpClient, String url) {
		return new JsonRpcHttpClientTransport(
				Objects.requireNonNull(reactor, "reactor"),
				Objects.requireNonNull(httpClient, "httpClient"),
				Objects.requireNonNull(url, "url"))
			.new Builder();
	}

	public final class Builder extends AbstractBuilder<Builder, JsonRpcHttpClientTransport> {
		private Builder() {}

		/**
		 * The response-body bound (FR-037): the largest response body this transport accepts.
		 * Defaults to {@link JsonRpcLimits#MAX_BODY_SIZE}; a non-positive value is refused at
		 * {@link #build()} — the same unconditional refusal the servlet's builder applies (T031),
		 * because {@code 0} would disable {@code loadBody}'s bound entirely.
		 */
		public Builder withMaxBodySize(MemSize maxBodySize) {
			checkNotBuilt(this);
			JsonRpcHttpClientTransport.this.maxBodySize = Objects.requireNonNull(maxBodySize, "maxBodySize");
			return this;
		}

		@Override
		protected JsonRpcHttpClientTransport doBuild() {
			// unconditional — the servlet's builder precedent (T031): a transport that cannot bound
			// its response body would silently unbind FR-037
			checkArgument(maxBodySize.toInt() > 0, "maxBodySize must be positive");
			return JsonRpcHttpClientTransport.this;
		}
	}

	/**
	 * Turns one document into one {@code POST} (FR-041) and completes the returned promise once the
	 * exchange has produced a {@code 2xx} and any resulting document has been delivered to the
	 * listener — never before (FR-034, FR-035). A network-level failure or a non-{@code 2xx}
	 * status fails <b>this</b> call's promise only, never {@code onClosed} (FR-036); a response
	 * body over {@link #maxBodySize} fails the same way and delivers nothing (FR-037).
	 * <p>
	 * <b>Ownership.</b> {@code document} is the SPI's — not retained after this method returns; the
	 * request built from it is the client's to recycle. The <b>response</b> body is this method's:
	 * every branch takes it ({@code takeBody()} — ownership out of the response, which the
	 * connection recycles with the exchange) and recycles it exactly once — via {@code asArray()}
	 * on the delivery path (copies <b>and</b> recycles; the FR-026 take-then-convert order holds
	 * client-side too), via {@link #drain(HttpResponse)} on the non-{@code 2xx} and post-close
	 * paths, and explicitly when the post-load bound check or the zero-length check refuses
	 * delivery. {@code getArray()} never appears on this path. An exchange in flight at
	 * {@link #closeEx(Exception)} delivers nothing.
	 * <p>
	 * <b>Allocation (FR-075 mirror).</b> One {@code SettablePromise} and one {@code HttpRequest}
	 * per call; the request body is copied into a pooled buffer by {@code withBody} (outbound copy)
	 * and the response body pooled-buffer → {@code byte[]} by {@code asArray()} (inbound copy) —
	 * one copy per direction, none on the failure paths.
	 */
	@Override
	public Promise<Void> send(byte[] document) {
		checkInReactorThread(this);
		if (closed) return Promise.ofException(closedException());
		Objects.requireNonNull(document, "document");
		checkState(listener != null, "setListener must be called before the first send (FR-038)");

		SettablePromise<Void> sendPromise = new SettablePromise<>();
		inFlight.add(sendPromise);

		// one send, one POST (FR-041); the request body is the connection's to recycle — the
		// transport never retains document after this call returns (the SPI's contract)
		HttpRequest request = HttpRequest.post(url)
			.withHeader(CONTENT_TYPE, "application/json")                    // FR-032, literal value
			.withBody(document)
			.build();
		httpClient.request(request).whenComplete((response, e) -> {
			inFlight.remove(sendPromise);
			// T041: a callback that runs after close drains the body and delivers nothing — and must
			// not touch its already-failed SettablePromise (a second set throws — plan D9)
			if (closed) {
				if (response != null) drain(response);
				return;
			}
			if (e != null) {
				// network failure: fails this send's promise only, never onClosed (FR-034, FR-036)
				sendPromise.setException(e);
				return;
			}
			int code = response.getCode();
			if (code < 200 || code >= 300) {
				// T039 (D11): fail promptly, drain in the background, never deliver, never onClosed
				drain(response);
				sendPromise.setException(HttpError.ofCode(code));
				return;
			}
			// FR-037: the bound applies during accumulation (F4); the post-load check below closes
			// the already-accumulated hole
			response.loadBody(maxBodySize)
				.whenResult($ -> {
					ByteBuf taken = response.takeBody();                     // ownership out of the response (F5)
					if (taken.readRemaining() > maxBodySize.toInt()) {       // T039: the accumulated-path belt
						taken.recycle();
						// the same failure the streaming path produces, so both paths fail identically
						sendPromise.setException(new MalformedHttpException(
							"HTTP response body size exceeds load limit " + maxBodySize));
						return;
					}
					if (taken.readRemaining() == 0) {                        // obligation 3: no document
						taken.recycle();
						sendPromise.set(null);                               // a 204 still completes the send (FR-034)
						return;
					}
					byte[] delivered = taken.asArray();                      // copies AND recycles in one call
					listener.onDocument(delivered);                          // FR-035: before the promise (plan D2)
					sendPromise.set(null);
				})
				.whenException(sendPromise::setException);                   // oversize mid-stream (F4)
		});
		return sendPromise;
	}

	/**
	 * Registers the listener documents are delivered to. Must be called once, before the first
	 * {@link #send(byte[])} — a missing listener is refused at the first send (FR-038). An HTTP
	 * response can only arrive in reply to a send, so no document can ever arrive before a listener
	 * exists; there is nothing to buffer and nothing to drop. The listener is retained for the
	 * lifetime of this transport; this method owns no buffer and no promise.
	 */
	@Override
	public void setListener(Listener listener) {
		checkInReactorThread(this);
		this.listener = Objects.requireNonNull(listener, "listener");
	}

	/**
	 * Closes the transport: fires {@link Listener#onClosed} <b>exactly once</b> — the call is
	 * idempotent, so a second invocation, or a {@code close()} after {@code closeEx}, changes
	 * nothing — fails every in-flight {@link #send(byte[])} promise with {@code e}, and makes any
	 * later {@code send} fail immediately without issuing a request (FR-039, WI-9). The injected
	 * {@link IHttpClient} is <b>never</b> closed — this transport does not own it (FR-040); a
	 * response callback that runs after close drains the body (recycling it) and delivers nothing.
	 * This method owns no buffer; {@code close()} is inherited from {@code AsyncCloseable} and
	 * reports {@code closeEx(new AsyncCloseException())}.
	 */
	@Override
	public void closeEx(Exception e) {
		checkInReactorThread(this);
		Objects.requireNonNull(e, "e");
		if (closed) return;                                                  // idempotent (FR-039, WI-9)
		closed = true;
		closeException = e;
		// fail the calls before reporting the close, so a listener continuation that inspects state
		// sees a consistent picture (plan T041); a snapshot, because a failed promise's continuation
		// may itself call send (which fails immediately — the latch is set first)
		for (SettablePromise<Void> promise : List.copyOf(inFlight)) {
			promise.setException(e);
		}
		inFlight.clear();
		if (listener != null) listener.onClosed(e);                          // exactly once (obligation 6)
	}
	// close() is inherited from AsyncCloseable → closeEx(new AsyncCloseException()) (plan F13)

	/**
	 * Drains a response body in the background so the connection completes its exchange and returns
	 * to the pool instead of lingering (plan D11). Take-then-recycle: {@code loadBody} stores the
	 * body in the response, and the client connection recycles the response after the exchange — the
	 * take transfers ownership so that later recycle finds {@code body == null} (plan F5; without it
	 * the already-accumulated path double-recycles). A drain that fails (an oversize body) is
	 * ignored: the connection closes itself and recycles.
	 */
	private void drain(HttpResponse response) {
		response.loadBody(maxBodySize)
			.whenResult($ -> response.takeBody().recycle())
			.whenException($ -> {});
	}

	private Exception closedException() {
		Exception e = closeException;
		return e != null ? e : new AsyncCloseException("the transport is closed");
	}
}
