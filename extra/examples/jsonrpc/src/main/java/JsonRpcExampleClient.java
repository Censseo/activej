import calculator.CalculatorService;
import calculator.Operands;
import io.activej.dns.DnsClient;
import io.activej.eventloop.Eventloop;
import io.activej.http.HttpClient;
import io.activej.http.HttpUtils;
import io.activej.jsonrpc.JsonRpcError;
import io.activej.jsonrpc.JsonRpcException;
import io.activej.jsonrpc.service.JsonRpcClient;
import io.activej.jsonrpc.transport.http.JsonRpcHttpClientTransport;
import io.activej.promise.Promise;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * The client half of the HTTP example. Start {@link JsonRpcExampleServer} first, then run this class.
 * <p>
 * The whole of the JSON-RPC wiring is three lines — a transport, a client, a {@code proxy()} — after
 * which {@link CalculatorService} is called like any other Java interface and every method answers a
 * {@code Promise}. There is no generated stub, no schema file and no HTTP code beyond constructing
 * the transport over an ordinary {@link HttpClient}.
 * <p>
 * The calls below exercise, in order: the record round trip, a second call, the recorded history, the
 * deliberate error path — where the promise fails with a {@link JsonRpcException} carrying the peer's
 * own code, message and {@code data} — and the notification, whose effect the next call observes.
 */
public final class JsonRpcExampleClient {
	private static final String URL =
		"http://127.0.0.1:" + JsonRpcExampleServer.PORT + JsonRpcExampleServer.PATH;

	public static void main(String[] args) {
		Eventloop eventloop = Eventloop.builder()
			.withCurrentThread()
			.build();

		// an ordinary ActiveJ HTTP client; the DNS client is never consulted for a literal address
		HttpClient httpClient = HttpClient.create(eventloop,
			DnsClient.create(eventloop, HttpUtils.inetAddress("8.8.8.8")));

		//[START REGION_1]
		// the transport turns each outgoing document into one POST to URL; connection reuse and
		// keep-alive stay entirely the HTTP client's business
		JsonRpcClient client = JsonRpcClient.builder(eventloop,
				JsonRpcHttpClientTransport.create(eventloop, httpClient, URL))
			.build();

		// validated here, once, against the same interface the server registered — or it throws
		CalculatorService calc = client.proxy(CalculatorService.class);
		//[END REGION_1]

		System.out.println("Calling " + URL);
		System.out.println();

		//[START REGION_2]
		calc.add(new Operands(2, 3))
			.whenResult(result -> print("calc.add", result))

			.then(() -> calc.divide(new Operands(10, 4)))
			.whenResult(result -> print("calc.divide", result))

			// both calls were recorded server-side
			.then(calc::history)
			.whenResult(history -> print("calc.history", history))

			// the deliberate error path: the promise FAILS with the peer's own error object
			.then(() -> calc.divide(new Operands(1, 0))
				.then(
					result -> {
						print("calc.divide", "unexpectedly succeeded: " + result);
						return Promise.complete();
					},
					e -> {
						printError("calc.divide", e);
						return Promise.complete();
					}))

			// a notification: nothing comes back at all — over HTTP the server answers 204 No Content
			.then(calc::clear)
			.whenResult(() -> print("calc.clear", "(notification: the server sent no response document)"))

			// ... and its effect is visible on the next call
			.then(calc::history)
			.whenResult(history -> print("calc.history", history + "   <- emptied by the notification"))
			//[END REGION_2]

			.whenException(e -> System.err.println(
				"the exchange failed (is JsonRpcExampleServer running on port " +
				JsonRpcExampleServer.PORT + "?): " + e))

			.whenComplete(() -> {
				// the client owns its transport and closes it; the HTTP client is NOT owned by the
				// transport, so releasing its keep-alive connections is this example's own business —
				// and is what lets eventloop.run() below return instead of idling forever
				client.close();
				httpClient.stop();
			});

		eventloop.run();
	}

	private static void print(String wireName, Object result) {
		System.out.println(pad(wireName) + " -> " + result);
	}

	private static void printError(String wireName, Exception e) {
		if (!(e instanceof JsonRpcException jsonRpcException)) {
			System.out.println(pad(wireName) + " -> failed locally: " + e);
			return;
		}
		JsonRpcError error = jsonRpcException.getError();
		System.out.println(pad(wireName) + " -> error " + error.code() + " \"" + error.message() + "\"" +
						   (error.data().isAbsent() ? "" : ", data " + new String(error.data().toByteArray(), UTF_8)));
		System.out.println(pad("") + "    (code " + CalculatorService.DIVISION_BY_ZERO +
						   " is the service's own, chosen by throwing JsonRpcException server-side)");
	}

	private static String pad(String wireName) {
		return String.format("%-12s", wireName);
	}
}
