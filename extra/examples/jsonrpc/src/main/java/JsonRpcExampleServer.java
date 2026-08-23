import calculator.CalculatorService;
import calculator.CalculatorServiceImpl;
import io.activej.common.ApplicationSettings;
import io.activej.eventloop.Eventloop;
import io.activej.http.HttpMethod;
import io.activej.http.HttpServer;
import io.activej.http.RoutingServlet;
import io.activej.jsonrpc.service.JsonRpcDispatcher;
import io.activej.jsonrpc.transport.http.JsonRpcServlet;

import java.io.IOException;

/**
 * A JSON-RPC 2.0 server over HTTP POST, in four objects and no HTTP code beyond mounting the servlet.
 * <p>
 * Run this class, then run {@link JsonRpcExampleClient} — or reach the very same endpoint with the
 * {@code curl} commands this server prints on startup, because a JSON-RPC document is just a body.
 * <p>
 * The wiring, top to bottom:
 * <ol>
 *     <li>{@link CalculatorServiceImpl} — the implementation, a plain object;</li>
 *     <li>{@link JsonRpcDispatcher} — the wire-name → implementation table. Its {@code build()} either
 *     validates {@link CalculatorService} completely or throws {@code JsonRpcContractException}
 *     listing <i>every</i> fault at once; nothing is resolved later, at call time;</li>
 *     <li>{@link JsonRpcServlet} — one {@code POST} in, one {@code dispatch(byte[])} call, the
 *     dispatcher's bytes back out unaltered. It reads no path, so it serves wherever it is mounted;</li>
 *     <li>{@link HttpServer} — an ordinary ActiveJ HTTP server; the JSON-RPC endpoint is just one
 *     route on it, which is why it can sit beside the rest of an existing application.</li>
 * </ol>
 */
public final class JsonRpcExampleServer {
	/**
	 * A fixed, documented port, so the printed {@code curl} commands are copy-pasteable. Both this
	 * class and {@link JsonRpcExampleClient} read it, so a single
	 * {@code -DJsonRpcExampleServer.port=…} on each JVM moves the example off an occupied 8080.
	 */
	public static final int PORT = ApplicationSettings.getInt(JsonRpcExampleServer.class, "port", 8080);

	/** The route the servlet is mounted on — the servlet itself is path-agnostic. */
	public static final String PATH = "/api";

	public static void main(String[] args) throws IOException {
		Eventloop eventloop = Eventloop.builder()
			.withCurrentThread()
			.build();

		//[START REGION_1]
		// the method table: every wire name of the annotated interface, resolved once, here and now
		JsonRpcDispatcher dispatcher = JsonRpcDispatcher.builder(eventloop)
			.withService(CalculatorService.class, new CalculatorServiceImpl())
			.build();

		// the transport: one POST -> one dispatch -> the dispatcher's bytes, verbatim
		JsonRpcServlet jsonRpcServlet = JsonRpcServlet.create(eventloop, dispatcher);

		HttpServer server = HttpServer.builder(eventloop,
				RoutingServlet.builder(eventloop)
					.with(HttpMethod.POST, PATH, jsonRpcServlet)
					.build())
			.withListenPort(PORT)
			.build();

		server.listen();
		//[END REGION_1]

		String url = "http://127.0.0.1:" + PORT + PATH;
		System.out.println("JSON-RPC over HTTP is listening on " + url);
		System.out.println("Registered wire names: " + dispatcher.wireNames());
		System.out.println();
		System.out.println("Run JsonRpcExampleClient, or reach the same endpoint directly:");
		System.out.println();
		System.out.println("  # a call: 200, with a response document");
		System.out.println("  curl -H 'Content-Type: application/json' \\");
		System.out.println("       -d '{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"calc.add\",\"params\":{\"operands\":{\"a\":2,\"b\":3}}}' \\");
		System.out.println("       " + url);
		System.out.println("  # -> {\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"expression\":\"2.0 + 3.0\",\"value\":5.0}}");
		System.out.println();
		System.out.println("  # the deliberate error path: still 200, the failure is in the document");
		System.out.println("  curl -H 'Content-Type: application/json' \\");
		System.out.println("       -d '{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"calc.divide\",\"params\":{\"operands\":{\"a\":1,\"b\":0}}}' \\");
		System.out.println("       " + url);
		System.out.println("  # -> {\"jsonrpc\":\"2.0\",\"id\":2,\"error\":{\"code\":100,\"message\":\"Division by zero\",\"data\":{\"a\":1.0,\"b\":0.0}}}");
		System.out.println();
		System.out.println("  # a notification: no id, so 204 No Content and no body at all");
		System.out.println("  curl -i -H 'Content-Type: application/json' \\");
		System.out.println("       -d '{\"jsonrpc\":\"2.0\",\"method\":\"calc.clear\"}' \\");
		System.out.println("       " + url);
		System.out.println();
		System.out.println("Press Ctrl+C to stop.");

		eventloop.run();
	}
}
