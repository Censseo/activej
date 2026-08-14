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

package io.activej.jsonrpc.transport.http.fixtures;

import io.activej.common.MemSize;
import io.activej.common.builder.AbstractBuilder;
import io.activej.http.AsyncServlet;
import io.activej.http.HttpRequest;
import io.activej.http.HttpResponse;
import io.activej.http.HttpServer;
import io.activej.jsonrpc.JsonRpcLimits;
import io.activej.jsonrpc.service.JsonRpcDispatcher;
import io.activej.promise.Promise;
import io.activej.reactor.nio.NioReactor;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Future;
import java.util.function.Consumer;

import static io.activej.http.HttpHeaders.CONTENT_TYPE;

/**
 * The shared server harness for this module's tests and probes (FR-050a, ADR-028): a
 * {@link JsonRpcDispatcher} over {@link TestApi}, mounted behind a minimal fixture servlet on a real
 * {@link HttpServer} bound to <b>port {@code 0}</b>.
 * <p>
 * The actual address is never guessed: after {@link #listen()} it is read back with
 * {@link HttpServer#getBoundAddresses()} — the {@code :0} contract of
 * {@code AbstractReactiveServer} — and exposed through {@link #address()} / {@link #port()}.
 * {@code io.activej.test.TestUtils.getFreePort()} is deliberately absent from this module: an
 * allocate-then-release port leaves a window for another process to take it, and the registry records
 * that as an architectural anti-pattern whose answer is {@code getBoundAddresses()} (ADR-028).
 * <p>
 * The fixture servlet is this phase's probe vehicle only — it mirrors the wire semantics the real
 * {@code JsonRpcServlet} will implement (whole-body load bound by {@code maxBodySize}, dispatch,
 * {@code 204} on a zero-length result, {@code 200} + {@code application/json} otherwise) and is
 * superseded by it in Phase 3. Its body handling is leak-free by construction:
 * {@code ByteBuf.asArray()} copies <b>and</b> recycles in one call, and the dispatcher is total.
 */
public final class JsonRpcHttpTestServer {
	private final HttpServer server;
	private final JsonRpcDispatcher dispatcher;
	private final AsyncServlet servlet;
	private List<InetSocketAddress> boundAddresses = List.of();

	private JsonRpcHttpTestServer(HttpServer server, JsonRpcDispatcher dispatcher, AsyncServlet servlet) {
		this.server = server;
		this.dispatcher = dispatcher;
		this.servlet = servlet;
	}

	/** Starts a harness on {@code reactor}; nothing listens until {@link #listen()}. */
	public static Builder builder(NioReactor reactor) {
		return new Builder(reactor);
	}

	public static final class Builder extends AbstractBuilder<Builder, JsonRpcHttpTestServer> {
		private final NioReactor reactor;
		private MemSize maxBodySize = JsonRpcLimits.MAX_BODY_SIZE;
		private AsyncServlet servlet;
		private Consumer<HttpRequest> onRequest;
		private HttpServer.Inspector inspector;

		private Builder(NioReactor reactor) {
			this.reactor = reactor;
		}

		/**
		 * The bound {@code loadBody(int)} passes to the fixture servlet. Defaults to
		 * {@link JsonRpcLimits#MAX_BODY_SIZE}; probes shrink it so an oversize body costs bytes, not time.
		 * Ignored when {@link #withServlet(AsyncServlet)} replaces the fixture servlet.
		 */
		public Builder withMaxBodySize(MemSize maxBodySize) {
			checkNotBuilt(this);
			this.maxBodySize = Objects.requireNonNull(maxBodySize, "maxBodySize");
			return this;
		}

		/** Replaces the fixture servlet entirely — the escape hatch for probes observing other servlet shapes. */
		public Builder withServlet(AsyncServlet servlet) {
			checkNotBuilt(this);
			this.servlet = Objects.requireNonNull(servlet, "servlet");
			return this;
		}

		/** Invoked by the fixture servlet before each dispatch — the observable proof that {@code serve()} was entered. */
		public Builder withOnRequest(Consumer<HttpRequest> onRequest) {
			checkNotBuilt(this);
			this.onRequest = Objects.requireNonNull(onRequest, "onRequest");
			return this;
		}

		/** Forwards an {@link HttpServer.Inspector} to the server, for probes observing connection-tier events. */
		public Builder withInspector(HttpServer.Inspector inspector) {
			checkNotBuilt(this);
			this.inspector = Objects.requireNonNull(inspector, "inspector");
			return this;
		}

		@Override
		protected JsonRpcHttpTestServer doBuild() {
			JsonRpcDispatcher dispatcher = JsonRpcDispatcher.builder(reactor)
				.withService(TestApi.class, new TestApiImpl())
				.build();
			AsyncServlet effectiveServlet = servlet != null ?
				servlet :
				new JsonRpcHttpFixtureServlet(dispatcher, maxBodySize.toInt(), onRequest)::serve;
			HttpServer.Builder builder = HttpServer.builder(reactor, effectiveServlet)
				.withListenPort(0);
			if (inspector != null) builder.withInspector(inspector);
			return new JsonRpcHttpTestServer(builder.build(), dispatcher, effectiveServlet);
		}
	}

	/**
	 * Binds port {@code 0} and starts accepting. Call {@link #address()} afterwards — the bound port
	 * exists only once the kernel has assigned it.
	 */
	public void listen() throws IOException {
		server.listen();
		boundAddresses = server.getBoundAddresses();
	}

	/** The actual address the kernel bound {@code :0} to. Valid after {@link #listen()}. */
	public InetSocketAddress address() {
		return boundAddresses.get(0);
	}

	/** The actual port the kernel bound {@code :0} to. Valid after {@link #listen()}. */
	public int port() {
		return address().getPort();
	}

	/** The dispatcher answering {@code test.*} — for probes that want to observe or wrap it. */
	public JsonRpcDispatcher dispatcher() {
		return dispatcher;
	}

	/** The effective servlet mounted on the server — for probes that wrap it to count entries. */
	public AsyncServlet servlet() {
		return servlet;
	}

	/**
	 * Closes the server <b>on its own reactor thread</b> (a {@code Reactor.submit}), which is the
	 * only safe way when the loop runs on a dedicated thread — see the raw-socket pattern in
	 * {@code core-http}'s {@code HttpServerTest}. Blocks until the close task has run.
	 */
	public Future<?> closeFuture() {
		return server.closeFuture();
	}

	/**
	 * The fixture servlet behind the harness: {@code loadBody(bound)} → {@code asArray()} → dispatch →
	 * {@code 204} for a zero-length result, {@code 200} + {@code application/json} otherwise.
	 * <p>
	 * Deliberately minimal: no method or media-type gate yet — those are {@code JsonRpcServlet}'s
	 * (FR-015, FR-016), and the probes this phase runs POST {@code application/json} only. The
	 * dispatcher is total, so the only failure source on this path is {@code loadBody} itself; what
	 * a body-size violation produces on the wire is exactly what probe R2 observes.
	 * <p>
	 * <b>Ownership</b>: {@code loadBody(...)} returns the body but the {@code HttpRequest} keeps it
	 * and recycles it when the connection recycles the request — recycling the returned buffer
	 * again (which {@code asArray()} does) double-recycles. The body is therefore
	 * {@link HttpRequest#takeBody() taken} first, so {@code asArray()} is the single release. This
	 * is a probe finding (recorded in research.md §5): the real servlet must follow the same
	 * take-then-convert order.
	 */
	private static final class JsonRpcHttpFixtureServlet {
		private final JsonRpcDispatcher dispatcher;
		private final int maxBodySize;
		private final @Nullable Consumer<HttpRequest> onRequest;

		private JsonRpcHttpFixtureServlet(JsonRpcDispatcher dispatcher, int maxBodySize, @Nullable Consumer<HttpRequest> onRequest) {
			this.dispatcher = dispatcher;
			this.maxBodySize = maxBodySize;
			this.onRequest = onRequest;
		}

		Promise<HttpResponse> serve(HttpRequest request) {
			if (onRequest != null) onRequest.accept(request);
			return request.loadBody(maxBodySize)
				.then($ -> dispatcher.dispatch(request.takeBody().asArray()))
				.then(response -> response.length == 0 ?
					HttpResponse.ofCode(204).toPromise() :
					HttpResponse.ok200()
						.withHeader(CONTENT_TYPE, "application/json")
						.withBody(response)
						.toPromise());
		}
	}
}
