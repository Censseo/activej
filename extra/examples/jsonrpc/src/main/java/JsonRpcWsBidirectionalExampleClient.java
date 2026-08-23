import calculator.CalculatorDemo;
import calculator.CalculatorService;
import io.activej.common.ref.Ref;
import io.activej.common.ref.RefInt;
import io.activej.dns.DnsClient;
import io.activej.eventloop.Eventloop;
import io.activej.http.HttpClient;
import io.activej.http.HttpRequest;
import io.activej.http.HttpUtils;
import io.activej.jsonrpc.service.JsonRpcClient;
import io.activej.jsonrpc.service.JsonRpcDispatcher;
import io.activej.jsonrpc.transport.ws.JsonRpcWsTransport;
import notifications.ClientNotifications;
import notifications.ClientNotificationsImpl;

/**
 * The client half of the bidirectional example. Start
 * {@link JsonRpcWsBidirectionalExampleServer} first, then run this class.
 * <p>
 * A client that a server can call is a client with a <b>dispatcher of its own</b>. That is the entire
 * difference from {@link JsonRpcWsExampleClient}, and it is one builder call:
 * <pre>{@code
 * JsonRpcClient.builder(eventloop, transport)
 *     .withPeerHandler(clientDispatcher)   // <- the whole server -> client direction
 *     .build();
 * }</pre>
 * The dispatcher handed to {@code withPeerHandler} is built exactly as a server builds one — the same
 * class, the same {@code withService(...)} call, the same startup validation. It is the peer handler
 * that decides who answers an inbound request document, and JSON-RPC 2.0 has nothing else to say
 * about which end of a connection is the "server".
 * <p>
 * The run therefore shows two independent things happening over one connection: this client calling
 * {@code calc.*} on the server ({@link CalculatorDemo}, identical to the other two transports), and
 * the server calling {@code client.greet} and pushing {@code client.tick} back — each printed by
 * {@link ClientNotificationsImpl} as it arrives. The two directions have independent {@code id}
 * spaces, so they cannot interfere however they interleave.
 */
public final class JsonRpcWsBidirectionalExampleClient {
	private static final String URL = "ws://127.0.0.1:" +
									  JsonRpcWsBidirectionalExampleServer.PORT +
									  JsonRpcWsBidirectionalExampleServer.PATH;

	/** How this process names itself when the server calls {@code client.greet}. */
	private static final String CLIENT_NAME = "JsonRpcWsBidirectionalExampleClient";

	/** Server pushes to sit through before disconnecting, so the example terminates on its own. */
	private static final int PUSHES_BEFORE_EXIT = 3;

	public static void main(String[] args) {
		Eventloop eventloop = Eventloop.builder()
			.withCurrentThread()
			.build();

		HttpClient httpClient = HttpClient.create(eventloop,
			DnsClient.create(eventloop, HttpUtils.inetAddress("8.8.8.8")));

		Ref<JsonRpcClient> clientRef = new Ref<>();
		RefInt pushes = new RefInt(0);

		// the client's own implementation of what the SERVER calls. It prints every inbound call, so
		// each of its lines is proof that the server reached this process
		ClientNotificationsImpl notifications = new ClientNotificationsImpl(CLIENT_NAME, seq -> {
			if (pushes.inc() < PUSHES_BEFORE_EXIT) return;
			System.out.println();
			System.out.println("Seen " + PUSHES_BEFORE_EXIT + " server pushes; that was the demonstration. Closing.");
			// close from the next reactor task rather than from inside the dispatch running right now
			eventloop.post(() -> {
				clientRef.get().close();
				httpClient.stop();
			});
		});

		System.out.println("Connecting to " + URL);
		System.out.println();

		JsonRpcWsTransport.connect(eventloop, httpClient, HttpRequest.get(URL).build())
			.then(transport -> {
				//[START REGION_1]
				// what THIS process answers when the server calls it -- an ordinary dispatcher,
				// validated at build() exactly like a server's
				JsonRpcDispatcher clientDispatcher = JsonRpcDispatcher.builder(eventloop)
					.withService(ClientNotifications.class, notifications)
					.build();

				JsonRpcClient client = JsonRpcClient.builder(eventloop, transport)
					.withPeerHandler(clientDispatcher)    // the whole server -> client direction
					.build();
				//[END REGION_1]

				clientRef.set(client);

				// ... and the client -> server direction is unchanged: the same proxy of the same
				// interface running the same exchange as the HTTP and TCP examples
				return CalculatorDemo.run(client.proxy(CalculatorService.class));
			})

			.whenResult(() -> {
				System.out.println();
				System.out.println("The client -> server direction is done. Staying connected for the other one:");
				System.out.println("the server calls client.greet on this session, then pushes client.tick.");
				System.out.println("Every line below originates at the SERVER.");
				System.out.println();
			})

			.whenException(e -> {
				System.err.println(
					"the exchange failed (is JsonRpcWsBidirectionalExampleServer running on port " +
					JsonRpcWsBidirectionalExampleServer.PORT + "?): " + e);
				if (clientRef.get() != null) clientRef.get().close();
				httpClient.stop();
			});

		// the reactor keeps running while the upgraded connection is open -- which is precisely what
		// lets the server call back into this process long after the last outbound call resolved
		eventloop.run();
	}
}
