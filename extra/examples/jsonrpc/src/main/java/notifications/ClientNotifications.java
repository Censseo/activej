package notifications;

import io.activej.jsonrpc.service.JsonRpcMethod;
import io.activej.jsonrpc.service.JsonRpcNotification;
import io.activej.jsonrpc.service.JsonRpcParam;
import io.activej.jsonrpc.service.JsonRpcService;
import io.activej.promise.Promise;

/**
 * The <b>client's</b> service — the mirror image of {@code CalculatorService}, and the whole of what
 * makes the WebSocket example bidirectional.
 * <p>
 * Read the direction carefully, because it is the opposite of everything else in these examples: the
 * <i>client</i> implements this interface and registers it on a dispatcher of its own; the
 * <i>server</i> holds a proxy of it, obtained from a live session, and calls it. Nothing about
 * JSON-RPC 2.0 distinguishes the two roles once a connection exists — a peer that can send a request
 * document can send it in either direction — and nothing about this interface distinguishes it from
 * a server-side one either. Only the wiring decides who answers.
 * <p>
 * It is kept deliberately small and separate from {@code CalculatorService}: that interface is what a
 * client calls a server with, this one is what a server calls a client with, and merging them would
 * describe a protocol nobody has.
 * <p>
 * Both directions of the server&rarr;client push are shown:
 * <ul>
 *     <li>{@link #greet} is a <b>call</b>: the server sends a request with an {@code id} and awaits
 *     the client's answer, so its {@code Promise} completes with a value the client computed;</li>
 *     <li>{@link #tick} is a <b>notification</b>: no {@code id}, no answer, and therefore the only
 *     shape that can sensibly be broadcast to every connected session at once.</li>
 * </ul>
 * Note the independent {@code id} spaces this implies: the server's calls draw ids from the session's
 * own counter and the client's calls from its own, so both sides may legitimately have an {@code id}
 * of {@code 1} in flight at the same moment and neither can mistake the other's answer for its own.
 */
@JsonRpcService("client")
public interface ClientNotifications {
	/**
	 * Wire name {@code client.greet}. Sent by the server to a single, specific session as soon as it
	 * notices the connection; the client's answer travels back over that same connection and resolves
	 * the server's promise.
	 */
	@JsonRpcMethod("greet")
	Promise<String> greet(@JsonRpcParam("message") String message);

	/**
	 * Wire name {@code client.tick} — a notification, so it carries no {@code id} and the client
	 * produces no response document whatever happens. This is the shape {@code broadcast(...)} needs:
	 * a fan-out has no single promise to resolve.
	 */
	@JsonRpcNotification("tick")
	void tick(@JsonRpcParam("seq") long seq);
}
