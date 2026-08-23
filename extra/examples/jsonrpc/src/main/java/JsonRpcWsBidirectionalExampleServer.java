import calculator.CalculatorService;
import calculator.CalculatorServiceImpl;
import io.activej.common.ApplicationSettings;
import io.activej.eventloop.Eventloop;
import io.activej.http.HttpServer;
import io.activej.http.RoutingServlet;
import io.activej.jsonrpc.service.JsonRpcDispatcher;
import io.activej.jsonrpc.transport.ws.JsonRpcWsServlet;
import io.activej.jsonrpc.transport.ws.JsonRpcWsSession;
import notifications.ClientNotifications;

import java.io.IOException;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;

/**
 * The example that runs the arrow the other way: a server that <b>calls its clients</b>. Run this
 * class, then run {@link JsonRpcWsBidirectionalExampleClient}.
 * <p>
 * {@link JsonRpcWsExampleServer} is the same service over the same transport and does not do this at
 * all, which is the comparison worth making: everything below the horizontal rule in this file is
 * additive. Serving {@link CalculatorService} is unchanged; the server&rarr;client direction is a
 * second, independent thing bolted onto the same connections.
 *
 * <h2>How a server calls a client</h2>
 * There is no new component and no new mechanism. {@link JsonRpcWsServlet} keeps a registry of live
 * sessions, and a session <i>is</i> an ordinary {@code JsonRpcClient} bound to that one connection.
 * So the server obtains a proxy from it exactly as a client would:
 * <pre>{@code
 * session.proxy(ClientNotifications.class).greet("...")   // a call: the promise resolves with the
 *                                                         // client's own answer
 * wsServlet.broadcast(ClientNotifications.class,          // a notification, fanned out to every
 *     client -> client.tick(seq));                        // live session
 * }</pre>
 * {@link ClientNotifications} is implemented in the <i>client</i> process and registered on a
 * dispatcher there. Which peer answers a request is decided entirely by which peer registered the
 * service; JSON-RPC 2.0 itself has no notion of client and server once a connection exists.
 *
 * <h2>Why this polls</h2>
 * The registry is a set, not an event stream: there is no "session opened" callback to hook. A real
 * application usually has an application-level trigger for a push (a database change, a queue
 * message, a timer), so it never needs one. This example has nothing to react to, so it looks at the
 * registry on a schedule and greets whatever is new — the schedule stands in for the trigger, and is
 * not a pattern to copy.
 * <p>
 * Note also that the {@code greet} promise below has <b>no deadline</b>: a peer that never answers
 * leaves it pending until the connection closes. Per-call timeouts are not part of this line of
 * modules yet.
 */
public final class JsonRpcWsBidirectionalExampleServer {
	/**
	 * A fixed, documented port, distinct from every other example's so all of them can run side by
	 * side. Both this class and {@link JsonRpcWsBidirectionalExampleClient} read it.
	 */
	public static final int PORT =
		ApplicationSettings.getInt(JsonRpcWsBidirectionalExampleServer.class, "port", 8083);

	/** The route the WebSocket endpoint is mounted on. */
	public static final String PATH = "/ws";

	/** How often the server looks at its session registry and pushes. Stands in for a real trigger. */
	private static final Duration PUSH_INTERVAL = Duration.ofSeconds(1);

	public static void main(String[] args) throws IOException {
		Eventloop eventloop = Eventloop.builder()
			.withCurrentThread()
			.build();

		//[START REGION_1]
		// ------------------------------------------------------------------------------------------
		// The client -> server direction: identical to every other example in this module.
		// ------------------------------------------------------------------------------------------
		JsonRpcDispatcher dispatcher = JsonRpcDispatcher.builder(eventloop)
			.withService(CalculatorService.class, new CalculatorServiceImpl())
			.build();

		JsonRpcWsServlet wsServlet = JsonRpcWsServlet.builder(eventloop, dispatcher)
			.build();

		HttpServer server = HttpServer.builder(eventloop,
				RoutingServlet.builder(eventloop)
					.withWebSocket(PATH, wsServlet)
					.build())
			.withListenPort(PORT)
			// mandatory for a session that is meant to outlive one exchange: the 60s default is not
			// refreshed by frame traffic, so it would close this connection mid-example
			.withReadWriteTimeout(Duration.ZERO)
			.build();

		server.listen();
		//[END REGION_1]

		System.out.println("Bidirectional JSON-RPC over WebSocket is listening on ws://127.0.0.1:" + PORT + PATH);
		System.out.println("Registered wire names (what a client may call): " + dispatcher.wireNames());
		System.out.println("Called on the client  (what this server calls): client.greet, client.tick");
		System.out.println();
		System.out.println("Run JsonRpcWsBidirectionalExampleClient. This server will then:");
		System.out.println("  1. answer its calc.* calls, exactly like the other examples;");
		System.out.println("  2. call client.greet on its session and await the answer;");
		System.out.println("  3. broadcast a client.tick notification every " + PUSH_INTERVAL.toSeconds() + "s.");
		System.out.println();
		System.out.println("Press Ctrl+C to stop.");
		System.out.println();

		//[START REGION_2]
		// ------------------------------------------------------------------------------------------
		// The server -> client direction: everything below is what the plain WebSocket example lacks.
		// delayBackground, so this schedule never by itself keeps the reactor alive.
		// ------------------------------------------------------------------------------------------
		eventloop.delayBackground(PUSH_INTERVAL, new Runnable() {
			/** Sessions already greeted, so each connection is called exactly once. */
			private final Set<JsonRpcWsSession> greeted = new HashSet<>();
			private long seq;

			@Override
			public void run() {
				// a reactor-confined snapshot of the live connections; its size IS the open-connection
				// count -- the registry adds no bound of its own
				Set<JsonRpcWsSession> live = wsServlet.sessions();
				greeted.retainAll(live);

				for (JsonRpcWsSession session : live) {
					if (!greeted.add(session)) continue;

					System.out.println("a client connected; calling client.greet on its session");
					// the session is a JsonRpcClient bound to this one connection: proxy() and call
					session.proxy(ClientNotifications.class)
						.greet("welcome, one of " + live.size() + " connected session(s)")
						.whenResult(answer -> System.out.println(
							"client.greet -> " + answer + "   <- answered by the CLIENT process"))
						.whenException(e -> System.out.println(
							"client.greet failed (does the client register ClientNotifications?): " + e));
				}

				if (!live.isEmpty()) {
					// a notification, so there is nothing to await and nothing to correlate -- which is
					// what makes a fan-out to every session meaningful in the first place
					wsServlet.broadcast(ClientNotifications.class, client -> client.tick(++seq));
					System.out.println("client.tick broadcast: seq " + seq + " to " + live.size() + " session(s)");
				}

				eventloop.delayBackground(PUSH_INTERVAL, this);
			}
		});
		//[END REGION_2]

		eventloop.run();
	}
}
