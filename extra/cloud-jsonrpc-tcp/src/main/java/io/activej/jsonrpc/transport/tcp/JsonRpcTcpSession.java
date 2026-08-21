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

import io.activej.async.process.AsyncCloseable;
import io.activej.json.JsonCodecFactory;
import io.activej.jsonrpc.service.JsonRpcClient;
import io.activej.jsonrpc.service.JsonRpcDispatcher;
import io.activej.jsonrpc.transport.JsonRpcTransport;
import io.activej.promise.Promise;
import io.activej.reactor.AbstractReactive;
import io.activej.reactor.Reactor;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.function.Consumer;

import static io.activej.reactor.Reactive.checkInReactorThread;

/**
 * The server-side view of one framed-TCP connection (research D6, FR-034/FR-050): the connection's
 * {@link JsonRpcTcpTransport} plus one {@link JsonRpcClient} built on it with
 * {@code withPeerHandler(serverDispatcher)}. That one client is the whole server&rarr;client call
 * path — {@link #proxy(Class)} for the client-facing direction, {@link #inFlightCount()} for the
 * calls the server initiated, {@link #closeEx(Exception)} for the lifecycle purge — <i>and</i> the
 * inbound dispatch path, because its peer handler is the server's shared dispatcher. No parallel
 * mechanism is added (FR-050); the independent {@code id} spaces of the two directions fall out of
 * the two peers owning two tables and two counters (FR-051).
 * <p>
 * <b>Not user-constructible (FR-034).</b> The package-private constructor is called only by
 * {@link JsonRpcTcpServer#serve}, which supplies the dispatcher, the codec factory and the failure
 * handler that flow from the server's builder into the per-session client, plus a deregistration
 * callback. The session exposes no raw {@code ITcpSocket} — framing stays encapsulated inside the
 * transport, and a caller that could write bytes beside it could desynchronise the stream.
 * <p>
 * <b>Close observation (FR-032).</b> The client owns the transport's single listener slot (its
 * {@code doBuild()} installs itself), so this session routes the transport through a thin
 * {@link JsonRpcTransport} wrapper that forwards every call but interposes on {@code onClosed}: the
 * client's own close handling (the single-removal-path purge of FR-053) runs first, then the
 * server's deregistration callback fires — exactly once, because the transport's latch owns
 * exactly-once. A local {@link #closeEx(Exception)} takes the same path, so close and deregistration
 * can never diverge.
 * <p>
 * <b>Threading.</b> Reactive: every public method opens with {@code checkInReactorThread(this)}; the
 * client's transport callbacks carry the same guard, inherited.
 */
public final class JsonRpcTcpSession extends AbstractReactive implements AsyncCloseable {
	private final JsonRpcClient client;
	private final Consumer<Exception> failureHandler;

	JsonRpcTcpSession(
		Reactor reactor,
		JsonRpcTcpTransport transport,
		JsonRpcDispatcher dispatcher,
		JsonCodecFactory codecFactory,
		Consumer<Exception> failureHandler,
		Consumer<Exception> onClosed
	) {
		super(reactor);
		this.failureHandler = Objects.requireNonNull(failureHandler, "failureHandler");
		Objects.requireNonNull(transport, "transport");
		Objects.requireNonNull(dispatcher, "dispatcher");
		Objects.requireNonNull(codecFactory, "codecFactory");
		Objects.requireNonNull(onClosed, "onClosed");
		this.client = JsonRpcClient.builder(reactor, new CloseObservingTransport(transport, onClosed))
			.withPeerHandler(dispatcher)
			.withCodecFactory(codecFactory)
			.withFailureHandler(failureHandler)
			.build();
	}

	/**
	 * The client-facing proxy for one annotated interface, validated at the first {@code proxy} call by
	 * the shared contract rules (FR-054: several distinct interfaces are proxiable on one session, each
	 * validated once). Delegates to the session's client — i.e. to the server&rarr;client direction.
	 * <p>
	 * On a closed session the proxy still exists but its calls fail immediately with the close cause
	 * (FR-055): there is no queue of calls waiting for a connection that is gone.
	 */
	public <T> T proxy(Class<T> serviceType) {
		checkInReactorThread(this);
		return client.proxy(serviceType);
	}

	/**
	 * The number of server-initiated calls awaiting an answer (FR-034) — the observed in-flight count of
	 * the session's client, and where feature 09's per-connection bound will later attach.
	 */
	public int inFlightCount() {
		checkInReactorThread(this);
		return client.inFlightCount();
	}

	/**
	 * Closes the session: idempotent, purges every server-initiated in-flight call through the client's
	 * single removal path with {@code e}, and closes the connection. Deregistration happens through the
	 * same {@code onClosed} path a peer close takes (FR-053) — there is no second removal site.
	 */
	@Override
	public void closeEx(Exception e) {
		checkInReactorThread(this);
		Objects.requireNonNull(e, "e");
		client.closeEx(e);
	}

	/**
	 * Where a per-session failure with no awaiting caller goes — the broadcast containment path of
	 * FR-033. Mirrors {@code JsonRpcClient.reportFailure}: a failure handler that itself fails must not
	 * become a second failure.
	 */
	void reportFailure(Exception e) {
		try {
			failureHandler.accept(e);
		} catch (RuntimeException ignored) {
			// a failure handler that itself fails must not propagate into the broadcast iteration (FR-033)
		}
	}

	@Override
	public String toString() {
		return "JsonRpcTcpSession{" + client + '}';
	}

	/**
	 * A forwarding {@link JsonRpcTransport} whose {@code setListener} wraps the incoming listener so that
	 * {@code onClosed} reaches both the client (which purges) and the server (which deregisters).
	 * Everything else — {@code send}, {@code closeEx}, the read loop — is the delegate's own.
	 */
	private static final class CloseObservingTransport implements JsonRpcTransport {
		private final JsonRpcTransport delegate;
		private final Consumer<Exception> onClosed;
		private @Nullable Listener listener;

		private CloseObservingTransport(JsonRpcTransport delegate, Consumer<Exception> onClosed) {
			this.delegate = delegate;
			this.onClosed = onClosed;
		}

		@Override
		public Promise<Void> send(byte[] document) {
			return delegate.send(document);
		}

		@Override
		public void setListener(Listener listener) {
			this.listener = listener;
			delegate.setListener(new Listener() {
				@Override
				public void onDocument(byte[] document) {
					CloseObservingTransport.this.listener.onDocument(document);
				}

				@Override
				public void onClosed(@Nullable Exception e) {
					try {
						CloseObservingTransport.this.listener.onClosed(e);
					} finally {
						// deregistration must happen even if the client's own close handling threw
						onClosed.accept(e);
					}
				}
			});
		}

		@Override
		public void closeEx(Exception e) {
			delegate.closeEx(e);
		}
	}
}
