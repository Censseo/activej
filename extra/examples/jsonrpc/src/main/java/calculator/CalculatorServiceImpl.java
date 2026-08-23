package calculator;

import io.activej.json.JsonCodec;
import io.activej.json.JsonCodecFactory;
import io.activej.jsonrpc.JsonRpcErrors;
import io.activej.jsonrpc.JsonRpcException;
import io.activej.jsonrpc.JsonRpcPayload;
import io.activej.promise.Promise;

import java.util.ArrayList;
import java.util.List;

/**
 * The server-side implementation of {@link CalculatorService} — a plain object with no JSON, no HTTP
 * and no reactor code in it. The dispatcher invokes it on the reactor thread, so the mutable
 * {@link #history} list needs no synchronisation; it is confined to that one thread exactly like any
 * other reactor-owned state.
 * <p>
 * The interesting method is {@link #divide}: it <b>throws</b> a {@link JsonRpcException}, which the
 * dispatcher renders verbatim — code, message and {@code data} — into the response document. Any
 * other throwable would instead become a bare {@code -32603 Internal error} carrying nothing at all,
 * because nothing derived from a local exception is ever allowed into an outbound document.
 */
public final class CalculatorServiceImpl implements CalculatorService {
	/**
	 * A codec for the error's {@code data} member. Derived, not registered — the same derivation that
	 * gives {@link Operands} and {@link CalculationResult} their codecs on the wire.
	 */
	private static final JsonCodec<Operands> OPERANDS_CODEC =
		JsonCodecFactory.defaultInstance().resolve(Operands.class);

	/** Reactor-confined: only the dispatcher touches it, and only on the reactor thread. */
	private final List<CalculationResult> history = new ArrayList<>();

	@Override
	public Promise<CalculationResult> add(Operands operands) {
		return Promise.of(remember(new CalculationResult(
			operands.a() + " + " + operands.b(),
			operands.a() + operands.b())));
	}

	@Override
	public Promise<CalculationResult> divide(Operands operands) throws JsonRpcException {
		if (operands.b() == 0) {
			// the deliberate error path: a chosen code, a chosen message, and the offending input as
			// `data`. Note that the double division would NOT have thrown — it would have quietly
			// produced Infinity — which is precisely why the check is explicit
			throw new JsonRpcException(JsonRpcErrors.of(
				DIVISION_BY_ZERO,
				"Division by zero",
				JsonRpcPayload.encoded(OPERANDS_CODEC, operands)));
		}
		return Promise.of(remember(new CalculationResult(
			operands.a() + " / " + operands.b(),
			operands.a() / operands.b())));
	}

	@Override
	public Promise<List<CalculationResult>> history() {
		return Promise.of(List.copyOf(history));
	}

	@Override
	public Promise<Void> clear() {
		// a notification has nowhere to put a result — the side effect IS the whole of it
		history.clear();
		return Promise.complete();
	}

	private CalculationResult remember(CalculationResult result) {
		history.add(result);
		return result;
	}
}
