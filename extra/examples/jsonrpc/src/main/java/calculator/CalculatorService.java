package calculator;

import io.activej.jsonrpc.JsonRpcException;
import io.activej.jsonrpc.service.JsonRpcMethod;
import io.activej.jsonrpc.service.JsonRpcNotification;
import io.activej.jsonrpc.service.JsonRpcParam;
import io.activej.jsonrpc.service.JsonRpcService;
import io.activej.promise.Promise;

import java.util.List;

/**
 * The whole of what a developer writes to declare a JSON-RPC service: <b>one annotated interface</b>.
 * The same interface is used by both sides — the server registers an implementation of it on a
 * {@code JsonRpcDispatcher}, the client asks a {@code JsonRpcClient} for a {@code proxy()} of it —
 * and it is entirely transport-agnostic: HTTP, WebSocket and framed TCP all carry it unchanged.
 * <p>
 * Every wire name is <b>explicit</b>. An empty {@code @JsonRpcMethod} / {@code @JsonRpcNotification}
 * value falls back to the Java method identifier, which would make a later rename a silent
 * wire-format change.
 * <p>
 * The four things this example demonstrates:
 * <ul>
 *     <li>{@link #add} — a {@code record} in, a {@code record} out, no codec registered anywhere;</li>
 *     <li>{@link #divide} — {@code throws JsonRpcException}: how an implementation deliberately
 *     chooses an error <i>code</i>, <i>message</i> and <i>data</i> instead of degrading to a bare
 *     {@code -32603 Internal error};</li>
 *     <li>{@link #clear} — a notification: no {@code id} on the wire, no response document at all
 *     (over HTTP the server answers {@code 204 No Content});</li>
 *     <li>{@link #history} — a zero-parameter method returning a {@code List} of records, so the
 *     effect of the notification is observable.</li>
 * </ul>
 */
@JsonRpcService("calc")
public interface CalculatorService {
	/**
	 * The application error code {@link #divide} answers a zero divisor with.
	 * <p>
	 * It is deliberately <b>outside</b> {@code -32768 … -32000}: that whole range is reserved by
	 * JSON-RPC 2.0 §5.1, and {@code JsonRpcErrors.of(...)} refuses a code inside it. Application
	 * errors get their own numbering; the protocol keeps its own.
	 */
	int DIVISION_BY_ZERO = 100;

	/** Wire name {@code calc.add} — one record parameter in, one record result out. */
	@JsonRpcMethod("add")
	Promise<CalculationResult> add(@JsonRpcParam("operands") Operands operands);

	/**
	 * Wire name {@code calc.divide}. Fails with {@link #DIVISION_BY_ZERO} when {@code operands.b()}
	 * is zero, carrying the offending operands as the error's {@code data} member.
	 * <p>
	 * {@link JsonRpcException} is a <b>checked</b> exception, and a {@code throws} clause is accepted
	 * on any annotated method — that is the one supported way for an implementation to select the
	 * error a peer sees. Anything else it throws becomes a bare {@code -32603} carrying nothing.
	 */
	@JsonRpcMethod("divide")
	Promise<CalculationResult> divide(@JsonRpcParam("operands") Operands operands) throws JsonRpcException;

	/** Wire name {@code calc.history} — a zero-parameter method; {@code List<Record>} needs no codec either. */
	@JsonRpcMethod("history")
	Promise<List<CalculationResult>> history();

	/**
	 * Wire name {@code calc.clear} — a <b>notification</b>: it carries no {@code id}, so the server
	 * produces no response document whatever the outcome. A notification must declare {@code void}
	 * or {@code Promise<Void>}; the promise a caller gets back is the transport's send promise, not
	 * an answer.
	 */
	@JsonRpcNotification("clear")
	Promise<Void> clear();
}
