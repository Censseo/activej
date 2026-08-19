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

package io.activej.jsonrpc.transport.ws;

import io.activej.common.Checks;
import io.activej.common.builder.AbstractBuilder;
import io.activej.common.ref.Ref;
import io.activej.http.IWebSocket;
import io.activej.http.WebSocketServlet;
import io.activej.json.JsonCodecFactory;
import io.activej.jsonrpc.service.JsonRpcDispatcher;
import io.activej.reactor.Reactor;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

import static io.activej.common.Checks.checkState;
import static io.activej.reactor.Reactive.checkInReactorThread;

/**
 * The WebSocket endpoint and its session registry (research D7, FR-030…FR-037): a
 * {@link WebSocketServlet} whose {@code onWebSocket} wraps each accepted connection in a
 * {@link JsonRpcWsSession}, registers it in the live-session set, and lets the connection's
 * {@code onClosed} deregister it — the <b>single</b> deregistration path (FR-032).
 * <p>
 * <b>The dispatcher is shared, the client is per-session (FR-030, FR-050).</b> Every session
 * dispatches its inbound calls through the one {@link JsonRpcDispatcher} the servlet was built with,
 * and answers server-initiated calls through its own {@code JsonRpcClient} — configured with the
 * {@code codecFactory} and {@code failureHandler} this servlet's builder received. Those
 * pass-throughs are the whole of the server→client direction: no second mechanism is added.
 * <p>
 * <b>Session cardinality (FR-035).</b> The registry mirrors open connections — it adds no second
 * bound; the connection tier's own limits (file descriptors, the host server's sweeps) govern it.
 * {@link #sessions()} publishes a reactor-confined snapshot; {@link #broadcast(Class, Consumer)}
 * iterates one such snapshot, and a session's failure is routed to that session's failure handling —
 * never thrown into the iteration (FR-033).
 * <p>
 * <b>Admission (FR-036).</b> The application-level gate is core-http's {@code onRequest} seam,
 * composed in front of this servlet (e.g. a {@code BasicAuthServlet} decorator): a non-{@code 101}
 * answer refuses the upgrade before any session exists.
 * <p>
 * <b>Threading.</b> Reactive via {@link WebSocketServlet}: {@link #sessions()} and
 * {@link #broadcast(Class, Consumer)} open with {@code checkInReactorThread(this)}, and the builder
 * refuses — under {@code CHECKS} — a dispatcher living on a different reactor than the servlet
 * (FR-031).
 */
public final class JsonRpcWsServlet extends WebSocketServlet {
	private static final boolean CHECKS = Checks.isEnabled(JsonRpcWsServlet.class);

	private final JsonRpcDispatcher dispatcher;
	private JsonCodecFactory codecFactory;
	private Consumer<Exception> failureHandler;
	private final Set<JsonRpcWsSession> sessions = new HashSet<>();

	private JsonRpcWsServlet(Reactor reactor, JsonRpcDispatcher dispatcher) {
		super(reactor);
		this.dispatcher = dispatcher;
		this.codecFactory = JsonCodecFactory.defaultInstance();
		this.failureHandler = e -> reactor.logFatalError(e, this);
	}

	/**
	 * Starts a servlet on {@code reactor} dispatching every session's inbound calls to
	 * {@code dispatcher}.
	 *
	 * @throws NullPointerException if either argument is {@code null}
	 */
	public static Builder builder(Reactor reactor, JsonRpcDispatcher dispatcher) {
		return new JsonRpcWsServlet(
			Objects.requireNonNull(reactor, "reactor"),
			Objects.requireNonNull(dispatcher, "dispatcher")).new Builder();
	}

	/**
	 * The optional configuration — both are pass-throughs to every per-session client, so a session
	 * built later resolves codecs and reports failures exactly as its servlet does.
	 */
	public final class Builder extends AbstractBuilder<Builder, JsonRpcWsServlet> {
		private Builder() {}

		/** The factory every parameter and result codec of the per-session clients is resolved through. */
		public Builder withCodecFactory(JsonCodecFactory codecFactory) {
			checkNotBuilt(this);
			JsonRpcWsServlet.this.codecFactory = Objects.requireNonNull(codecFactory, "codecFactory");
			return this;
		}

		/**
		 * Where a per-session failure with no awaiting caller goes — e.g. a broadcast's send failure
		 * to a dead session (FR-033). Defaults to {@code Reactor.logFatalError}.
		 */
		public Builder withFailureHandler(Consumer<Exception> failureHandler) {
			checkNotBuilt(this);
			JsonRpcWsServlet.this.failureHandler = Objects.requireNonNull(failureHandler, "failureHandler");
			return this;
		}

		/**
		 * @throws IllegalStateException under {@code CHECKS} if {@code dispatcher} lives on a
		 *                              different reactor than {@code reactor} (FR-031)
		 */
		@Override
		protected JsonRpcWsServlet doBuild() {
			if (CHECKS) checkState(dispatcher.getReactor() == reactor,
				"the dispatcher lives on a different reactor than the servlet (FR-031)");
			return JsonRpcWsServlet.this;
		}
	}

	/**
	 * Wraps one accepted connection in a session and registers it (FR-032). The session's client
	 * installs itself as the transport's listener during construction, so inbound dispatch starts
	 * here — but nothing is dispatched until the reactor runs, by which time this method has
	 * returned and the session is in the registry: registered before any inbound message can be
	 * dispatched.
	 * <p>
	 * <b>The pre-closed websocket (FR-032, review T030).</b> When the websocket is already closed
	 * when the read loop starts, {@code readMessage()} fails <i>synchronously</i> inside the session
	 * constructor: the transport's close latch fires {@code onClosed} before the {@code Ref} holds
	 * the session, the deregistration callback removes {@code null} (a no-op), and the transport
	 * latch is consumed — so registering afterwards would leave a session that is never
	 * deregistered. The {@code webSocket.isClosed()} check after construction catches exactly this
	 * case: a close that happened during construction has already fired {@code onClosed}, so the
	 * session must not be registered. Any later close fires {@code onClosed} with the {@code Ref}
	 * set and deregisters normally.
	 */
	@Override
	protected void onWebSocket(IWebSocket webSocket) {
		JsonRpcWsTransport transport = JsonRpcWsTransport.of(reactor, webSocket);
		Ref<JsonRpcWsSession> sessionRef = new Ref<>();
		JsonRpcWsSession session = new JsonRpcWsSession(
			reactor, transport, dispatcher, codecFactory, failureHandler,
			e -> sessions.remove(sessionRef.get()));
		sessionRef.set(session);
		if (webSocket.isClosed()) {
			// the transport's close latch fired during construction — the deregistration callback
			// already ran (removing null); registering now would leak a zombie session (FR-032/FR-035)
			return;
		}
		sessions.add(session);
	}

	/**
	 * The live sessions as a reactor-confined, read-only snapshot (FR-033): a copy that does not
	 * reflect registrations or deregistrations that happen after the call.
	 */
	public Set<JsonRpcWsSession> sessions() {
		checkInReactorThread(this);
		return Set.copyOf(sessions);
	}

	/**
	 * Broadcasts a notification to every live session (FR-033): takes the snapshot, invokes
	 * {@code invocation} on each session's proxy for {@code clientInterface}, and routes one
	 * session's failure to that session's failure handling — it never aborts the iteration. A
	 * notification is never answered (§4.1), so the clients receive one document and send nothing
	 * back.
	 */
	public <T> void broadcast(Class<T> clientInterface, Consumer<T> invocation) {
		checkInReactorThread(this);
		Objects.requireNonNull(clientInterface, "clientInterface");
		Objects.requireNonNull(invocation, "invocation");
		for (JsonRpcWsSession session : sessions()) {
			try {
				invocation.accept(session.proxy(clientInterface));
			} catch (RuntimeException e) {
				session.reportFailure(e);
			}
		}
	}
}
