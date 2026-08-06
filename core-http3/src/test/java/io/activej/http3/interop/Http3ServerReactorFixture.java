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

import io.activej.async.callback.AsyncComputation;
import io.activej.common.function.RunnableEx;
import io.activej.common.function.SupplierEx;
import io.activej.eventloop.Eventloop;
import io.activej.http.AsyncServlet;
import io.activej.http3.Http3Server;
import io.activej.http3.testutil.Http3TestTls;
import io.activej.net.socket.udp.UdpSocket;
import io.activej.reactor.Reactor;
import io.activej.reactor.net.DatagramSocketSettings;
import io.activej.reactor.nio.NioReactor;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.channels.DatagramChannel;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

import org.jetbrains.annotations.Nullable;

/**
 * An {@link Http3Server} on its own reactor thread, bound to the <b>loopback</b> address on a
 * {@code :0} port that is read off the kept socket — the fixture the automated interop suite
 * (T009, FR-009, FR-011, research D10) and the real-socket client suite stand a server up with.
 * <p>
 * <b>Threading</b> (FR-012): the fixture owns one {@link Eventloop} on a dedicated thread. The JUnit
 * thread never touches a reactive component — construction, teardown, counter reads and every other
 * reactor-touching operation run through {@link #submit(...)}, which posts to the reactor and blocks
 * the caller on the resulting {@link CompletableFuture}. {@code EventloopRule} is deliberately not
 * used: the JUnit thread must be free to block on a real subprocess for seconds while the reactor
 * keeps serving (research D7).
 * <p>
 * <b>Socket</b> (research D10): a {@link DatagramChannel} is bound to {@code :0} on the loopback
 * address, the assigned port is read off the channel, and the channel is <b>kept</b> — wrapped with
 * {@link UdpSocket#connect} and handed to {@link Http3Server.Builder#withSocket} — so there is no
 * free-then-rebind window and no other process can take the port.
 * <p>
 * <b>Teardown</b> (FR-010): {@link #close()} is idempotent and runs on every path. It closes the
 * server on the reactor (or the socket, or the raw channel — whichever stage setup reached), joins
 * the reactor thread, and only then returns, so a failed assertion, a timeout or a throw during
 * setup leaves nothing for {@code ByteBufRule} to find. The loop is held open for the fixture's
 * life with {@code keepAlive(true)} — a setup failure that never registered a selector key must
 * not let the loop exit and strand the teardown submit — and released by {@code close()}. As a
 * last resort, after a bounded join, the loop is forced out with {@code breakEventloop()}.
 */
public final class Http3ServerReactorFixture implements AutoCloseable {
	private static final long REACTOR_CALL_TIMEOUT_SECONDS = 10;
	private static final long JOIN_TIMEOUT_MILLIS = 10_000;
	private static final long BREAK_JOIN_TIMEOUT_MILLIS = 2_000;

	private final Eventloop eventloop = Eventloop.create();
	private final Thread reactorThread;
	private final Function<Reactor, AsyncServlet> servletFactory;

	private @Nullable DatagramChannel channel;
	private @Nullable UdpSocket socket;
	private @Nullable Http3Server server;
	private InetSocketAddress boundAddress;
	private int port;

	private volatile boolean closed;

	/**
	 * Builds and listens the server, blocking the caller until it is accepting on the loopback
	 * address. The servlet factory is applied on the reactor thread, so a {@code RoutingServlet}
	 * built by it captures exactly this reactor.
	 */
	public Http3ServerReactorFixture(Function<Reactor, AsyncServlet> servletFactory) {
		this.servletFactory = servletFactory;
		// The loop must outlive a failed setup: a channel that was never registered with the
		// selector keeps no key, so without keepAlive the loop would exit right after the failing
		// setup task and the teardown submit in close() would never run.
		eventloop.keepAlive(true);
		this.reactorThread = new Thread(eventloop, "http3-interop-server");
		reactorThread.setDaemon(true);
		CompletableFuture<BoundServer> built = eventloop.submit(AsyncComputation.of(this::startServer));
		reactorThread.start();
		try {
			BoundServer bound = built.get(REACTOR_CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
			this.server = bound.server();
			this.boundAddress = bound.boundAddress();
			this.port = this.boundAddress.getPort();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			close();
			throw new IllegalStateException("Interrupted while starting the interop HTTP/3 server", e);
		} catch (ExecutionException | TimeoutException e) {
			close();
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
		await(eventloop.submit(action));
	}

	/** Runs a computation on the reactor thread and returns its result (FR-012). */
	public <T> T submit(SupplierEx<T> computation) {
		return await(eventloop.submit(AsyncComputation.of(computation)));
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
		if (closed) return;
		closed = true;
		Exception failure = null;
		try {
			eventloop.submit(() -> {
				if (server != null) {
					server.close();
				} else if (socket != null) {
					socket.close();
				} else if (channel != null && channel.isOpen()) {
					try {
						channel.close();
					} catch (IOException e) {
						throw new IllegalStateException("Failed to close the raw datagram channel", e);
					}
				}
			}).get(REACTOR_CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			failure = e;
		} catch (ExecutionException | TimeoutException e) {
			failure = e instanceof ExecutionException ?
				new IllegalStateException("Interop server close failed on the reactor", e.getCause()) :
				e;
		}
		// The loop is this fixture's; release it now that nothing of it is left to run.
		eventloop.keepAlive(false);
		try {
			reactorThread.join(JOIN_TIMEOUT_MILLIS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			failure = failure == null ? e : failure;
		}
		if (reactorThread.isAlive()) {
			// Last resort: a drain that outlives its join must not hold the JVM — the loop is forced
			// out and whatever ByteBufs were still in flight surface through ByteBufRule as a red.
			eventloop.breakEventloop();
			try {
				reactorThread.join(BREAK_JOIN_TIMEOUT_MILLIS);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}
		if (failure != null) {
			throw new IllegalStateException("Interop fixture teardown did not complete cleanly", failure);
		}
	}

	/** Runs on the reactor thread: bind {@code :0} on loopback, keep the socket, listen (T009, T010a). */
	private BoundServer startServer() throws IOException {
		DatagramChannel channel = NioReactor.createDatagramChannel(
			DatagramSocketSettings.create(),
			new InetSocketAddress(InetAddress.getLoopbackAddress(), 0),
			null);
		this.channel = channel;
		InetSocketAddress boundAddress = (InetSocketAddress) channel.getLocalAddress();
		this.boundAddress = boundAddress;

		// The servlet is built here, on the reactor thread, so a RoutingServlet built by the factory
		// captures exactly this reactor (WI-2) — the JUnit thread must never construct a reactive piece.
		AsyncServlet servlet = servletFactory.apply(eventloop);

		UdpSocket socket = UdpSocket.connect(eventloop, channel).getResult();
		this.socket = socket;

		Http3Server server = Http3Server.builder(eventloop, servlet)
			.withSocket(socket)
			.withServerIdentity(Http3TestTls.devIdentity())
			.build();
		server.listen();
		this.server = server;
		return new BoundServer(server, boundAddress);
	}

	private record BoundServer(Http3Server server, InetSocketAddress boundAddress) {}

	private static <T> T await(CompletableFuture<T> future) {
		try {
			return future.get(REACTOR_CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Interrupted while waiting for the interop reactor", e);
		} catch (ExecutionException e) {
			throw new IllegalStateException("The interop reactor task failed", e.getCause());
		} catch (TimeoutException e) {
			throw new IllegalStateException("The interop reactor did not answer within " +
				REACTOR_CALL_TIMEOUT_SECONDS + " seconds", e);
		}
	}
}
