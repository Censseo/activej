import calculator.CalculatorDemo;
import calculator.CalculatorService;
import io.activej.eventloop.Eventloop;
import io.activej.jsonrpc.service.JsonRpcClient;
import io.activej.jsonrpc.transport.tcp.JsonRpcTcpTransport;

import java.net.InetSocketAddress;

/**
 * The client half of the framed-TCP example. Start {@link JsonRpcTcpExampleServer} first, then run
 * this class.
 * <p>
 * This is the shortest of the three clients, because there is no protocol underneath to configure:
 * {@link JsonRpcTcpTransport#connect} dials the address and resolves with a transport, and from there
 * on {@link JsonRpcClient}, {@code proxy()} and the calls are identical to the HTTP and WebSocket
 * examples — {@link CalculatorDemo#run} is the very same method all three run.
 * <p>
 * Everything below travels over one persistent connection as LF-terminated lines, which is exactly
 * what the {@code nc} one-liners {@link JsonRpcTcpExampleServer} prints send by hand. There is no
 * client object here that a shell could not replace.
 */
public final class JsonRpcTcpExampleClient {
	private static final InetSocketAddress ADDRESS =
		new InetSocketAddress("127.0.0.1", JsonRpcTcpExampleServer.PORT);

	public static void main(String[] args) {
		Eventloop eventloop = Eventloop.builder()
			.withCurrentThread()
			.build();

		System.out.println("Connecting to tcp://127.0.0.1:" + JsonRpcTcpExampleServer.PORT);
		System.out.println();

		//[START REGION_1]
		JsonRpcTcpTransport.connect(eventloop, ADDRESS)
			.then(transport -> {
				JsonRpcClient client = JsonRpcClient.builder(eventloop, transport)
					.build();

				// validated here, once, against the same interface the server registered -- or it throws
				CalculatorService calc = client.proxy(CalculatorService.class);

				// the same exchange, the same code, a different wire
				return CalculatorDemo.run(calc)
					// the client owns its transport and closing it closes the socket -- which is what
					// lets eventloop.run() below return instead of idling on an open connection
					.whenComplete(client::close);
			})
			//[END REGION_1]

			.whenException(e -> System.err.println(
				"the exchange failed (is JsonRpcTcpExampleServer running on port " +
				JsonRpcTcpExampleServer.PORT + "?): " + e));

		eventloop.run();
	}
}
