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

import io.activej.common.builder.AbstractBuilder;
import io.activej.http.AsyncServlet;
import io.activej.http.HttpError;
import io.activej.http.HttpMethod;
import io.activej.http.HttpRequest;
import io.activej.http.HttpResponse;
import io.activej.jsonrpc.service.JsonRpcDispatcher;
import io.activej.promise.Promise;
import io.activej.reactor.AbstractReactive;
import io.activej.reactor.Reactor;

import java.util.Objects;

import static io.activej.http.HttpHeaders.ALLOW;
import static io.activej.http.HttpHeaders.CONTENT_TYPE;
import static io.activej.reactor.Reactive.checkInReactorThread;

/**
 * The read-only OpenRPC discovery endpoint: an {@link AsyncServlet} that answers a {@code GET} with
 * the document {@link JsonRpcDispatcher#discoveryDocument()} already holds
 * ({@code contracts/openrpc-mapping.md} §4, FR-041).
 * <p>
 * It is the sibling of {@link JsonRpcServlet}, not a row added to it: the two are <b>separate
 * routes</b>, and feature 013's HTTP semantics table is untouched by this feature. Nothing here
 * decodes, dispatches or encodes anything — the document is generated once, at
 * {@code JsonRpcDispatcher.build()}, and this servlet only puts it on the wire. That is what makes
 * the {@code GET} answer and the {@code rpc.discover} answer byte-identical by construction rather
 * than by two code paths agreeing (FR-040).
 * <p>
 * Like {@link JsonRpcServlet} it is <b>path-agnostic</b> — it reads no path segment and no query
 * parameter, so the deployment chooses where it is mounted — and <b>stateless</b> between requests:
 * a servlet instance and its dispatcher belong to exactly one reactor, and a multi-worker server
 * needs one set per worker reactor.
 *
 * <h4>Serving one array forever, without owning a buffer</h4>
 * {@code discoveryDocument()} hands out <b>the</b> array: a build-time constant shared with the
 * dispatcher's {@code rpc.discover} table entry, reused for the life of the dispatcher, and
 * documented as the caller's to read and never to modify. Serving it therefore has one hard
 * requirement — <b>the response must never take ownership of it</b>.
 * <p>
 * {@code HttpResponse.withBody(byte[])} is exactly that: it wraps the array with
 * {@code ByteBuf.wrapForReading(...)}, which produces a <b>non-pooled</b> {@code ByteBuf} with
 * {@code refs == 0}. Two consequences, and both are load-bearing here:
 * <ul>
 *     <li>{@code recycle()} on such a buffer is a <b>no-op</b> — {@code ByteBuf.recycle()} only acts
 *     while {@code refs > 0}, so the array is never handed to {@code ByteBufPool}, never zeroed by
 *     {@code clearOnRecycle}, and never reissued to another consumer. The connection tier recycles
 *     the body it is given ({@code AbstractHttpConnection.renderHttpMessage} copies it into the
 *     pooled write buffer and then releases it) and the dispatcher's array survives that, request
 *     after request;</li>
 *     <li>a <b>fresh wrapper per request</b> is what keeps it correct. The wrapper carries the
 *     {@code head}/{@code tail} cursors, and rendering advances {@code head} to the end. Building
 *     one shared {@code ByteBuf} at construction time and handing it to every response — the obvious
 *     "avoid the allocation" move — would serve the document once and then serve zero bytes forever.
 *     {@code withBody(byte[])} is called per request precisely so the cursors are per response.</li>
 * </ul>
 * Per-request cost is therefore one 24-byte {@code ByteBuf} header and no copy of the document in
 * this servlet; the single copy is the connection's own, into the pooled write buffer, which it owns
 * and recycles — the same shape as {@link JsonRpcServlet}'s outbound path.
 *
 * <h4>The method gate is this servlet's, not the router's</h4>
 * §4 pins {@code 405} for any other method on the discovery path. That answer cannot be delegated:
 * {@code RoutingServlet} raises {@code HttpError.notFound404()} when no servlet is mapped for a
 * request's method, never a {@code 405}, and it emits no {@code Allow} header. The gate is therefore
 * here, first, in {@link JsonRpcServlet}'s own row-1 shape — {@code 405} plus {@code Allow: GET},
 * with no body — so the pinned status holds however the endpoint is mounted, and so a direct
 * {@code serve(...)} caller gets a response rather than a surprise.
 *
 * <h4>Discovery disabled</h4>
 * By contract the route is <b>not mounted at all</b> when discovery is off, so the path answers
 * exactly as any unmapped path (FR-042); mounting is the launcher's decision, not this class's.
 * The {@code null}-document branch below is the defensive one — reachable only through a mis-wiring
 * or a direct caller — and it answers with the very {@code HttpError.notFound404()} that
 * {@code RoutingServlet} raises for a path it does not know. That keeps a disabled deployment
 * unprobeable even when something mounts the endpoint by mistake, which a {@code 500} would not: a
 * distinct status would tell a caller that discovery exists here and is switched off. It is a
 * deliberate answer either way — never an unexplained {@code NullPointerException}.
 */
public final class JsonRpcDiscoveryServlet extends AbstractReactive implements AsyncServlet {
	private final JsonRpcDispatcher dispatcher;

	private JsonRpcDiscoveryServlet(Reactor reactor, JsonRpcDispatcher dispatcher) {
		super(reactor);
		this.dispatcher = dispatcher;
	}

	/** The no-configuration shortcut: the discovery endpoint of {@code dispatcher}, with all defaults. */
	public static JsonRpcDiscoveryServlet create(Reactor reactor, JsonRpcDispatcher dispatcher) {
		return builder(reactor, dispatcher).build();
	}

	/**
	 * Starts a discovery endpoint on {@code reactor} over {@code dispatcher}. The builder carries no
	 * options today — the document, its media type and its status are all pinned by
	 * {@code contracts/openrpc-mapping.md} §4, and §4 requires no caching headers — but it is the
	 * house construction shape and the seam a later, additive option (an {@code ETag}, say) belongs on.
	 *
	 * @throws NullPointerException if {@code reactor} or {@code dispatcher} is {@code null}
	 */
	public static Builder builder(Reactor reactor, JsonRpcDispatcher dispatcher) {
		return new JsonRpcDiscoveryServlet(
			Objects.requireNonNull(reactor, "reactor"),
			Objects.requireNonNull(dispatcher, "dispatcher"))
			.new Builder();
	}

	public final class Builder extends AbstractBuilder<Builder, JsonRpcDiscoveryServlet> {
		private Builder() {}

		@Override
		protected JsonRpcDiscoveryServlet doBuild() {
			// deliberately NOT checking discoveryDocument() here: a dispatcher is readable only from
			// its own reactor thread, and construction happens at wiring time. Mounting the route at
			// all is the deployment's decision (the launcher reads the config key); serve() answers
			// the disabled case the way an unmapped path answers it.
			return JsonRpcDiscoveryServlet.this;
		}
	}

	/**
	 * Serves the document: method gate, then the dispatcher's bytes as a read-only view.
	 * <p>
	 * <b>Ownership.</b> The request is bodyless by contract and nothing is read from it — no body is
	 * taken, converted or recycled on any path here. Outbound, the response body wraps the
	 * dispatcher's array without owning it (class Javadoc): the wrapper is non-pooled, the
	 * connection's recycle of it is a no-op, and a fresh wrapper per request keeps the read cursors
	 * per response. Nothing this method creates outlives one exchange, and nothing it touches is
	 * pooled.
	 */
	@Override
	public Promise<HttpResponse> serve(HttpRequest request) {
		checkInReactorThread(this);
		// §4 row "other methods on that path": the routing layer answers an unmapped method 404, not
		// 405, so the pinned status is produced here — before anything is read, like JsonRpcServlet's
		// own row 1
		if (request.getMethod() != HttpMethod.GET) {
			return HttpResponse.ofCode(405)
				.withHeader(ALLOW, "GET")
				.toPromise();
		}
		byte[] document = dispatcher.discoveryDocument();
		if (document == null) {
			// §4 row "discovery disabled": indistinguishable from any unmapped path (FR-042)
			return HttpError.notFound404().toPromise();
		}
		// withBody(byte[]) wraps — it does not copy and it does not pool. See the class Javadoc: the
		// wrapper's recycle() is a no-op, so the dispatcher's long-lived array survives every request
		return HttpResponse.ok200()
			.withHeader(CONTENT_TYPE, "application/json")
			.withBody(document)
			.toPromise();
	}
}
