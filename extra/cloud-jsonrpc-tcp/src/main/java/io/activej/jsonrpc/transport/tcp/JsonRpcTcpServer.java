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
import io.activej.common.Checks;
import io.activej.common.MemSize;
import io.activej.common.ref.Ref;
import io.activej.json.JsonCodecFactory;
import io.activej.jsonrpc.JsonRpcLimits;
import io.activej.jsonrpc.service.JsonRpcContractException;
import io.activej.jsonrpc.service.JsonRpcDispatcher;
import io.activej.net.AbstractReactiveServer;
import io.activej.net.socket.tcp.ITcpSocket;
import io.activej.promise.SettableCallback;
import io.activej.reactor.nio.NioReactor;
import org.jetbrains.annotations.Nullable;

import java.net.InetAddress;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

import static io.activej.common.Checks.checkArgument;
import static io.activej.common.Checks.checkState;
import static io.activej.reactor.Reactive.checkInReactorThread;

/**
 * The framed-TCP endpoint and its session registry (research D6/D7, FR-030…FR-039): an
 * {@link AbstractReactiveServer} whose {@code serve} wraps each accepted socket in a
 * {@link JsonRpcTcpTransport}, wraps that in a {@link JsonRpcTcpSession}, registers the session in
 * the live-session set, and lets the connection's {@code onClosed} deregister it — the <b>single</b>
 * deregistration path (FR-032).
 * <p>
 * <b>Composed, not built.</b> The accept loop, the listen addresses, {@code withAcceptOnce},
 * {@code withAcceptFilter}, the socket settings, SSL composition and {@code getBoundAddresses()} —
 * which is how a port-{@code 0} bind is discovered (ADR-028) — are all the base class's. What this
 * class adds is the registry, the drain and three options.
 * <p>
 * <b>The dispatcher is shared, the client is per-session (FR-030, FR-050).</b> Every session
 * dispatches its inbound calls through the one {@link JsonRpcDispatcher} the server was built with,
 * and answers server-initiated calls through its own {@code JsonRpcClient}, configured with the
 * {@code codecFactory} and {@code failureHandler} this builder received.
 * <p>
 * <b>Session cardinality (FR-035).</b> The registry mirrors open connections and adds no second
 * bound: the connection tier's own limits — file descriptors, the accept backlog, and the optional
 * {@code SocketSettings} implementation timeouts — govern it. {@link #sessions()} publishes a
 * reactor-confined snapshot; {@link #broadcast(Class, Consumer)} iterates one such snapshot, and a
 * session's failure is routed to that session's failure handling rather than thrown into the
 * iteration (FR-033).
 * <p>
 * <b>Admission (FR-036).</b> The application-level gate is the platform's existing
 * {@code withAcceptFilter(...)} seam. No JSON-RPC-level handshake gates a connection — there is no
 * preamble on this wire at all (FR-010).
 * <p>
 * <b>Closing drains (FR-038).</b> {@code close()} stops accepting (base class), then closes every
 * live session — each purging its own in-flight calls with the close cause — and completes only once
 * the registry has emptied, which is {@code RpcServer}'s pattern.
 * <p>
 * <b>Threading.</b> Reactive via {@link AbstractReactiveServer}: {@link #sessions()} and
 * {@link #broadcast(Class, Consumer)} open with {@code checkInReactorThread(this)}, and the builder
 * refuses — under {@code CHECKS} — a dispatcher living on a different reactor than the server
 * (FR-031). A publisher on another thread hops explicitly (FR-037); the guard is the enforcement.
 */
public final class JsonRpcTcpServer extends AbstractReactiveServer {
	private static final boolean CHECKS = Checks.isEnabled(JsonRpcTcpServer.class);

	private final JsonRpcDispatcher dispatcher;
	private final Set<JsonRpcTcpSession> sessions = new HashSet<>();

	private MemSize maxMessageSize = JsonRpcLimits.MAX_BODY_SIZE;
	private JsonCodecFactory codecFactory = JsonCodecFactory.defaultInstance();
	private Consumer<Exception> failureHandler;

	private @Nullable SettableCallback<Void> closeCallback;

	private JsonRpcTcpServer(NioReactor reactor, JsonRpcDispatcher dispatcher) {
		super(reactor);
		this.dispatcher = dispatcher;
		this.failureHandler = e -> reactor.logFatalError(e, this);
	}

	/**
	 * Starts a server on {@code reactor} dispatching every session's inbound calls to
	 * {@code dispatcher} — the single server-side service table.
	 *
	 * @throws NullPointerException if either argument is {@code null}
	 */
	public static Builder builder(NioReactor reactor, JsonRpcDispatcher dispatcher) {
		return new JsonRpcTcpServer(
			Objects.requireNonNull(reactor, "reactor"),
			Objects.requireNonNull(dispatcher, "dispatcher")).new Builder();
	}

	/**
	 * This server's own three options, on top of everything
	 * {@link AbstractReactiveServer.Builder} already offers — {@code withListenAddress(es)},
	 * {@code withListenPort}, {@code withAcceptOnce}, {@code withAcceptFilter},
	 * {@code withSocketSettings} and the SSL listen variants, none of which is re-declared here.
	 */
	public final class Builder extends AbstractReactiveServer.Builder<Builder, JsonRpcTcpServer> {
		private Builder() {}

		/**
		 * The transport tier of the two-tier size bound, handed to every session's framing decoder and
		 * applied <b>during</b> accumulation (FR-016). Defaults to {@link JsonRpcLimits#MAX_BODY_SIZE}.
		 * <p>
		 * ⚠ With the two tiers equal — the default — the transport-tier close wins and the envelope's
		 * {@code -32001 Request too large} answer is unreachable. Set this strictly above
		 * {@code JsonRpcLimits.MAX_BODY_SIZE} to make that answer observable.
		 *
		 * @throws IllegalArgumentException under {@code CHECKS} if the tier is not positive
		 */
		public Builder withMaxMessageSize(MemSize maxMessageSize) {
			checkNotBuilt(this);
			Objects.requireNonNull(maxMessageSize, "maxMessageSize");
			if (CHECKS) checkArgument(maxMessageSize.toInt() > 0, "maxMessageSize must be positive");
			JsonRpcTcpServer.this.maxMessageSize = maxMessageSize;
			return this;
		}

		/** The factory every parameter and result codec of the per-session clients is resolved through. */
		public Builder withCodecFactory(JsonCodecFactory codecFactory) {
			checkNotBuilt(this);
			JsonRpcTcpServer.this.codecFactory = Objects.requireNonNull(codecFactory, "codecFactory");
			return this;
		}

		/**
		 * Where a per-session failure with no awaiting caller goes — e.g. a broadcast's send failure to a
		 * session that has just died (FR-033). Defaults to {@code Reactor.logFatalError}.
		 */
		public Builder withFailureHandler(Consumer<Exception> failureHandler) {
			checkNotBuilt(this);
			JsonRpcTcpServer.this.failureHandler = Objects.requireNonNull(failureHandler, "failureHandler");
			return this;
		}

		/**
		 * @throws IllegalStateException under {@code CHECKS} if {@code dispatcher} lives on a different
		 *                               reactor than this server (FR-031)
		 */
		@Override
		protected JsonRpcTcpServer doBuild() {
			if (CHECKS) checkState(dispatcher.getReactor() == reactor,
				"the dispatcher lives on a different reactor than the server (FR-031)");
			return JsonRpcTcpServer.this;
		}
	}

	/**
	 * Wraps one accepted connection in a session and registers it (FR-032). The session's client installs
	 * itself as the transport's listener during construction, so the read loop starts here — but nothing
	 * is decoded until the reactor runs, by which time this method has returned and the session is in the
	 * registry: <b>registered before any inbound document can be dispatched</b>.
	 * <p>
	 * <b>The zombie guard (research risk 2).</b> A connection that died while the session was being
	 * constructed — a peer that reset between the accept and the first read — has already fired the
	 * transport's exactly-once {@code onClosed}: the deregistration callback ran while the {@link Ref}
	 * still held {@code null} (removing {@code null}, a no-op) and the latch is spent. Registering
	 * afterwards would leave an entry nothing can ever remove, so the latch is consulted instead.
	 */
	@Override
	protected void serve(ITcpSocket socket, InetAddress remoteAddress) {
		checkInReactorThread(this);
		JsonRpcTcpTransport transport = JsonRpcTcpTransport.builder(reactor, socket)
			.withMaxMessageSize(maxMessageSize)
			.build();
		Ref<JsonRpcTcpSession> sessionRef = new Ref<>();
		JsonRpcTcpSession session = new JsonRpcTcpSession(
			reactor, transport, dispatcher, codecFactory, failureHandler,
			e -> remove(sessionRef.get()));
		sessionRef.set(session);
		if (transport.isClosed()) {
			// the close latch fired during construction — the deregistration callback already ran on a
			// null reference, so registering now would leak a zombie session (FR-032/FR-035)
			return;
		}
		sessions.add(session);
	}

	/**
	 * The live sessions as a reactor-confined, read-only snapshot (FR-033): a copy that does not reflect
	 * registrations or deregistrations happening after the call, so an iteration cannot be invalidated by
	 * a connection dying inside it.
	 */
	public Set<JsonRpcTcpSession> sessions() {
		checkInReactorThread(this);
		return Set.copyOf(sessions);
	}

	/**
	 * Broadcasts to every live session (FR-033): takes the snapshot, invokes {@code invocation} on each
	 * session's proxy for {@code clientInterface}, and routes one session's failure to that session's own
	 * failure handling — it never aborts the iteration, because one dead connection must not cost the
	 * others their message.
	 * <p>
	 * One exception is <b>not</b> contained: a {@link JsonRpcContractException} from
	 * {@code proxy(clientInterface)}. A broken interface is the broadcaster's own programming error and
	 * every session's proxy rejects it identically, so it propagates to the caller at the first session —
	 * before any invocation ran — rather than being reported once per session, which only the caller can
	 * act on.
	 */
	public <T> void broadcast(Class<T> clientInterface, Consumer<T> invocation) {
		checkInReactorThread(this);
		Objects.requireNonNull(clientInterface, "clientInterface");
		Objects.requireNonNull(invocation, "invocation");
		for (JsonRpcTcpSession session : sessions()) {
			try {
				invocation.accept(session.proxy(clientInterface));
			} catch (JsonRpcContractException e) {
				// not a per-session fault: the contract is a property of the interface, so every session
				// refuses it the same way — the caller, the only one who can fix it, gets it once
				throw e;
			} catch (RuntimeException e) {
				session.reportFailure(e);
			}
		}
	}

	/**
	 * The drain (FR-038): the base class has already stopped accepting, so this closes every live session
	 * — each purging its own in-flight calls with the close cause — and completes when the registry has
	 * emptied. The callback is armed <b>before</b> the loop because a session deregisters synchronously
	 * inside {@code closeEx}: arming afterwards, as an asynchronous protocol could afford to, would miss
	 * the very removal that empties the set.
	 */
	@Override
	protected void onClose(SettableCallback<Void> cb) {
		if (sessions.isEmpty()) {
			cb.set(null);
			return;
		}
		closeCallback = cb;
		for (JsonRpcTcpSession session : List.copyOf(sessions)) {
			session.closeEx(new AsyncCloseException("the server is closing"));
		}
	}

	/** The registry's single removal path, reached only from a session's exactly-once {@code onClosed}. */
	private void remove(@Nullable JsonRpcTcpSession session) {
		// null while the zombie guard's session reference is still unset — a no-op by construction
		if (session == null) return;
		if (!sessions.remove(session)) return;
		if (closeCallback != null && sessions.isEmpty()) {
			SettableCallback<Void> cb = closeCallback;
			closeCallback = null;
			cb.set(null);
		}
	}
}
