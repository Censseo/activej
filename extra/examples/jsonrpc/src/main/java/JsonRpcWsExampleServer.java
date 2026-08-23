import calculator.CalculatorService;
import calculator.CalculatorServiceImpl;
import io.activej.common.ApplicationSettings;
import io.activej.eventloop.Eventloop;
import io.activej.http.HttpServer;
import io.activej.http.RoutingServlet;
import io.activej.jsonrpc.service.JsonRpcDispatcher;
import io.activej.jsonrpc.transport.ws.JsonRpcWsServlet;

import java.io.IOException;
import java.time.Duration;

/**
 * The same {@link CalculatorService} as {@link JsonRpcExampleServer}, served over a <b>WebSocket</b>
 * instead of an HTTP POST. Run this class, then run {@link JsonRpcWsExampleClient}.
 * <p>
 * Compare the two servers side by side: the service, its implementation and the
 * {@link JsonRpcDispatcher} are <i>identical</i>, and the only difference is which transport object
 * the dispatcher is handed to — {@code JsonRpcServlet} there, {@link JsonRpcWsServlet} here. That is
 * the whole of what "transport-agnostic" means in this line of modules.
 * <p>
 * What a WebSocket buys over POST, and why it is a different module rather than an option:
 * <ul>
 *     <li>the connection is <b>persistent</b>: one TCP connection and one upgrade carry every
 *     subsequent call, instead of a request per call;</li>
 *     <li>it is <b>duplex</b> — the servlet keeps a registry of live sessions, each holding a full
 *     {@code JsonRpcClient}, so the server can call <i>into</i> a connected client. This example does
 *     not use that direction at all; {@link JsonRpcWsBidirectionalExampleServer} is the one that
 *     does.</li>
 * </ul>
 * The framing rule is one complete JSON-RPC document per WebSocket TEXT message, and nothing else —
 * no subprotocol, no handshake, no envelope of our own. A browser therefore needs no library:
 * <pre>{@code
 * const ws = new WebSocket("ws://127.0.0.1:8081/ws");
 * ws.onopen = () => ws.send(JSON.stringify({
 *   jsonrpc: "2.0", id: 1, method: "calc.add", params: {operands: {a: 2, b: 3}}
 * }));
 * ws.onmessage = e => console.log(e.data);
 * }</pre>
 */
public final class JsonRpcWsExampleServer {
	/**
	 * A fixed, documented port, distinct from {@link JsonRpcExampleServer}'s so both examples can run
	 * side by side. Both this class and {@link JsonRpcWsExampleClient} read it, so a single
	 * {@code -DJsonRpcWsExampleServer.port=...} on each JVM moves the example.
	 */
	public static final int PORT = ApplicationSettings.getInt(JsonRpcWsExampleServer.class, "port", 8081);

	/** The route the WebSocket endpoint is mounted on. */
	public static final String PATH = "/ws";

	public static void main(String[] args) throws IOException {
		Eventloop eventloop = Eventloop.builder()
			.withCurrentThread()
			.build();

		//[START REGION_1]
		// the method table: byte for byte the same construction as the HTTP example's
		JsonRpcDispatcher dispatcher = JsonRpcDispatcher.builder(eventloop)
			.withService(CalculatorService.class, new CalculatorServiceImpl())
			.build();

		// the transport: one TEXT message in -> one dispatch -> one TEXT message out. The servlet also
		// owns the live-session registry, which the bidirectional example uses and this one ignores
		JsonRpcWsServlet wsServlet = JsonRpcWsServlet.builder(eventloop, dispatcher)
			.build();

		HttpServer server = HttpServer.builder(eventloop,
				RoutingServlet.builder(eventloop)
					.withWebSocket(PATH, wsServlet)
					.build())
			.withListenPort(PORT)
			// readWriteTimeout defaults to 60s and its timestamp is NOT refreshed by frame traffic, so
			// an upgraded connection would die a minute after the upgrade however busy it is. A
			// long-lived WebSocket deployment sets it to zero; a POST server has no such concern
			.withReadWriteTimeout(Duration.ZERO)
			.build();

		server.listen();
		//[END REGION_1]

		String url = "ws://127.0.0.1:" + PORT + PATH;
		System.out.println("JSON-RPC over WebSocket is listening on " + url);
		System.out.println("Registered wire names: " + dispatcher.wireNames());
		System.out.println();
		System.out.println("Run JsonRpcWsExampleClient, or open a browser console on any page and paste:");
		System.out.println();
		System.out.println("  const ws = new WebSocket(\"" + url + "\");");
		System.out.println("  ws.onmessage = e => console.log(e.data);");
		System.out.println("  ws.onopen = () => ws.send(JSON.stringify(");
		System.out.println("      {jsonrpc: \"2.0\", id: 1, method: \"calc.add\", params: {operands: {a: 2, b: 3}}}));");
		System.out.println("  // -> {\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"expression\":\"2.0 + 3.0\",\"value\":5.0}}");
		System.out.println();
		System.out.println("The wire is plain JSON-RPC 2.0, one document per TEXT message. No library required.");
		System.out.println();
		System.out.println("Press Ctrl+C to stop.");

		eventloop.run();
	}
}
