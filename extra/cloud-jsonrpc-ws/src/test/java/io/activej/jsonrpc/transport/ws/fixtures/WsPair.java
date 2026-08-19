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

package io.activej.jsonrpc.transport.ws.fixtures;

import io.activej.async.exception.AsyncCloseException;
import io.activej.dns.DnsClient;
import io.activej.eventloop.Eventloop;
import io.activej.http.HttpClient;
import io.activej.http.HttpRequest;
import io.activej.http.HttpServer;
import io.activej.http.IWebSocket;
import io.activej.http.IWebSocketClient;
import io.activej.http.RoutingServlet;
import io.activej.http.WebSocketServlet;
import io.activej.promise.Promise;
import io.activej.reactor.nio.NioReactor;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

import static io.activej.http.HttpUtils.inetAddress;

/**
 * The shared server/client harness for this module's tests (T003, FR-078): an
 * <b>{@code acceptOnce}</b> {@link HttpServer} bound to port {@code 0} — the actual port is read
 * back with {@link HttpServer#getBoundAddresses()} (ADR-028; {@code getFreePort()} is refused in
 * this module's source) — with {@code readWriteTimeout} disabled so the 60 s connection sweep can
 * never kill a long-lived session mid-test (FR-096), and a real {@link IWebSocketClient} over
 * {@code ws://127.0.0.1} — an IP literal, which never touches DNS.
 * <p>
 * The servlet's {@code onWebSocket} is caller-supplied (server-upgrade factory), so a test controls
 * exactly what the server does with the accepted socket — wrapping it in a
 * {@code JsonRpcWsTransport}, counting its writes, or refusing the upgrade via a custom
 * {@link WebSocketServlet} (the {@code onRequest} admission gate, FR-036).
 * <p>
 * <b>Quiescence (R3).</b> {@code TestUtils.await} runs the eventloop to quiescence, and
 * {@code Eventloop.isAlive()} counts selector keys — an open accept socket or connection blocks
 * return. Every fixture path therefore ends with all channels closed: the awaited chains in the
 * tests close the sockets they open, and {@link #closeAll()} is the belt-and-suspenders cleanup
 * that closes whatever remains and drives the loop to quiescence.
 */
public final class WsPair {
	private final NioReactor reactor;
	private final HttpServer server;
	private final IWebSocketClient client;
	private final int port;
	private final List<IWebSocket> serverSockets;
	private @Nullable IWebSocket clientSocket;

	private WsPair(NioReactor reactor, HttpServer server, IWebSocketClient client, int port, List<IWebSocket> serverSockets) {
		this.reactor = reactor;
		this.server = server;
		this.client = client;
		this.port = port;
		this.serverSockets = serverSockets;
	}

	/** Starts an {@code acceptOnce} server on port {@code 0} with a fresh shared client. */
	public static WsPair serverUpgrade(NioReactor reactor, Consumer<IWebSocket> onWebSocket) {
		return serverUpgrade(reactor, null, onWebSocket);
	}

	/**
	 * Starts an {@code acceptOnce} server on port {@code 0} using the given client — the reuse
	 * harness for FR-065, which needs a second connection through the same injected client.
	 */
	public static WsPair serverUpgrade(NioReactor reactor, @Nullable IWebSocketClient client, Consumer<IWebSocket> onWebSocket) {
		Objects.requireNonNull(reactor, "reactor");
		Objects.requireNonNull(onWebSocket, "onWebSocket");
		List<IWebSocket> serverSockets = new ArrayList<>();
		WebSocketServlet servlet = new WebSocketServlet(reactor) {
			@Override
			protected void onWebSocket(IWebSocket webSocket) {
				serverSockets.add(webSocket);
				onWebSocket.accept(webSocket);
			}
		};
		return serverUpgrade(reactor, client, servlet, serverSockets);
	}

	/** Starts an {@code acceptOnce} server on port {@code 0} with a caller-supplied servlet (admission-gate tests). */
	public static WsPair serverUpgrade(NioReactor reactor, WebSocketServlet servlet) {
		return serverUpgrade(reactor, null, servlet);
	}

	/** Starts an {@code acceptOnce} server on port {@code 0} with a caller-supplied servlet (admission-gate tests). */
	public static WsPair serverUpgrade(NioReactor reactor, @Nullable IWebSocketClient client, WebSocketServlet servlet) {
		return serverUpgrade(reactor, client, servlet, new ArrayList<>());
	}

	private static WsPair serverUpgrade(NioReactor reactor, @Nullable IWebSocketClient client, WebSocketServlet servlet, List<IWebSocket> serverSockets) {
		HttpServer server = HttpServer.builder(reactor, RoutingServlet.builder(reactor)
				.withWebSocket("/", servlet)
				.build())
			.withListenPort(0)                       // FR-078: :0, then asked where it landed
			.withAcceptOnce()                        // R3: the accept socket must not outlive one connection
			.withReadWriteTimeout(Duration.ZERO)     // FR-096: the 60 s sweep would kill long-lived sessions
			.build();
		try {
			server.listen();
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		int port = server.getBoundAddresses().get(0).getPort();
		IWebSocketClient effectiveClient = client != null ? client : HttpClient.create(reactor, DnsClient.create(reactor, inetAddress("8.8.8.8")));
		return new WsPair(reactor, server, effectiveClient, port, serverSockets);
	}

	/** The port the kernel bound {@code :0} to. Valid after construction. */
	public int port() {
		return port;
	}

	/** The injected {@link IWebSocketClient} — the transport under test must never close it (FR-065). */
	public IWebSocketClient client() {
		return client;
	}

	/** Performs the client upgrade; resolves with the connected client-side {@link IWebSocket}. */
	public Promise<IWebSocket> connect() {
		return client.webSocketRequest(HttpRequest.get("ws://127.0.0.1:" + port).build())
			.whenResult(socket -> clientSocket = socket);
	}

	/**
	 * Closes whatever remains — the client socket, every server-side socket the servlet saw, and the
	 * server — then drives the eventloop to quiescence. Every call is idempotent, so calling this
	 * after an awaited chain that already closed everything is a no-op on the sockets and just runs
	 * the loop (which returns immediately once quiescent).
	 */
	public void closeAll() {
		if (clientSocket != null) {
			clientSocket.closeEx(new AsyncCloseException());
			clientSocket = null;
		}
		for (IWebSocket serverSocket : serverSockets) {
			serverSocket.closeEx(new AsyncCloseException());
		}
		serverSockets.clear();
		server.close();
		((Eventloop) reactor).run();
	}
}