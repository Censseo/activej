import calculator.CalculatorDemo;
import calculator.CalculatorService;
import io.activej.dns.DnsClient;
import io.activej.eventloop.Eventloop;
import io.activej.http.HttpClient;
import io.activej.http.HttpRequest;
import io.activej.http.HttpUtils;
import io.activej.jsonrpc.service.JsonRpcClient;
import io.activej.jsonrpc.transport.ws.JsonRpcWsTransport;

/**
 * The client half of the WebSocket example. Start {@link JsonRpcWsExampleServer} first, then run this
 * class.
 * <p>
 * The point of this file is how little of it there is. Connecting is one call —
 * {@link JsonRpcWsTransport#connect} performs the HTTP upgrade and resolves with a transport — after
 * which {@link JsonRpcClient} and {@code proxy()} are exactly what the HTTP example used, and the
 * calls themselves are literally the same code: {@link CalculatorDemo#run} is shared with
 * {@link JsonRpcTcpExampleClient} and mirrors {@link JsonRpcExampleClient}'s inline sequence.
 * <p>
 * The one visible difference from POST is the <b>connection</b>: every call below travels over the
 * single upgraded connection opened here, and the notification is a TEXT message that simply gets no
 * answer — there is no {@code 204} and no status code anywhere, because after the {@code 101} there
 * is no more HTTP.
 */
public final class JsonRpcWsExampleClient {
	private static final String URL =
		"ws://127.0.0.1:" + JsonRpcWsExampleServer.PORT + JsonRpcWsExampleServer.PATH;

	public static void main(String[] args) {
		Eventloop eventloop = Eventloop.builder()
			.withCurrentThread()
			.build();

		// an ordinary ActiveJ HTTP client -- it is what performs the upgrade handshake; the DNS client
		// is never consulted for a literal address
		HttpClient httpClient = HttpClient.create(eventloop,
			DnsClient.create(eventloop, HttpUtils.inetAddress("8.8.8.8")));

		System.out.println("Connecting to " + URL);
		System.out.println();

		//[START REGION_1]
		// the handshake is asynchronous: the promise resolves once the server answered 101, with a
		// transport bound to the upgraded connection
		JsonRpcWsTransport.connect(eventloop, httpClient, HttpRequest.get(URL).build())
			.then(transport -> {
				JsonRpcClient client = JsonRpcClient.builder(eventloop, transport)
					.build();

				// validated here, once, against the same interface the server registered -- or it throws
				CalculatorService calc = client.proxy(CalculatorService.class);

				// the same exchange, the same code, a different wire
				return CalculatorDemo.run(calc)
					// the client owns its transport and closing it closes the WebSocket
					.whenComplete(client::close);
			})
			//[END REGION_1]

			.whenException(e -> System.err.println(
				"the exchange failed (is JsonRpcWsExampleServer running on port " +
				JsonRpcWsExampleServer.PORT + "?): " + e))

			// the HTTP client is NOT owned by the transport, so releasing it is this example's own
			// business -- and is what lets eventloop.run() below return instead of idling forever
			.whenComplete(httpClient::stop);

		eventloop.run();
	}
}
