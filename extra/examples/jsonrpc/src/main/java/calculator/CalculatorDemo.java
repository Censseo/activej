package calculator;

import io.activej.jsonrpc.JsonRpcError;
import io.activej.jsonrpc.JsonRpcException;
import io.activej.promise.Promise;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * The one client&rarr;server exchange every transport example replays, hoisted out of the example
 * classes so that each of them is <b>nothing but its own wiring</b>.
 * <p>
 * {@code JsonRpcExampleClient} spells this same sequence out inline, because the HTTP example is the
 * one a reader meets first and there is nothing to compare it against yet. From the second transport
 * on, the point is precisely that <i>the call code does not change</i>: the same {@code proxy()} of
 * the same {@link CalculatorService} runs unmodified over HTTP POST, over a WebSocket and over framed
 * TCP. Handing all three the identical {@link #run(CalculatorService)} makes that literal rather than
 * merely claimed.
 * <p>
 * The sequence exercises, in order: the record round trip, a second call, the recorded history, the
 * deliberate error path — where the promise fails with a {@link JsonRpcException} carrying the peer's
 * own code, message and {@code data} — and the notification, whose effect the next call observes.
 */
public final class CalculatorDemo {
	private CalculatorDemo() {}

	/**
	 * Runs the exchange against an already-connected proxy and prints every answer. The returned
	 * promise completes when the last call has, whatever the transport underneath — including the
	 * deliberate error, which is caught and printed rather than propagated.
	 */
	public static Promise<Void> run(CalculatorService calc) {
		//[START REGION_1]
		return calc.add(new Operands(2, 3))
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

			// a notification: nothing comes back at all, on any transport
			.then(calc::clear)
			.whenResult(() -> print("calc.clear", "(notification: the server sent no response document)"))

			// ... and its effect is visible on the next call
			.then(calc::history)
			.whenResult(history -> print("calc.history", history + "   <- emptied by the notification"))
			.toVoid();
		//[END REGION_1]
	}

	public static void print(String wireName, Object result) {
		System.out.println(pad(wireName) + " -> " + result);
	}

	public static void printError(String wireName, Exception e) {
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

	/** The one column width every example prints in, so three transports produce comparable output. */
	public static String pad(String wireName) {
		return String.format("%-12s", wireName);
	}
}
