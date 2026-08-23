import calculator.CalculatorService;
import calculator.CalculatorServiceImpl;
import io.activej.common.ApplicationSettings;
import io.activej.eventloop.Eventloop;
import io.activej.jsonrpc.service.JsonRpcDispatcher;
import io.activej.jsonrpc.transport.tcp.JsonRpcTcpServer;

import java.io.IOException;

/**
 * The same {@link CalculatorService} again, this time over <b>bare TCP</b>. Run this class, then run
 * {@link JsonRpcTcpExampleClient} — or reach it with {@code nc}, because the framing is JSON Lines
 * and needs no client library whatsoever.
 * <p>
 * There is no HTTP here at all: no servlet, no route, no status code. {@link JsonRpcTcpServer} is an
 * ordinary ActiveJ server that owns its own listening socket, so unlike the HTTP and WebSocket
 * examples this one cannot be co-mounted beside an existing application's routes — it is a port of
 * its own. That is exactly why the launcher module leaves {@code jsonrpc.tcp.port} <i>disabled</i> by
 * default while it mounts the WebSocket route without being asked.
 *
 * <h2>The framing, and why there is none</h2>
 * One complete JSON-RPC document per line, terminated by exactly one {@code LF}, in both directions.
 * No preamble, no length prefix, no handshake. This works because RFC 8259 forbids a raw control
 * character inside a JSON string and the encoder never emits one, so <b>a raw LF on the wire is
 * always a boundary</b> — which is what let this transport ship with no framing code of its own.
 * <p>
 * The practical consequence is that the endpoint is reachable from a shell:
 * <pre>{@code
 * printf '{"jsonrpc":"2.0","id":1,"method":"calc.add","params":{"operands":{"a":2,"b":3}}}\n' \
 *     | nc 127.0.0.1 8082
 * }</pre>
 * Note the {@code \n}: without it nothing is ever framed and the request is never seen. Note also
 * that a pretty-printed document is <b>not</b> carriable — its own newlines would be read as
 * boundaries — so the answer to a multi-line request is {@code -32700}, and the connection stays up.
 */
public final class JsonRpcTcpExampleServer {
	/**
	 * A fixed, documented port, distinct from {@link JsonRpcExampleServer}'s and
	 * {@link JsonRpcWsExampleServer}'s so all the examples can run side by side. Both this class and
	 * {@link JsonRpcTcpExampleClient} read it, so a single {@code -DJsonRpcTcpExampleServer.port=...}
	 * on each JVM moves the example.
	 */
	public static final int PORT = ApplicationSettings.getInt(JsonRpcTcpExampleServer.class, "port", 8082);

	public static void main(String[] args) throws IOException {
		Eventloop eventloop = Eventloop.builder()
			.withCurrentThread()
			.build();

		//[START REGION_1]
		// the method table: byte for byte the same construction as the other two examples'
		JsonRpcDispatcher dispatcher = JsonRpcDispatcher.builder(eventloop)
			.withService(CalculatorService.class, new CalculatorServiceImpl())
			.build();

		// the transport: an ordinary reactive server. It adds a live-session registry, a drain on
		// close, and nothing else -- the accept loop, the listen addresses, the SSL variants and the
		// bound-address readback are all AbstractReactiveServer's
		JsonRpcTcpServer server = JsonRpcTcpServer.builder(eventloop, dispatcher)
			.withListenPort(PORT)
			.build();

		server.listen();
		//[END REGION_1]

		System.out.println("JSON-RPC over framed TCP is listening on tcp://127.0.0.1:" + PORT);
		System.out.println("Registered wire names: " + dispatcher.wireNames());
		System.out.println();
		System.out.println("Run JsonRpcTcpExampleClient, or reach the same endpoint with nothing but nc:");
		System.out.println();
		System.out.println("  # a call: one line in, one line out");
		System.out.println("  printf '{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"calc.add\",\"params\":{\"operands\":{\"a\":2,\"b\":3}}}\\n' \\");
		System.out.println("      | nc 127.0.0.1 " + PORT);
		System.out.println("  # -> {\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"expression\":\"2.0 + 3.0\",\"value\":5.0}}");
		System.out.println();
		System.out.println("  # the deliberate error path: still one line back, the failure is in the document");
		System.out.println("  printf '{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"calc.divide\",\"params\":{\"operands\":{\"a\":1,\"b\":0}}}\\n' \\");
		System.out.println("      | nc 127.0.0.1 " + PORT);
		System.out.println("  # -> {\"jsonrpc\":\"2.0\",\"id\":2,\"error\":{\"code\":100,\"message\":\"Division by zero\",\"data\":{\"a\":1.0,\"b\":0.0}}}");
		System.out.println();
		System.out.println("  # a notification: no id, so nothing at all comes back and nc just exits");
		System.out.println("  printf '{\"jsonrpc\":\"2.0\",\"method\":\"calc.clear\"}\\n' | nc 127.0.0.1 " + PORT);
		System.out.println();
		System.out.println("The trailing \\n is the framing. Without it the document is never delimited.");
		System.out.println();
		System.out.println("Press Ctrl+C to stop.");

		eventloop.run();
	}
}
