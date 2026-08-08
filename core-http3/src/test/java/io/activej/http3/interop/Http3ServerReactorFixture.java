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

package io.activej.http3.interop;

import io.activej.common.function.RunnableEx;
import io.activej.common.function.SupplierEx;
import io.activej.eventloop.Eventloop;
import io.activej.http.AsyncServlet;
import io.activej.http3.Http3Server;
import io.activej.http3.testutil.Http3TestTls;
import io.activej.reactor.Reactor;
import io.activej.test.EventloopThread;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.function.Function;

import org.jetbrains.annotations.Nullable;

/**
 * An {@link Http3Server} on its own reactor thread, bound to the <b>loopback</b> address on a
 * {@code :0} port that is read back off the server's bound-address accessor — the fixture the
 * automated interop suite (T009, FR-009, FR-011, research D10) and the real-socket client suite
 * stand a server up with.
 * <p>
 * <b>Threading</b> (FR-012): the loop, the submit bridge and the bounded teardown all come from
 * {@link EventloopThread}. The JUnit thread never touches a reactive component — construction,
 * teardown, counter reads and every other reactor-touching operation run through
 * {@link #submit(RunnableEx)}, which posts to the reactor and blocks the caller.
 * {@code EventloopRule} is deliberately not used: the JUnit thread must be free to block on a real
 * subprocess for seconds while the reactor keeps serving (research D7).
 * <p>
 * <b>Socket</b> (feature 008, T029): the server is built with
 * {@code withListenAddress(loopback:0)} and {@code listen()} opens the socket itself; the assigned
 * port is read through {@code Http3Server.getBoundAddress()} (research D11, resolved). This
 * replaces the earlier workaround of binding a {@link java.nio.channels.DatagramChannel} by hand
 * and handing it back via {@code withSocket} — see {@code specs/008-h3-api-gaps} for why that
 * existed (a pre-bound channel guaranteed no free-then-rebind window; the accessor's read-back
 * keeps the same guarantee, since {@code listen()} binds and completes synchronously).
 * <p>
 * <b>Teardown</b> (FR-010): {@link #close()} is idempotent and runs on every path. Whatever stage
 * setup reached — server or nothing — is closed on the reactor via the {@link EventloopThread#onClose}
 * action registered below, and only then is the thread joined, so a failed assertion, a timeout or
 * a throw during setup leaves nothing for {@code ByteBufRule} to find.
 */
public final class Http3ServerReactorFixture implements AutoCloseable {
	private final EventloopThread loop = EventloopThread.create("http3-interop-server");
	private final Function<Reactor, AsyncServlet> servletFactory;

	private @Nullable Http3Server server;
	private InetSocketAddress boundAddress;
	private int port;

	/**
	 * Builds and listens the server, blocking the caller until it is accepting on the loopback
	 * address. The servlet factory is applied on the reactor thread, so a {@code RoutingServlet}
	 * built by it captures exactly this reactor.
	 */
	public Http3ServerReactorFixture(Function<Reactor, AsyncServlet> servletFactory) {
		this.servletFactory = servletFactory;
		// Registered before the server exists: closeResources() picks whatever stage setup reached,
		// so a throw halfway through startServer() still releases the socket.
		loop.onClose(this::closeResources);
		try {
			loop.submit(this::startServer);
		} catch (RuntimeException | Error e) {
			try {
				close();
			} catch (RuntimeException | Error teardown) {
				// a teardown that also fails must not mask what actually broke setup
				e.addSuppressed(teardown);
			}
			throw new IllegalStateException("Failed to start the interop HTTP/3 server", e);
		}
	}

	/** The bound loopback address — the {@code :0} port the OS assigned. */
	public InetSocketAddress boundAddress() {
		return boundAddress;
	}

	/** The port curl is pointed at. */
	public int port() {
		return port;
	}

	/** Runs an action on the reactor thread and blocks the caller until it has run (FR-012). */
	public void submit(RunnableEx action) {
		loop.submit(action);
	}

	/** Runs a computation on the reactor thread and returns its result (FR-012). */
	public <T> T submit(SupplierEx<T> computation) {
		return loop.submit(computation);
	}

	/** {@code Http3Server.connectionsAccepted()}, read on the reactor thread. */
	public long connectionsAccepted() {
		return submit(() -> server.connectionsAccepted());
	}

	/** {@code Http3Server.requestsServed()}, read on the reactor thread. */
	public long requestsServed() {
		return submit(() -> server.requestsServed());
	}

	/**
	 * Idempotent teardown (FR-010): closes whatever stage setup reached — server, socket or raw
	 * channel — on the reactor, then joins the reactor thread, then {@code ByteBufRule} evaluates.
	 */
	@Override
	public void close() {
		loop.close();
	}

	/** Runs on the reactor thread during {@link #close()}: closes the server if setup reached it. */
	private void closeResources() {
		if (server != null) {
			server.close();
		}
	}

	/** Runs on the reactor thread: listen on {@code :0} on loopback, read the assigned port back (T029). */
	private void startServer() {
		Eventloop eventloop = loop.eventloop();

		// The servlet is built here, on the reactor thread, so a RoutingServlet built by the factory
		// captures exactly this reactor (WI-2) — the JUnit thread must never construct a reactive piece.
		AsyncServlet servlet = servletFactory.apply(eventloop);

		Http3Server server = Http3Server.builder(eventloop, servlet)
			.withListenAddress(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0))
			.withServerIdentity(Http3TestTls.devIdentity())
			.build();
		// listen() binds and completes synchronously, so the accessor is readable right here — on the
		// reactor thread, where its guard permits it — and the port the client is pointed at is the
		// one the OS assigned (feature 008, T029; the pre-bound-channel workaround is gone).
		server.listen();
		this.server = server;
		this.boundAddress = server.getBoundAddress();
		this.port = this.boundAddress.getPort();
	}
}
