package notifications;

import io.activej.promise.Promise;

import java.util.function.LongConsumer;

/**
 * The <b>client-side</b> implementation of {@link ClientNotifications} — a plain object, exactly like
 * a server-side implementation, because there is no such thing as a client-only or server-only
 * service implementation. The dispatcher that holds it happens to live in the client process; that is
 * the only difference.
 * <p>
 * It narrates what the server does to it on {@code System.out}, which is the point of the example:
 * every line this class prints is proof that a call originated at the <i>server</i> and arrived here.
 */
public final class ClientNotificationsImpl implements ClientNotifications {
	private final String clientName;
	private final LongConsumer onTick;

	private long ticks;

	/**
	 * @param clientName what {@link #greet} answers with, so the server can print something that could
	 *                   only have come from this process
	 * @param onTick     invoked after each notification is printed, with its sequence number; the
	 *                   example client uses it to stop after a few pushes
	 */
	public ClientNotificationsImpl(String clientName, LongConsumer onTick) {
		this.clientName = clientName;
		this.onTick = onTick;
	}

	@Override
	public Promise<String> greet(String message) {
		// a server-initiated CALL: whatever is returned here travels back as the result of the
		// server's request document and resolves the promise the server is holding
		System.out.println(pad("client.greet") + " <- the SERVER called us: \"" + message + "\"");
		System.out.println(pad("") + "   answering it, which resolves the server's promise");
		return Promise.of("hello from " + clientName);
	}

	@Override
	public void tick(long seq) {
		// a server-initiated NOTIFICATION: nothing is returned, and nothing is sent back
		ticks++;
		System.out.println(pad("client.tick") + " <- the SERVER pushed seq " + seq + " (notification #" + ticks + ")");
		onTick.accept(seq);
	}

	/** How many notifications have arrived so far. */
	public long ticks() {
		return ticks;
	}

	private static String pad(String wireName) {
		return String.format("%-12s", wireName);
	}
}
