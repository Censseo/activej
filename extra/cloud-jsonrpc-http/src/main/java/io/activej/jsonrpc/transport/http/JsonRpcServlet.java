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

import io.activej.common.MemSize;
import io.activej.common.builder.AbstractBuilder;
import io.activej.http.AsyncServlet;
import io.activej.http.HttpMethod;
import io.activej.http.HttpRequest;
import io.activej.http.HttpResponse;
import io.activej.jsonrpc.JsonRpcLimits;
import io.activej.jsonrpc.service.JsonRpcDispatcher;
import io.activej.promise.Promise;
import io.activej.reactor.AbstractReactive;
import io.activej.reactor.Reactor;

import java.util.Objects;

import static io.activej.common.Checks.checkArgument;
import static io.activej.http.HttpHeaders.ALLOW;
import static io.activej.http.HttpHeaders.CONTENT_LENGTH;
import static io.activej.http.HttpHeaders.CONTENT_TYPE;
import static io.activej.reactor.Reactive.checkInReactorThread;

/**
 * The server half of the JSON-RPC-over-HTTP transport: an {@link AsyncServlet} that turns one HTTP
 * {@code POST} into one {@link JsonRpcDispatcher#dispatch(byte[])} call and writes the dispatcher's
 * bytes back unaltered (FR-010…FR-019).
 * <p>
 * The servlet is <b>path-agnostic</b> (FR-012) — it reads no path segment and no query parameter,
 * so it serves identically at a server root or mounted under a {@code RoutingServlet} at any path
 * — and <b>stateless</b> between requests: a servlet instance and its dispatcher belong to exactly
 * one reactor, and a multi-worker server needs one set per worker reactor.
 * <p>
 * <b>Threading.</b> The servlet is reactive ({@link AbstractReactive}); every public method opens
 * with {@code checkInReactorThread(this)} (FR-011). {@link #builder(Reactor, JsonRpcDispatcher)}
 * and {@link #create(Reactor, JsonRpcDispatcher)} are construction-time entry points and are not
 * guarded, matching {@code JsonRpcDispatcher.builder}.
 * <p>
 * <b>Body ownership.</b> The request body is materialised whole before decoding (FR-024):
 * {@code loadBody(maxBodySize)} bounds the body <b>during</b> accumulation, and the conversion to
 * the {@code byte[]} the dispatcher needs is {@code takeBody()} <b>then</b> {@code asArray()} — in
 * that order. {@code loadBody} leaves the request owning the body, so the ownership is taken
 * first, and {@code asArray()} copies and recycles in one call, so the buffer is released as part
 * of the conversion and no path can forget it (FR-026). {@code getArray()} — which copies
 * <i>without</i> recycling — must never appear on this path.
 * <p>
 * <b>Empty responses.</b> A zero-length dispatcher result — its encoding of "no response" for a
 * lone notification or an all-notification batch — becomes the configured {@code emptyResponseCode}
 * (204 by default, 200 optional) with <b>no body and no {@code Content-Type}</b> (FR-017). The
 * {@code 204} wire form is core-http's own: {@code HTTP/1.1 204 No Content} plus an automatic
 * {@code Content-Length: 0}, nothing to suppress, nothing to add (observed in probe R1). The
 * branch keys on the result's length, never on the request's shape — an unparseable body or an
 * empty {@code []} batch produces a non-empty error document and stays on the {@code 200} path.
 * <p>
 * <b>Rejections.</b> The HTTP semantics table ({@code contracts/http-semantics.md} §2) is evaluated
 * <b>in order</b>, before any body byte is read: a non-{@code POST} method is {@code 405} with
 * {@code Allow: POST} (FR-015, FR-096); a {@code Content-Type} outside the allow-list, or absent,
 * is {@code 415} (FR-016); a declared {@code Content-Length} above {@link #maxBodySize} is
 * {@code 413} (FR-022). A body that crosses the bound <b>mid-stream</b> is not the servlet's to
 * answer: {@code loadBody}'s violation reaches the connection tier's hardcoded {@code 400} + close
 * before this chain can react (probe R2, FR-023 as amended).
 * <p>
 * <b>Totality.</b> {@link JsonRpcDispatcher#dispatch(byte[])} never completes exceptionally
 * (FR-038a of feature 012, ADR-033), so the dispatch promise needs no failure branch; and a
 * body-loading failure never reaches this chain — the connection tier answers it first (probe R2).
 * Every input a caller can send therefore produces a response, never a failed promise (FR-019).
 * <p>
 * <b>Per-request allocation (FR-075).</b> The servlet allocates no per-request object and no
 * buffer of its own: the pooled body buffer is the connection's (accumulated by {@code loadBody}
 * into core-http's pooled accumulator), and the one unavoidable copy — pooled buffer →
 * contiguous {@code byte[]} — happens inside {@code takeBody().asArray()} (FR-024, verdict 00-A).
 * The dispatcher decodes from that array and encodes a response {@code byte[]} of its own; outbound,
 * {@code HttpResponse.withBody(byte[])} copies into a pooled buffer the connection owns and
 * recycles. Exactly one copy per direction, and the {@code 405}/{@code 415}/{@code 413} rejection
 * paths allocate no body at all (FR-075, spec performance table).
 */
public final class JsonRpcServlet extends AbstractReactive implements AsyncServlet {
	private final JsonRpcDispatcher dispatcher;
	private MemSize maxBodySize = JsonRpcLimits.MAX_BODY_SIZE;
	private int emptyResponseCode = 204;

	private JsonRpcServlet(Reactor reactor, JsonRpcDispatcher dispatcher) {
		super(reactor);
		this.dispatcher = dispatcher;
	}

	/** The no-configuration shortcut: a servlet over {@code dispatcher} with all defaults. */
	public static JsonRpcServlet create(Reactor reactor, JsonRpcDispatcher dispatcher) {
		return builder(reactor, dispatcher).build();
	}

	/**
	 * Starts a servlet on {@code reactor} over {@code dispatcher}.
	 *
	 * @throws NullPointerException if {@code reactor} or {@code dispatcher} is {@code null}
	 */
	public static Builder builder(Reactor reactor, JsonRpcDispatcher dispatcher) {
		return new JsonRpcServlet(
				Objects.requireNonNull(reactor, "reactor"),
				Objects.requireNonNull(dispatcher, "dispatcher"))
			.new Builder();
	}

	public final class Builder extends AbstractBuilder<Builder, JsonRpcServlet> {
		private Builder() {}

		/**
		 * The status of an empty response — a zero-length dispatcher result. {@code 204} (the
		 * default) or {@code 200}: some JSON-RPC clients in the wild expect {@code 200} with an
		 * empty body instead of {@code 204}. Any other value is refused at {@link #build()}.
		 */
		public Builder withEmptyResponseCode(int emptyResponseCode) {
			checkNotBuilt(this);
			JsonRpcServlet.this.emptyResponseCode = emptyResponseCode;
			return this;
		}

		/**
		 * The request-body bound (FR-020): the largest body this servlet accepts. Defaults to
		 * {@link JsonRpcLimits#MAX_BODY_SIZE}; a non-positive value is refused at {@link #build()}.
		 * The bound is the servlet tier's — {@code HttpServer}'s own 100 MB connection tier stays
		 * untouched (FR-020a), and no {@code ApplicationSettings} key exists for it (Decision 8 —
		 * {@code JsonRpcLimits} exists precisely so a transport reads the bound). A configured value
		 * above {@code Integer.MAX_VALUE} bytes is clamped to that for accumulation; the 100 MB
		 * connection tier remains the effective cap for anything above it (FR-020a).
		 */
		public Builder withMaxBodySize(MemSize maxBodySize) {
			checkNotBuilt(this);
			JsonRpcServlet.this.maxBodySize = Objects.requireNonNull(maxBodySize, "maxBodySize");
			return this;
		}

		@Override
		protected JsonRpcServlet doBuild() {
			// unconditional — a promised refusal (FR-018), not a CHECKS-gated debug aid:
			// production runs with checks off must still refuse to ship a 418-answering servlet
			checkArgument(emptyResponseCode == 200 || emptyResponseCode == 204,
				"emptyResponseCode must be 200 or 204");
			// T031 — unconditional, always-on: java-api.md §1 refuses a non-positive maxBodySize.
			// 0 would disable loadBody's bound (HttpMessage.java:335 `maxBodySize != 0 &&`) AND make
			// the row-3 check answer 413 for every declared-length request (`declared > 0`).
			// toLong(), not toInt(): toInt throws past Integer.MAX_VALUE before the check could run (D3-1).
			checkArgument(maxBodySize.toLong() > 0, "maxBodySize must be positive");
			return JsonRpcServlet.this;
		}
	}

	/**
	 * Serves one {@code POST} by evaluating the HTTP semantics table ({@code contracts/http-semantics.md}
	 * §2) in order — method → media type → declared length — and then loading, dispatching and
	 * writing the response (FR-013…FR-019). Never completes exceptionally for any input a caller
	 * can send (FR-019); every rejection is a response.
	 * <p>
	 * <b>Ownership (FR-025, FR-026).</b> This method receives the request but never owns its body:
	 * {@code loadBody} leaves the body with the request — which the connection recycles with the
	 * exchange — so the conversion is {@code request.takeBody()} (ownership taken out first, or the
	 * subsequent recycle double-releases) <b>then</b> {@code ByteBuf.asArray()} (copies into the
	 * dispatcher's {@code byte[]} and recycles in one call). Every body-bearing path recycles
	 * exactly once; the three rejection branches above read no body at all, so nothing is taken,
	 * converted or recycled there; {@code getArray()} never appears on this path.
	 * <p>
	 * <b>Allocation (FR-075).</b> Nothing per request beyond the promise chain — no per-request
	 * object of this class, no buffer of this class. The one inbound copy is pooled-buffer →
	 * {@code byte[]} inside {@code asArray()}; outbound, {@code withBody} copies the dispatcher's
	 * {@code byte[]} into a pooled buffer the connection owns. The rejection responses have no body
	 * and allocate none.
	 */
	@Override
	public Promise<HttpResponse> serve(HttpRequest request) {
		checkInReactorThread(this);
		// ---- semantics table row 1: the method gate, before anything is read (FR-015, FR-096) ----
		// Every non-POST method — GET, PUT, HEAD, DELETE, OPTIONS, TRACE — is refused the same way.
		// The order is part of the contract (http-semantics §2): a GET carrying an oversized declared
		// Content-Length is 405, never 413.
		if (request.getMethod() != HttpMethod.POST) {
			return HttpResponse.ofCode(405)
				.withHeader(ALLOW, "POST")
				.toPromise();
		}
		// ---- semantics table row 2: the media-type gate, before anything is decoded (FR-016) ------
		// Matches the raw header string; an absent Content-Type is rejected, not assumed.
		if (!JsonRpcHttpMediaTypes.isAccepted(request.getHeader(CONTENT_TYPE))) {
			return HttpResponse.ofCode(415).toPromise();
		}
		// ---- semantics table row 3: the up-front Content-Length bound (FR-022) -------------------
		String contentLengthHeader = request.getHeader(CONTENT_LENGTH);
		if (contentLengthHeader != null) {
			long declaredContentLength;
			try {
				declaredContentLength = Long.parseLong(contentLengthHeader.trim());
			} catch (NumberFormatException e) {
				// unreachable via a real connection — the connection tier has already validated the
				// header (AbstractHttpConnection.java:338-344) — but required for totality against
				// direct serve() callers (FR-019); a malformed value is treated as absent
				declaredContentLength = -1;
			}
			if (declaredContentLength > maxBodySize.toLong()) {
				// strict > : a body exactly at the bound proceeds (loadBody accepts it too)
				return HttpResponse.ofCode(413).toPromise();
			}
		}
		// ---- semantics table rows 4-6: load, dispatch, respond (FR-013, FR-017) -------------------
		// loadBody takes an int bound; a configured value above Integer.MAX_VALUE is clamped to it for
		// accumulation — the connection tier's 100mb is the real cap for anything above (FR-020a, D3-1).
		return request.loadBody((int) Math.min(maxBodySize.toLong(), Integer.MAX_VALUE))
			.then($ -> dispatcher.dispatch(request.takeBody().asArray()))
			.then(response -> response.length == 0 ?
				HttpResponse.ofCode(emptyResponseCode).toPromise() :
				HttpResponse.ok200()
					.withHeader(CONTENT_TYPE, "application/json")
					.withBody(response)
					.toPromise());
		// NO failure branch anywhere: dispatch is total (ADR-033); a mid-stream body-loading failure
		// never reaches this chain — the connection tier answers its hardcoded 400 first (probe R2).
		// The three rejection branches above are synchronous early returns, so a body is never read
		// on any rejection path (FR-015/016/022).
	}
}
