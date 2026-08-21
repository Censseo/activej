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

import io.activej.jsonrpc.service.JsonRpcClient;
import io.activej.jsonrpc.service.JsonRpcDispatcher;
import io.activej.jsonrpc.service.JsonRpcMethod;
import io.activej.jsonrpc.service.JsonRpcParam;
import io.activej.jsonrpc.service.JsonRpcService;
import io.activej.jsonrpc.transport.tcp.fixtures.ClientApi;
import io.activej.jsonrpc.transport.tcp.fixtures.ClientApiImpl;
import io.activej.jsonrpc.transport.tcp.fixtures.JsonRpcTcpRawSocket;
import io.activej.promise.Promise;
import io.activej.test.EventloopThread;
import io.activej.test.ExpectedException;
import io.activej.test.rules.ActivePromisesRule;
import io.activej.test.rules.ByteBufRule;
import io.activej.test.rules.EventloopRule;
import org.junit.After;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static io.activej.test.EventloopThread.await;
import static org.junit.Assert.assertEquals;

/**
 * The runnable, self-checking example of this feature (T011, FR-063): one annotated interface per
 * direction, a real {@link JsonRpcTcpServer} on a real socket, and a client connected through
 * {@link JsonRpcTcpTransport#connect} — with the client calling the server, the <b>server calling the
 * client back and awaiting its answer</b>, and the server broadcasting a notification to every live
 * session. The whole bidirectional story over one persistent framed-TCP connection, asserted.
 * <p>
 * The example lives in this module's {@code src/test} and runs on every build under {@code -P extra}
 * (the class name carries the {@code Test} suffix for exactly that reason — Surefire's include
 * patterns are what make "compiled and exercised by the build" a fact rather than a promise).
 *
 * <h2>The wire, checked with nothing but a shell</h2>
 * This transport's headline property is that its wire needs no client library at all — one
 * LF-terminated JSON document in, one LF-terminated JSON document out. {@code quickstart.md} §2 states
 * that as a one-liner:
 * <pre>{@code
 * $ printf '{"jsonrpc":"2.0","id":1,"method":"user.get","params":[42]}\n' | nc localhost 5300
 * {"jsonrpc":"2.0","id":1,"result":{"name":"Bob","id":42}}
 * }</pre>
 * {@link #theExampleEndToEnd()} runs exactly that exchange — same request bytes, same expected
 * response bytes, byte for byte — through {@link JsonRpcTcpRawSocket}, a plain blocking
 * {@link java.net.Socket} that speaks no JSON-RPC. So the documented one-liner is a build-gated
 * assertion rather than a claim, and {@code 5300} is the only thing that differs: the example's server
 * binds port <b>{@code 0}</b> and is asked where it landed via
 * {@link JsonRpcTcpServer#getBoundAddresses()} (ADR-028) — {@code TestUtils.getFreePort()} is
 * deliberately absent from this module (FR-078). The port is printed at the start of the run.
 *
 * <h2>Threading</h2>
 * The example's reactor runs on a dedicated {@link EventloopThread}, for two reasons: it keeps a
 * listening server and a long-lived connection alive while the JUnit thread awaits answers, and
 * {@link JsonRpcTcpRawSocket} is blocking and must therefore never be driven from a reactor thread.
 * Everything reactor-confined — {@code proxy(...)}, {@code sessions()}, {@code broadcast(...)},
 * {@code inFlightCount()} — is issued inside {@code loop.submit(...)}.
 */
public final class JsonRpcTcpEndToEndExampleTest {
	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	@ClassRule
	public static final EventloopRule eventloopRule = new EventloopRule();

	@ClassRule
	public static final ActivePromisesRule activePromisesRule = new ActivePromisesRule();

	/** The request of the {@code printf | nc} one-liner above, byte for byte (the terminator is added on write). */
	private static final String NC_REQUEST = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"user.get\",\"params\":[42]}";

	/** The answer the one-liner above prints, byte for byte. */
	private static final String NC_RESPONSE = "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"name\":\"Bob\",\"id\":42}}";

	private EventloopThread loop;
	private JsonRpcTcpServer server;
	private JsonRpcClient client;

	/** The client's own service — what the <i>server</i>'s calls and broadcasts dispatch to. */
	private final ClientApiImpl clientService = new ClientApiImpl();

	@Before
	public void setUp() {
		loop = EventloopThread.create("jsonrpc-tcp-example");
		loop.submit(() -> {
			// the whole of the server side: a dispatcher holding the service table, and a server on it
			JsonRpcDispatcher dispatcher = JsonRpcDispatcher.builder(loop.eventloop())
				.withService(UserApi.class, new UserApiImpl())
				.build();                                   // the contract is validated here, or never
			server = JsonRpcTcpServer.builder(loop.eventloop(), dispatcher)
				.withListenPort(0)                          // :0, then asked where it landed (ADR-028)
				.build();
			server.listen();
		});
	}

	@After
	public void tearDown() throws Exception {
		if (server != null) {
			try {
				server.closeFuture().get(10, TimeUnit.SECONDS);
			} catch (InterruptedException | ExecutionException | TimeoutException e) {
				throw new AssertionError("the example server did not close cleanly", e);
			}
		}
		if (loop != null) loop.close();
	}

	/**
	 * The example end to end: the shell one-liner of {@code quickstart.md} §2 verified byte for byte,
	 * then the Java client calling the server, then the server calling that client back and awaiting its
	 * answer, then a broadcast notification reaching it — four exchanges in two directions, the last
	 * three over one persistent connection.
	 */
	@Test
	public void theExampleEndToEnd() throws Exception {
		int port = loop.submit(() -> {
			return server.getBoundAddresses().get(0).getPort();
		});
		System.out.println("JSON-RPC over framed TCP example: listening on tcp://127.0.0.1:" + port);
		System.out.println("  printf '" + NC_REQUEST + "\\n' | nc localhost " + port);
		System.out.println("  # -> " + NC_RESPONSE);

		// -----------------------------------------------------------------------------------------
		// 1. The wire, with no client library at all — the quickstart's `printf | nc`, in Java.
		// -----------------------------------------------------------------------------------------
		try (JsonRpcTcpRawSocket nc = JsonRpcTcpRawSocket.connect(new InetSocketAddress("127.0.0.1", port))) {
			nc.write(NC_REQUEST + "\n");                    // exactly what printf writes
			assertEquals("the documented one-liner's output, byte for byte", NC_RESPONSE, nc.readLine());
		}
		// that connection is gone; the registry drains it before the example's own client arrives
		awaitSessionCount(0, "the raw socket's session to be deregistered");

		// -----------------------------------------------------------------------------------------
		// 2. The Java client — the same wire, through a proxy of the same interface.
		// -----------------------------------------------------------------------------------------
		connectClient(port);

		User user = await(loop.submit(() -> {
			UserApi api = client.proxy(UserApi.class);
			return api.getUser(42).toCompletableFuture();
		}), "user.get");
		assertEquals("the result decoded from the wire", new User("Bob", 42), user);
		assertEquals("no correlation entry is left behind", 0, (int) loop.submit(() -> {
			return client.inFlightCount();
		}));

		// -----------------------------------------------------------------------------------------
		// 3. Server -> client, as a call: the server initiates on the session and awaits the answer.
		// -----------------------------------------------------------------------------------------
		// the call above proves the session is registered (registration precedes dispatch), so the
		// registry holds exactly this connection
		awaitSessionCount(1, "the example client's session to be registered");
		String decided = await(loop.submit(() -> {
			JsonRpcTcpSession session = server.sessions().iterator().next();
			return session.proxy(ClientApi.class).decide(7).toCompletableFuture();
		}), "client.decide");
		assertEquals("the server-initiated call's answer round-tripped", "decided-7", decided);

		// -----------------------------------------------------------------------------------------
		// 4. Server -> client, as a broadcast: a notification, so nothing comes back.
		// -----------------------------------------------------------------------------------------
		// firstEvent() is taken on the loop, in the same task as the broadcast, so the await point
		// exists before the delivery it waits for — a notification leaves the sender nothing to await
		await(loop.submit(() -> {
			server.broadcast(ClientApi.class, api -> api.event(99L));
			return clientService.firstEvent().toCompletableFuture();
		}), "the client.event broadcast");
		assertEquals("the broadcast notification reached the client", List.of(99L),
			loop.submit(() -> {
				return List.copyOf(clientService.events());
			}));

		// both correlation tables are empty: the client answered the server's call, the server got it
		assertEquals(0, (int) loop.submit(() -> {
			return client.inFlightCount();
		}));
		assertEquals(0, (int) loop.submit(() -> {
			return server.sessions().iterator().next().inFlightCount();
		}));

		// close the client cleanly BEFORE the server closes, so no connection is torn down mid-read
		loop.submit(() -> {
			client.closeEx(new ExpectedException("the example is complete"));
		});
	}

	// ---------------------------------------------------------------------------------------------------
	// The example's wiring — the whole of what a developer writes.
	// ---------------------------------------------------------------------------------------------------

	/**
	 * Connects the example's client and wires the reverse direction: a {@code JsonRpcClient} over the
	 * connected transport, with a dispatcher of the client's own services installed as the
	 * {@code peerHandler} — which is the <b>whole</b> of the server&rarr;client direction (quickstart §4).
	 */
	private void connectClient(int port) {
		client = await(loop.submit(() -> {
			JsonRpcDispatcher clientDispatcher = JsonRpcDispatcher.builder(loop.eventloop())
				.withService(ClientApi.class, clientService)
				.build();
			return JsonRpcTcpTransport.connect(loop.eventloop(), new InetSocketAddress("127.0.0.1", port))
				.map(transport -> JsonRpcClient.builder(loop.eventloop(), transport)
					.withPeerHandler(clientDispatcher)      // the whole server -> client direction
					.build())
				.toCompletableFuture();
		}), "the example client's connect");
	}

	/**
	 * Waits, bounded, until the server's registry holds {@code expected} sessions. A connection's
	 * registration and its peer's {@code connect} resolve on the same reactor but in no defined order, and
	 * a deregistration follows an end-of-stream the JUnit thread cannot observe — so this is the honest
	 * wait, not a sleep standing in for one.
	 */
	private void awaitSessionCount(int expected, String what) {
		long deadline = System.currentTimeMillis() + 10_000;
		int observed;
		while ((observed = loop.submit(() -> {
			return server.sessions().size();
		})) != expected && System.currentTimeMillis() < deadline) {
			try {
				Thread.sleep(10);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new AssertionError("interrupted awaiting " + what, e);
			}
		}
		assertEquals("timed out awaiting " + what, expected, observed);
	}

	/**
	 * The whole of what a developer writes for the client&rarr;server direction: one annotated interface
	 * is the protocol. This is {@code quickstart.md} §1 verbatim — which is what makes the
	 * {@code printf | nc} one-liner in this class's Javadoc reproducible. Every wire name is explicit: an
	 * empty {@code @JsonRpcMethod} value would fall back to the Java identifier, and a later rename would
	 * silently change the wire format.
	 */
	@JsonRpcService("user")
	public interface UserApi {
		/** Wire name {@code user.get}: one named parameter in, one record out. */
		@JsonRpcMethod("get")
		Promise<User> getUser(@JsonRpcParam("id") long id);
	}

	/** The record {@link UserApi#getUser} answers with — a codec is derived for any record, nothing to register. */
	public record User(String name, long id) {}

	/** The implementation the server dispatches to. {@code Bob} is the quickstart's answer. */
	static final class UserApiImpl implements UserApi {
		@Override
		public Promise<User> getUser(long id) {
			return Promise.of(new User("Bob", id));
		}
	}
}
