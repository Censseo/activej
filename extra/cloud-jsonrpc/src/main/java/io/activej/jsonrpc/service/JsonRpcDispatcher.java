/*
 * Copyright (C) 2020 ActiveJ LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.activej.jsonrpc.service;

import io.activej.common.ApplicationSettings;
import io.activej.common.builder.AbstractBuilder;
import io.activej.common.inspector.AbstractInspector;
import io.activej.common.inspector.BaseInspector;
import io.activej.jmx.api.attribute.JmxAttribute;
import io.activej.jmx.api.attribute.JmxReducers.JmxReducerSum;
import io.activej.jmx.stats.EventStats;
import io.activej.jmx.stats.ValueStats;
import io.activej.json.JsonCodec;
import io.activej.json.JsonCodecFactory;
import io.activej.json.JsonUtils;
import io.activej.jsonrpc.JsonRpcBatch;
import io.activej.jsonrpc.JsonRpcDecoded;
import io.activej.jsonrpc.JsonRpcDecoder;
import io.activej.jsonrpc.JsonRpcEncoder;
import io.activej.jsonrpc.JsonRpcError;
import io.activej.jsonrpc.JsonRpcErrors;
import io.activej.jsonrpc.JsonRpcException;
import io.activej.jsonrpc.JsonRpcId;
import io.activej.jsonrpc.JsonRpcInput;
import io.activej.jsonrpc.JsonRpcLimits;
import io.activej.jsonrpc.JsonRpcMalformed;
import io.activej.jsonrpc.JsonRpcMessage;
import io.activej.jsonrpc.JsonRpcOutput;
import io.activej.jsonrpc.JsonRpcPayload;
import io.activej.jsonrpc.JsonRpcRequest;
import io.activej.jsonrpc.JsonRpcResponse;
import io.activej.jsonrpc.schema.JsonRpcSchemaGenerator;
import io.activej.jsonrpc.schema.OpenRpcDocument;
import io.activej.jsonrpc.schema.OpenRpcInfo;
import io.activej.jsonrpc.service.impl.ParamsCodec;
import io.activej.promise.Promise;
import io.activej.promise.Promises;
import io.activej.reactor.AbstractReactive;
import io.activej.reactor.Reactor;
import io.activej.reactor.jmx.ReactiveJmxBean;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiConsumer;

import static io.activej.common.Checks.checkArgument;
import static io.activej.reactor.Reactive.checkInReactorThread;
import static java.nio.charset.StandardCharsets.US_ASCII;

/**
 * The server half: a wire name arrives, an implementation is invoked with decoded arguments, and its result
 * leaves as a JSON-RPC response (FR-035…FR-058).
 *
 * <pre>{@code
 * JsonRpcDispatcher dispatcher = JsonRpcDispatcher.builder(reactor)
 *     .withService(UserApi.class, new UserApiImpl())
 *     .build();
 *
 * transport.setListener(document -> dispatcher.dispatch(document).whenResult(transport::send));
 * }</pre>
 *
 * <h2>Both entry points are total (FR-038a)</h2>
 * Neither {@link #dispatch(byte[])} nor {@link #dispatch(JsonRpcInput)} ever completes its promise
 * exceptionally — not for a truncated document, not for an unknown method, not for a throwing
 * implementation. Every failure is a JSON-RPC <b>error document</b>, so a transport author writes
 * {@code dispatch(...).then(this::respond)} with no failure branch. The single exception is the
 * reactor-thread guard, which is programmer error and fires before any dispatch begins.
 * <p>
 * {@link #dispatch(byte[])} is implemented <b>in terms of</b> {@link #dispatch(JsonRpcInput)} so that the two
 * cannot diverge (FR-039), and returns a <b>zero-length</b> array when there is no response document — which
 * is not {@code []} and not <code>{}</code>.
 *
 * <h2>Immutable, and deliberately not closeable</h2>
 * The method table is built once at {@link Builder#build()} and never mutated; the dispatcher holds no
 * per-call state (FR-057). It is <b>not</b> {@code AsyncCloseable} and exposes no {@code close()}: it owns no
 * resource and keeps no in-flight registry, so a {@code close()} would be a promise it could not keep
 * (FR-057a). Cancelling server-side work when a connection drops needs knowledge only a transport has.
 *
 * <h2>Wire behaviour</h2>
 * <table border="1">
 *     <caption>one inbound element, one outcome</caption>
 *     <tr><th>Input</th><th>Output</th><th>Invokes the implementation?</th></tr>
 *     <tr><td>request, known method, params decode</td><td>{@code result} response</td><td>yes</td></tr>
 *     <tr><td>request, unknown method</td><td>{@code -32601}</td><td><b>no</b></td></tr>
 *     <tr><td>request, params fail to decode</td><td>{@code -32602}, no {@code data}</td><td><b>no</b></td></tr>
 *     <tr><td>implementation fails with {@link JsonRpcException}</td><td>that error verbatim</td><td>yes</td></tr>
 *     <tr><td>implementation fails otherwise</td><td>{@code -32603}, <b>no {@code data}</b></td><td>yes</td></tr>
 *     <tr><td>notification, any outcome</td><td><b>nothing</b></td><td>yes</td></tr>
 *     <tr><td>{@link JsonRpcMalformed}</td><td>its {@code toResponse()}, unchanged</td><td>no</td></tr>
 *     <tr><td>{@link JsonRpcResponse}</td><td>nothing, and not an error</td><td>no</td></tr>
 *     <tr><td>{@link JsonRpcBatch}</td><td>the producing elements in request order, or nothing</td>
 *         <td>per element</td></tr>
 *     <tr><td>{@code rpc.discover}, discovery enabled</td><td>the pre-computed OpenRPC document</td>
 *         <td><b>no</b> — the dispatcher owns it</td></tr>
 *     <tr><td>{@code rpc.discover}, discovery disabled</td><td>{@code -32601}, like any unknown name</td>
 *         <td><b>no</b></td></tr>
 *     <tr><td>request, at the in-flight bound</td><td>{@code -32005 Server busy}</td><td><b>no</b></td></tr>
 *     <tr><td>notification, at the in-flight bound</td><td><b>nothing</b></td><td><b>no</b></td></tr>
 * </table>
 *
 * <h2>The in-flight bound (FR-033…FR-036)</h2>
 * A batch bounds how many <i>elements</i> arrive at once; it bounds no <i>work</i> at all, because every
 * element is dispatched concurrently. {@link #MAX_IN_FLIGHT} closes that gap: a reactor-confined counter of
 * service invocations that have started and not yet completed, and at the ceiling further elements are
 * <b>shed</b> rather than queued — a request answers {@code -32005 Server busy}, a notification answers
 * nothing at all, and neither reaches an implementation. Rejection is <b>per element and in document
 * order</b>, so the part of a batch that fits proceeds exactly as it would have alone.
 * <p>
 * The counter brackets the <b>invocation</b> only. An unknown name ({@code -32601}), {@code params} that
 * fail to decode ({@code -32602}) and a malformed element never reach a handler, so they neither consume a
 * slot nor can be shed by the bound; and every invocation decrements on completion, <b>failure included</b>,
 * or the ceiling would ratchet upwards until nothing was ever admitted again.
 * <p>
 * This is a counter, not a registry: no per-call state is retained, nothing is cancelled and there is still
 * no {@code close()} (FR-057a). The bound is <b>per dispatcher</b>, which under a worker pool means per
 * reactor — the correct granularity, since what it protects is one reactor's own queue.
 *
 * <h2>{@code rpc.discover} is a table entry, not a special case (FR-030)</h2>
 * {@link Builder#withDiscovery(OpenRpcInfo)} makes {@code doBuild()} generate the OpenRPC document once and
 * register a <b>dispatcher-owned entry</b> for {@code rpc.discover} into the same frozen handler table user
 * methods live in. Everything else then follows from machinery that already existed, with no parallel path:
 * {@link #wireNames()} carries the name, {@link Inspector#initialize(Set)} receives it in the closed set (so
 * it gets its own JMX row rather than moving the aggregate-only {@code methodNotFound} counter), a batch
 * element produces its own response element, a notification produces none, non-empty {@code params} is the
 * ordinary {@code -32602}, and with discovery <i>off</i> the name is simply not in the table — answered
 * {@code -32601}, byte-indistinguishable from any other unregistered name (FR-004).
 * <p>
 * A <i>user</i> method can never claim the name: contract rule 10 refuses the whole reserved {@code rpc.}
 * namespace at {@code build()} (FR-001), so the built-in entry cannot displace anything.
 * <p>
 * The generated bytes are also readable out-of-band through {@link #discoveryDocument()}, which is how a
 * transport serves the document without going through a JSON-RPC call at all (FR-040, FR-041). It is the
 * <b>same array</b> the table entry answers with, so the two cannot drift.
 */
public final class JsonRpcDispatcher extends AbstractReactive implements JsonRpcPeerHandler, ReactiveJmxBean {
	/**
	 * The concurrent-invocation ceiling every dispatcher starts with — {@code 1000}, overridable process-wide
	 * with {@code -DJsonRpcDispatcher.maxInFlight=...} (or the fully qualified spelling) and per dispatcher
	 * with {@link Builder#withMaxInFlight(int)}.
	 * <p>
	 * An order of magnitude above {@code JsonRpcLimits.MAX_BATCH_SIZE} (100), so a full legitimate batch is
	 * never shed by the default, and far below any reactor-queue comfort zone under hostile fan-out. There is
	 * no "off" value: a consumer opts out by raising the bound, never by disabling it (constitution III), and
	 * anything below {@code 1} is refused at {@code build()}.
	 */
	public static final int MAX_IN_FLIGHT = ApplicationSettings.getInt(JsonRpcDispatcher.class, "maxInFlight", 1000);

	/** The wire rendering of a {@code Promise<Void>} result: the JSON literal {@code null} (FR-030). */
	private static final byte[] NULL_LITERAL = "null".getBytes(US_ASCII);

	/** The only name this dispatcher ever registers on its own behalf (FR-002). */
	private static final String DISCOVER = "rpc.discover";

	@Nullable Inspector inspector;
	private Map<String, Handler> handlers = Map.of();
	private Set<String> wireNames = Set.of();
	/** Immutable after {@code build()}, which is also where {@code < 1} is refused. */
	private int maxInFlight = MAX_IN_FLIGHT;
	/**
	 * Service invocations started and not yet completed. Reactor-confined, so a plain {@code int} is the
	 * honest type — every mutation happens on the dispatcher's own thread, behind the reactor-thread guard
	 * that opens both {@code dispatch} entry points.
	 */
	private int inFlight;
	/** The array {@link #DISCOVER}'s table entry answers with, or {@code null} when discovery is off. */
	private byte @Nullable [] discoveryDocument;
	private BiConsumer<JsonRpcMethodDescriptor, Exception> failureHandler;

	/**
	 * The observation seam (FR-030…FR-034). A plain interface with <b>no JMX types in its signature</b>, so a
	 * consumer may implement it for logging or tracing without any JMX dependency.
	 * <p>
	 * Every callback fires on the dispatcher's reactor thread. A throwing implementation is ignored — the
	 * dispatcher never propagates, and a dispatch never fails because its inspector failed (FR-040).
	 * {@link #initialize(Set)} is the one exception to the threading rule: it fires once, during
	 * {@code build()}, on the <b>builder's</b> thread, with the frozen registered wire-name set.
	 * <p>
	 * The asymmetry between {@link #onMethodNotFound(String)} and every other callback is the type system
	 * carrying FR-034: a callback that receives a {@link JsonRpcMethodDescriptor} can only have been reached
	 * through the closed handler table, so an implementation <i>cannot</i> key a map on a wire-supplied name
	 * by accident. {@code onMethodNotFound} is the one callback that does see wire text — <b>it is
	 * aggregate-only</b> and must never be used to retain, key or expose the name it receives; doing so would
	 * be a memory-exhaustion primitive, since the name set is unbounded (FR-034).
	 */
	public interface Inspector extends BaseInspector<Inspector> {
		/** A registered method is about to be invoked. */
		void onRequest(JsonRpcMethodDescriptor descriptor);

		/** A registered method succeeded; {@code durationMillis} is the invocation duration, {@code >= 0}. */
		void onResponse(JsonRpcMethodDescriptor descriptor, long durationMillis);

		/**
		 * A registered method produced an error object; {@code errorCode} is a JSON-RPC code <b>chosen by this
		 * server</b> — a named {@link JsonRpcErrors} code or an application's own — never echoed from a request.
		 */
		void onError(JsonRpcMethodDescriptor descriptor, int errorCode, long durationMillis);

		/**
		 * <b>Aggregate-only.</b> No descriptor exists for {@code requestedName} — the name came from the wire.
		 * Implementations MUST NOT retain it, key a map by it, or expose it; it is a count signal, not data
		 * (FR-034).
		 */
		void onMethodNotFound(String requestedName);

		/** The document never resolved to a method at all — it was malformed. */
		void onMalformed();

		/**
		 * <b>Aggregate-only.</b> One element was shed at the in-flight bound — a request answered
		 * {@code -32005 Server busy}, or a notification answered nothing at all (FR-034, FR-035).
		 * <p>
		 * There is deliberately no argument: a shed element never reached a handler, so it has no
		 * {@link JsonRpcMethodDescriptor}, and the wire name it carried must not become a key (FR-034 —
		 * the same rule that makes {@link #onMethodNotFound(String)} aggregate-only). Requests and
		 * notifications fold into one count, because "how much was shed" is one question.
		 * <p>
		 * A {@code default} no-op, like {@link #initialize(Set)}: this callback was added after the seam
		 * shipped, and an existing {@link Inspector} that does not know about it must keep compiling and
		 * keep working. An implementation that wants the count overrides it.
		 */
		default void onRejected() {}

		/**
		 * The lifecycle hook the dispatcher's {@code doBuild()} calls exactly once, when the handler table
		 * is frozen, with the <b>closed</b> registered wire-name set (FR-034 — it can never grow from the
		 * wire). A default no-op, so a logging or tracing inspector needs no implementation; an inspector
		 * that keeps per-method state pre-populates it here rather than on the call path.
		 * <p>
		 * Implementations must not retain the set for keying beyond their own lifetime or expose it: the
		 * names are server-registered, but {@link #onMethodNotFound(String)} is the only callback that
		 * must never retain its argument. An instance is owned by <b>one</b> dispatcher — a second
		 * {@code initialize} call is the dispatcher-build misusing a shared inspector. A <b>delegating</b>
		 * inspector (one whose {@link #lookup} forwards) must forward this call to its delegate too —
		 * the dispatcher reaches the delegate through the seam, not through {@code lookup}.
		 */
		default void initialize(Set<String> wireNames) {}
	}

	/**
	 * The statistics-carrying {@link Inspector} (FR-030…FR-034). {@code methodStats} is built <b>once</b> from
	 * the dispatcher's {@link #wireNames()} inside {@link Builder#doBuild()}, when the handler table is already
	 * frozen, and is never written to afterwards. There is <b>no {@code computeIfAbsent}</b> anywhere in this
	 * class: a lookup miss is a {@code null}, and {@link #onMethodNotFound(String)} / {@link #onMalformed()}
	 * never touch the map (FR-034).
	 * <p>
	 * Single-ownership: an instance is wired into <b>one</b> dispatcher, which calls {@link #initialize} once
	 * at {@code build()}. Re-using the instance for a second dispatcher is refused, not silently accepted —
	 * it would wipe the first dispatcher's rows.
	 */
	public static class JmxInspector extends AbstractInspector<Inspector> implements Inspector {
		private static final Duration SMOOTHING_WINDOW = Duration.ofMinutes(1);

		private Map<String, JsonRpcMethodStats> methodStats = Map.of();
		private boolean initialized;
		private final EventStats methodNotFound = EventStats.create(SMOOTHING_WINDOW);
		private final EventStats malformedDocuments = EventStats.create(SMOOTHING_WINDOW);
		private final EventStats totalRequests = EventStats.create(SMOOTHING_WINDOW);
		private final EventStats totalErrors = EventStats.create(SMOOTHING_WINDOW);
		private final EventStats rejectedRequests = EventStats.create(SMOOTHING_WINDOW);

		/**
		 * The dispatcher this inspector was wired into, set once by the same {@code doBuild()} that calls
		 * {@link #initialize(Set)}. The counters above are <b>events</b> this seam is told about; the two
		 * in-flight attributes are <b>gauges</b> — a configuration value and a live count — that only the
		 * dispatcher owns, so they are read through here rather than mirrored and kept in step by hand.
		 * <p>
		 * {@code null} only for an inspector no dispatcher ever built with, in which case both gauges read
		 * {@code 0} — a value {@code maxInFlight} can never legally take, so "not wired" is legible rather
		 * than plausible.
		 */
		private @Nullable JsonRpcDispatcher dispatcher;

		@Override
		public void onRequest(JsonRpcMethodDescriptor descriptor) {
			totalRequests.recordEvent();
		}

		@Override
		public void onResponse(JsonRpcMethodDescriptor descriptor, long durationMillis) {
			JsonRpcMethodStats stats = requireStats(descriptor);
			stats.getSuccessfulRequests().recordEvent();
			stats.getRequestHandlingTime().recordValue(durationMillis);
		}

		@Override
		public void onError(JsonRpcMethodDescriptor descriptor, int errorCode, long durationMillis) {
			totalErrors.recordEvent();
			JsonRpcMethodStats stats = requireStats(descriptor);
			stats.getFailedRequests().recordEvent();
			stats.getRequestHandlingTime().recordValue(durationMillis);
			EventStats bucket = stats.getErrorsByCode().get(errorCode);
			if (bucket != null) {
				bucket.recordEvent();
			} else {
				// FR-033a: an application-chosen code outside the nine named codes never creates an entry
				stats.getOtherErrors().recordEvent();
			}
		}

		@Override
		public void onMethodNotFound(String requestedName) {
			// aggregate-only by contract — the name is a count signal, never data (FR-034)
			methodNotFound.recordEvent();
			totalRequests.recordEvent();
			totalErrors.recordEvent();
		}

		@Override
		public void onMalformed() {
			malformedDocuments.recordEvent();
		}

		/**
		 * Aggregate-only, by construction: a shed element never reached a handler, so there is no row to
		 * attribute it to and no {@code -32005} bucket to fill — {@link JsonRpcErrors#named()} deliberately
		 * excludes {@code SERVER_BUSY} for exactly this reason. Requests and notifications fold together.
		 * <p>
		 * It moves <b>this counter and nothing else</b>: not {@code totalRequests} (no request was ever
		 * announced), not {@code totalErrors} (no method produced one), and no {@code methodStats} row.
		 */
		@Override
		public void onRejected() {
			rejectedRequests.recordEvent();
		}

		/**
		 * The dispatcher's own hook, called once at {@code build()} beside {@link #initialize(Set)}.
		 * <p>
		 * Not itself guarded against a second call: an instance is meant for **one** dispatcher
		 * (class Javadoc above), and wiring it into a second {@code doBuild()} silently rebinds
		 * {@link #dispatcher} to the new one — the first dispatcher's gauges would then read through
		 * an inspector that no longer points at it. Don't reuse an instance across dispatchers.
		 */
		private void bindTo(JsonRpcDispatcher dispatcher) {
			this.dispatcher = dispatcher;
		}

		/**
		 * The row for a registered descriptor must exist — the map was built from the frozen
		 * {@code wireNames()} at dispatcher build. A miss means this inspector was never wired into a
		 * dispatcher ({@code initialize} was never called); failing loudly here beats the dispatcher's
		 * totality wrapper silently swallowing the misuse.
		 */
		private JsonRpcMethodStats requireStats(JsonRpcMethodDescriptor descriptor) {
			JsonRpcMethodStats stats = methodStats.get(descriptor.wireName());
			if (stats == null) {
				throw new IllegalStateException(
					"this JmxInspector has no row for '" + descriptor.wireName() +
					"' — it was not wired into a dispatcher (initialize was never called)");
			}
			return stats;
		}

/** The {@link Inspector} lifecycle hook: pre-populates the per-method rows, once, from the frozen set. */
		@Override
		public void initialize(Set<String> wireNames) {
		if (initialized) {
			throw new IllegalStateException(
				"this JmxInspector is already wired into a dispatcher; an inspector belongs to one dispatcher only");
		}
		initialized = true;
		Map<String, JsonRpcMethodStats> built = new LinkedHashMap<>();
		for (String wireName : wireNames) {
			built.put(wireName, JsonRpcMethodStats.create());
		}
		this.methodStats = Collections.unmodifiableMap(built);
	}

		/**
		 * One row per registered wire name; the row set is fixed at dispatcher {@code build()} (FR-034).
		 * <p>
		 * Deliberately <b>without</b> a reducer: the map's value type is a {@code JsonRpcMethodStats}
		 * pojo, and a sum reducer on the map would be applied to the values themselves. Under a worker
		 * pool the aggregation happens one level down — each row's {@code EventStats}/{@code ValueStats}
		 * members combine via {@code JmxStats.add} — which is what makes the aggregated attribute equal
		 * the sum over workers (FR-038, verified by the multi-worker test).
		 */
		@JmxAttribute
		public Map<String, JsonRpcMethodStats> getMethodStats() {
			return methodStats;
		}

		@JmxAttribute(reducer = JmxReducerSum.class, extraSubAttributes = "totalCount")
		public EventStats getTotalRequests() {
			return totalRequests;
		}

		@JmxAttribute(reducer = JmxReducerSum.class, extraSubAttributes = "totalCount")
		public EventStats getTotalErrors() {
			return totalErrors;
		}

		@JmxAttribute(reducer = JmxReducerSum.class, extraSubAttributes = "totalCount")
		public EventStats getMethodNotFound() {
			return methodNotFound;
		}

		@JmxAttribute(reducer = JmxReducerSum.class, extraSubAttributes = "totalCount")
		public EventStats getMalformedDocuments() {
			return malformedDocuments;
		}

		/**
		 * How many elements were shed at the in-flight bound, cumulatively — requests and notifications
		 * together (FR-038). Summed across workers, like every other {@code EventStats} here: shedding is
		 * per reactor, and the operator's question is how much was shed in total.
		 */
		@JmxAttribute(reducer = JmxReducerSum.class, extraSubAttributes = "totalCount",
			description = "elements shed at the in-flight bound (-32005), requests and notifications")
		public EventStats getRejectedRequests() {
			return rejectedRequests;
		}

		/**
		 * The number of registered wire names. Deliberately <b>without</b> a reducer: it is identical on every
		 * worker, and the platform's default aggregation ({@code JmxReducerDistinct}) reads the single correct
		 * value — a sum reducer would report {@code workers × methods} on the aggregated bean, exactly like the
		 * read-only {@link #getMaxBatchSize()}/{@link #getMaxJsonDepth()} neighbours.
		 */
		@JmxAttribute
		public int getRegisteredMethods() {
			return methodStats.size();
		}

		/**
		 * The effective process-wide {@code JsonRpcLimits} value — read-only, so an operator can observe what is
		 * in force even though no config key sets it (FR-037).
		 */
		@JmxAttribute(description = "effective JsonRpcLimits.MAX_BATCH_SIZE (process-wide, read-only)")
		public int getMaxBatchSize() {
			return JsonRpcLimits.MAX_BATCH_SIZE;
		}

		@JmxAttribute(description = "effective JsonRpcLimits.MAX_JSON_DEPTH (process-wide, read-only)")
		public int getMaxJsonDepth() {
			return JsonRpcLimits.MAX_JSON_DEPTH;
		}

		/**
		 * This dispatcher's configured in-flight ceiling — read-only, so an operator can read what is in
		 * force beside the two envelope limits above (FR-038).
		 * <p>
		 * Deliberately <b>without</b> a reducer, exactly like {@link #getMaxBatchSize()}: the value is
		 * identical on every worker and the platform's default aggregation reads the single correct one. A
		 * sum would report {@code workers × maxInFlight}, which is the aggregate ceiling — a real number,
		 * but not the one this attribute names, and {@link #getInFlight()} beside it is what makes the
		 * per-worker reading the useful one.
		 */
		@JmxAttribute(description = "the configured concurrent in-flight ceiling (per dispatcher, read-only)")
		public int getMaxInFlight() {
			return dispatcher == null ? 0 : dispatcher.maxInFlight;
		}

		/**
		 * Service invocations in progress <b>right now</b> — a gauge, not a counter (FR-038). Summed across
		 * workers, because concurrent work in a worker pool is what an operator watches against the
		 * aggregate ceiling.
		 * <p>
		 * A JMX read happens on the reading thread rather than on the dispatcher's, so this may observe a
		 * momentarily stale value — the same property every {@code EventStats} attribute here already has,
		 * and the reason the counter itself is never <i>written</i> anywhere but the reactor thread. The
		 * bound is enforced against the field, never against this getter.
		 */
		@JmxAttribute(reducer = JmxReducerSum.class,
			description = "service invocations in progress right now")
		public int getInFlight() {
			return dispatcher == null ? 0 : dispatcher.inFlight;
		}

		@Override
		public String toString() {
			return "JmxInspector[" + methodStats.size() + " methods, " +
				totalRequests.getTotalCount() + " totalRequests, " +
				totalErrors.getTotalCount() + " totalErrors]";
		}
	}

	private JsonRpcDispatcher(Reactor reactor) {
		super(reactor);
		this.failureHandler = (descriptor, e) -> reactor.logFatalError(e, descriptor);
	}

	/**
	 * Starts a dispatcher on {@code reactor}. There is no {@code create(...)} shortcut: a dispatcher with no
	 * service registered would answer every method {@code -32601}, which is never what anyone meant.
	 *
	 * @throws NullPointerException if {@code reactor} is {@code null}
	 */
	public static Builder builder(Reactor reactor) {
		return new JsonRpcDispatcher(Objects.requireNonNull(reactor, "reactor")).new Builder();
	}

	/** Registers the services, then validates every one of them at {@link #doBuild()}. */
	public final class Builder extends AbstractBuilder<Builder, JsonRpcDispatcher> {
		private final Map<Class<?>, Object> services = new LinkedHashMap<>();
		private JsonCodecFactory codecFactory = JsonCodecFactory.defaultInstance();
		private @Nullable OpenRpcInfo discoveryInfo;

		private Builder() {}

		/**
		 * Registers one service. Repeatable; the contract is built at {@link #build()}, so registration order
		 * and {@link #withCodecFactory} order do not matter.
		 *
		 * @param serviceType    the annotated interface
		 * @param implementation an instance of it
		 * @throws IllegalArgumentException if the interface is already registered, or the implementation is
		 *                                  not an instance of it
		 */
		public <T> Builder withService(Class<T> serviceType, T implementation) {
			checkNotBuilt(this);
			Objects.requireNonNull(serviceType, "serviceType");
			Objects.requireNonNull(implementation, "implementation");
			if (!serviceType.isInstance(implementation)) {
				throw new IllegalArgumentException(
					implementation.getClass().getName() + " is not an instance of " + serviceType.getName());
			}
			if (services.putIfAbsent(serviceType, implementation) != null) {
				throw new IllegalArgumentException(
					serviceType.getName() + " is already registered; one interface has one implementation");
			}
			return this;
		}

		/** The factory every parameter and result codec is resolved through. Defaults to the shared instance. */
		public Builder withCodecFactory(JsonCodecFactory codecFactory) {
			checkNotBuilt(this);
			this.codecFactory = Objects.requireNonNull(codecFactory, "codecFactory");
			return this;
		}

		/**
		 * Where a <b>notification</b>'s failure goes — a notification produces no response element, so
		 * without this the failure would be silently dropped (FR-050). Defaults to
		 * {@code Reactor.logFatalError(e, descriptor)}.
		 */
		public Builder withFailureHandler(BiConsumer<JsonRpcMethodDescriptor, Exception> failureHandler) {
			checkNotBuilt(this);
			JsonRpcDispatcher.this.failureHandler = Objects.requireNonNull(failureHandler, "failureHandler");
			return this;
		}

		/**
		 * The ceiling on <b>concurrent service invocations</b> for this dispatcher, overriding
		 * {@link #MAX_IN_FLIGHT} (FR-033). At the ceiling a request is answered {@code -32005 Server busy}
		 * and a notification is answered nothing at all — per element, in document order — and neither
		 * reaches an implementation.
		 * <p>
		 * This is not {@code JsonRpcLimits.MAX_BATCH_SIZE}: that one bounds how many elements one document
		 * may carry, this one bounds how much work may be outstanding at once, across every document and
		 * every connection this dispatcher serves.
		 *
		 * @param maxInFlight at least {@code 1}; validated at {@link #build()}, not here, so a whole
		 *                    configuration is reported by one failure rather than by the first setter that
		 *                    happens to run
		 */
		public Builder withMaxInFlight(int maxInFlight) {
			checkNotBuilt(this);
			JsonRpcDispatcher.this.maxInFlight = maxInFlight;
			return this;
		}

		/**
		 * Installs the observation seam. The inspector's per-method table is pre-populated from the frozen
		 * {@code wireNames()} inside {@link #doBuild()}; a throwing inspector never breaks a dispatch (FR-040).
		 */
		public Builder withInspector(Inspector inspector) {
			checkNotBuilt(this);
			JsonRpcDispatcher.this.inspector = Objects.requireNonNull(inspector, "inspector");
			return this;
		}

		/**
		 * Enables OpenRPC service discovery: {@code build()} generates the document describing every
		 * registered service and registers a dispatcher-owned {@code rpc.discover} entry into the frozen
		 * handler table (FR-002, FR-030).
		 * <p>
		 * <b>Presence is the switch</b> (ADR-042): discovery is off unless this method is called, and there is
		 * no {@code withDiscovery(boolean)} — a second flag could disagree with the info that only makes sense
		 * when the feature is on. Off means the name is not in the table at all, so it is answered
		 * {@code -32601} exactly as any unregistered name is (FR-004).
		 * <p>
		 * The document is a <b>disclosure surface</b>: it names every wire method and describes every parameter
		 * and result type. That is why it is opt-in rather than a default.
		 *
		 * @param info the OpenRPC Info Object — {@code title} and {@code version}, both REQUIRED by OpenRPC and
		 *             both <b>supplied by the application</b>. Nothing here derives a title from a class name
		 *             or a version from a POM (FR-013)
		 * @throws NullPointerException if {@code info} is {@code null}
		 */
		public Builder withDiscovery(OpenRpcInfo info) {
			checkNotBuilt(this);
			this.discoveryInfo = Objects.requireNonNull(info, "info");
			return this;
		}

		/**
		 * Exactly {@link #withDiscovery(OpenRpcInfo)} over {@code new OpenRpcInfo(title, version)} — the two
		 * members are the whole of what an application supplies, so spelling them at the call site reads
		 * better than assembling a carrier for them.
		 *
		 * @param title   the service's name, as the application states it
		 * @param version the service's <b>API</b> version, unrelated to the OpenRPC specification version
		 */
		public Builder withDiscovery(String title, String version) {
			return withDiscovery(new OpenRpcInfo(title, version));
		}

		/**
		 * @throws IllegalArgumentException if {@code maxInFlight} is below {@code 1} (FR-033)
		 * @throws JsonRpcContractException if any registered interface breaks the contract, or if two
		 *                                  interfaces claim the same wire name (FR-036, FR-037)
		 */
		@Override
		protected JsonRpcDispatcher doBuild() {
			// the cheap configuration check first: a dispatcher that could never admit an invocation is a
			// misconfiguration, and reporting it costs nothing next to validating every registered contract
			checkArgument(maxInFlight >= 1,
				"maxInFlight must be at least 1 (there is no value that disables the bound): %s", maxInFlight);

			Map<String, Handler> built = new LinkedHashMap<>();
			List<String> collisions = new ArrayList<>();
			List<JsonRpcServiceContract> contracts = new ArrayList<>(services.size());

			for (Map.Entry<Class<?>, Object> entry : services.entrySet()) {
				Class<?> serviceType = entry.getKey();
				Object implementation = entry.getValue();
				// FR-036: a broken interface fails here, before the dispatcher exists and before any
				// transport could have been constructed
				JsonRpcServiceContract contract = JsonRpcServiceContract.of(serviceType, codecFactory);
				contracts.add(contract);

				for (JsonRpcMethodDescriptor descriptor : contract.methods().values()) {
					Handler previous = built.putIfAbsent(descriptor.wireName(),
						new Handler(descriptor, implementation));
					if (previous != null) {
						collisions.add("wire name '" + descriptor.wireName() + "' is claimed by two services: " +
									   previous.descriptor.method().getDeclaringClass().getName() + " and " +
									   serviceType.getName());
					}
				}
			}

			if (!collisions.isEmpty()) throw new JsonRpcContractException("the registered services", collisions);

			if (discoveryInfo != null) {
				// FR-015 / FR-032 / FR-040: generated ONCE, here, by the one generator the offline export and
				// the frozen references also call. The contracts are exactly the ones the table above was
				// built from, so the document cannot describe a method this dispatcher does not serve
				byte[] document = JsonRpcSchemaGenerator.generateBytes(discoveryInfo, contracts);
				// the SAME array the table entry below serves, kept for the transport tier to read through
				// discoveryDocument(). Not a second copy and not a second generation: an HTTP GET endpoint
				// and rpc.discover answer with one array, which is what makes them byte-identical by
				// construction rather than by two code paths agreeing (FR-040, FR-041)
				JsonRpcDispatcher.this.discoveryDocument = document;
				// research Decision 1: a real entry in the same frozen table, never a branch before the
				// lookup. Contract rule 10 (FR-001) refuses every user wire name in the reserved 'rpc.'
				// namespace, so there is nothing here for this put to displace
				built.put(DISCOVER, Handler.builtIn(
					new JsonRpcMethodDescriptor(DISCOVER, DISCOVER_METHOD, false, List.of(),
						OpenRpcDocument.CODEC, false),
					JsonRpcPayload.raw(document, 0, document.length)));
			}

			JsonRpcDispatcher.this.handlers = built;
			JsonRpcDispatcher.this.wireNames = Collections.unmodifiableSet(built.keySet());
			// FR-034: wire the inspector to the handler table only now that wireNames is frozen, so the
			// per-method rows are exactly the registered names and nothing can grow the set afterwards.
			// Called through the Inspector interface (default no-op) so the resolution path matches
			// getStats()'s BaseInspector.lookup — a composite inspector reached there is reached here too
			if (inspector != null) inspector.initialize(JsonRpcDispatcher.this.wireNames);
			// FR-038: the two in-flight attributes are gauges over dispatcher state, so the JmxInspector
			// getStats() will hand out — resolved through the very same lookup — is given the back-reference
			// here, once, when everything it may read is final
			JmxInspector jmx = BaseInspector.lookup(inspector, JmxInspector.class);
			if (jmx != null) jmx.bindTo(JsonRpcDispatcher.this);
			return JsonRpcDispatcher.this;
		}
	}

	/**
	 * The whole-document entry point: decode, dispatch, encode.
	 *
	 * @param document one complete, contiguous JSON-RPC document
	 * @return the response document, or a <b>zero-length</b> array when there is none — a lone notification,
	 * an all-notification batch, or an inbound response. Never completes exceptionally (FR-038a)
	 */
	public Promise<byte[]> dispatch(byte[] document) {
		checkInReactorThread(this);
		Objects.requireNonNull(document, "document");
		JsonRpcInput input;
		try {
			input = JsonRpcDecoder.decode(document);
		} catch (RuntimeException e) {
			// JsonRpcDecoder.decode is documented total and returns a JsonRpcMalformed rather than throwing.
			// This is the belt to that braces: FR-038a is a promise made to a transport author about EVERY
			// input, and it must not rest on another component's documentation alone
			return Promise.of(JsonRpcEncoder.encode(
				JsonRpcResponse.ofError(JsonRpcId.NULL, JsonRpcErrors.PARSE_ERROR)));
		}
		return dispatch(input).map(this::encodeOutput);
	}

	/**
	 * {@link JsonRpcEncoder#encode(JsonRpcOutput)}, guarded. FR-047 lets a service fail with a
	 * {@link JsonRpcException} carrying arbitrary {@code data}; if that payload's own codec throws while
	 * being written, the failure would otherwise surface here — one layer further out than
	 * {@link #encodeResult}'s equivalent guard on the success path — and break FR-038a's totality exactly as
	 * a throwing result codec would. The wire name is dropped either way, so the id is the only thing worth
	 * salvaging.
	 */
	private byte[] encodeOutput(JsonRpcOutput output) {
		try {
			return JsonRpcEncoder.encode(output);
		} catch (RuntimeException e) {
			JsonRpcId id = output instanceof JsonRpcOutput.Single single && single.message() instanceof JsonRpcResponse response ?
				response.id() : JsonRpcId.NULL;
			return JsonRpcEncoder.encode(JsonRpcResponse.ofError(id, JsonRpcErrors.INTERNAL_ERROR));
		}
	}

	/**
	 * The structured entry point, for a transport that has already decoded (FR-039).
	 *
	 * @param input one decoded document
	 * @return what to send: {@link JsonRpcOutput#none()}, one message, or a batch. Never completes
	 * exceptionally (FR-038a)
	 */
	public Promise<JsonRpcOutput> dispatch(JsonRpcInput input) {
		checkInReactorThread(this);
		Objects.requireNonNull(input, "input");
		return switch (input) {
			case JsonRpcDecoded decoded -> dispatchElement(decoded);
			case JsonRpcBatch batch -> dispatchBatch(batch);
		};
	}

	/**
	 * {@link JsonRpcPeerHandler}'s seam, so {@code withPeerHandler(dispatcher)} needs no method reference:
	 * {@link JsonRpcDecoded} is a narrower {@link JsonRpcInput}, so this is exactly {@link #dispatch(JsonRpcInput)}
	 * under the name a {@link JsonRpcClient} looks for.
	 */
	@Override
	public Promise<JsonRpcOutput> handle(JsonRpcDecoded incoming) {
		return dispatch(incoming);
	}

	/**
	 * The wire names this dispatcher resolves — the diagnostic a transport author needs when a call comes
	 * back {@code -32601} (FR-039a). Read-only, and fixed at {@code build()}.
	 */
	public Set<String> wireNames() {
		checkInReactorThread(this);
		return wireNames;
	}

	/**
	 * The OpenRPC document this dispatcher answers {@code rpc.discover} with — <b>the</b> array, computed once
	 * at {@code build()} and never regenerated (FR-015, FR-032).
	 * <p>
	 * This is the third consumer of the one generator (FR-040). The runtime answer reads the payload built
	 * from this array, the offline export calls {@link JsonRpcSchemaGenerator#generateBytes} with the same
	 * contracts and the same {@link OpenRpcInfo}, and a transport tier serving the document out-of-band — the
	 * HTTP {@code GET} endpoint of FR-041 — reads it here. All three are byte-identical because they are one
	 * document, not three that agree.
	 *
	 * <h4>{@code null} is the whole of "discovery is off"</h4>
	 * {@code null} <b>iff</b> {@link Builder#withDiscovery(OpenRpcInfo)} was never called, exactly as
	 * {@code rpc.discover} is then simply absent from {@link #wireNames()}. There is no empty document and no
	 * disabled-signalling document: presence <i>is</i> the switch (ADR-042), so a transport can decide whether
	 * to mount an endpoint at all from this one value, and a disabled deployment exposes no path to probe
	 * (FR-042).
	 *
	 * <h4>The caller does not own it</h4>
	 * The array is returned as-is, deliberately: it is a build-time constant shared with the frozen handler
	 * table, and copying it per call would put an allocation back on a path this feature made allocation-free.
	 * <b>It must not be modified</b> — a write here changes every subsequent {@code rpc.discover} answer and
	 * every response the {@code GET} endpoint has already been handed. A caller that needs a mutable array
	 * should {@code clone()} it, or call {@link JsonRpcSchemaGenerator#generateBytes}, which hands out a fresh
	 * one each time.
	 * <p>
	 * Reactor-thread-guarded like {@link #wireNames()}, and deliberately <b>not</b> a {@code @JmxAttribute}:
	 * JMX disclosure here is counts, latencies and error codes, never a payload (FR-055).
	 *
	 * @return the document as UTF-8 JSON, or {@code null} when discovery was never enabled
	 */
	public byte @Nullable [] discoveryDocument() {
		checkInReactorThread(this);
		return discoveryDocument;
	}

	/**
	 * The JMX view of this dispatcher — {@code null} unless an inspector resolving to a
	 * {@link JmxInspector} is installed. Deliberately <b>not</b> reactor-thread-guarded: JMX reads invoke
	 * this getter on the JMX thread (contract §6: with no inspector the whole surface reads as absent).
	 */
	@JmxAttribute(name = "")
	public @Nullable JmxInspector getStats() {
		return BaseInspector.lookup(inspector, JmxInspector.class);
	}

	@Override
	public String toString() {
		return "JsonRpcDispatcher[" + handlers.size() + " methods]";
	}

	// ---------------------------------------------------------------------------------------------------
	// Dispatch.
	// ---------------------------------------------------------------------------------------------------

	/** Every element is dispatched independently and concurrently; the answers keep request order (FR-053a). */
	private Promise<JsonRpcOutput> dispatchBatch(JsonRpcBatch batch) {
		List<Promise<JsonRpcOutput>> elements = new ArrayList<>(batch.size());
		for (JsonRpcDecoded element : batch.elements()) {
			elements.add(dispatchElement(element));
		}
		return Promises.toList(elements).map(outputs -> {
			List<JsonRpcMessage> messages = new ArrayList<>(outputs.size());
			for (JsonRpcOutput output : outputs) {
				if (output instanceof JsonRpcOutput.Single single) messages.add(single.message());
			}
			// FR-054: a batch that produced nothing is zero bytes, never the "[]" that is itself a -32600
			return messages.isEmpty() ? JsonRpcOutput.none() : JsonRpcOutput.batch(messages);
		});
	}

	private Promise<JsonRpcOutput> dispatchElement(JsonRpcDecoded element) {
		return switch (element) {
			case JsonRpcRequest request -> dispatchRequest(request);
			// the envelope record, not this package's @JsonRpcNotification annotation — the two share a
			// simple name on purpose, and this is one of the two files that must tell them apart
			case io.activej.jsonrpc.JsonRpcNotification notification -> dispatchNotification(notification);
			// FR-052: a bidirectional transport legitimately carries the peer's answers on the same channel
			case JsonRpcResponse ignored -> Promise.of(JsonRpcOutput.none());
			// FR-051: feature 01 already produced the normative error object; re-deriving it would be a
			// second answer to one question
			case JsonRpcMalformed malformed -> {
				notifyMalformed();
				yield Promise.of(JsonRpcOutput.single(malformed.toResponse()));
			}
		};
	}

	private Promise<JsonRpcOutput> dispatchRequest(JsonRpcRequest request) {
		Handler handler = handlers.get(request.method());
		// FR-041: a miss answers -32601 and invokes nothing at all
		if (handler == null) {
			notifyMethodNotFound(request.method());
			return Promise.of(error(request.id(), JsonRpcErrors.METHOD_NOT_FOUND));
		}

		// FR-034: shed before decoding the params and before announcing a request that will not happen —
		// the element was never attempted, which is exactly what -32005 says. Checked on the handler and
		// not on the wire name, so the dispatcher's own rpc.discover entry is bound like any other (ADR-045)
		if (inFlight >= maxInFlight) {
			notifyRejected();
			return Promise.of(error(request.id(), JsonRpcErrors.SERVER_BUSY));
		}

		notifyRequest(handler.descriptor);

		Object[] args;
		try {
			args = handler.paramsCodec.decode(request.params());
		} catch (Exception e) {
			// FR-045: the decoder's message embeds the offending input by construction, so it is dropped here
			// rather than mapped into the error object
			notifyError(handler.descriptor, JsonRpcErrors.INVALID_PARAMS.code(), 0);
			return Promise.of(error(request.id(), JsonRpcErrors.INVALID_PARAMS));
		}

		long startNanos = System.nanoTime();
		// FR-033: the counter brackets the invocation itself — nothing above this line has entered an
		// implementation, and both branches below decrement, so a failing method frees its slot too
		inFlight++;
		return handler.invoke(args)
			.map(
				value -> {
					inFlight--;
					notifyResponse(handler.descriptor, durationMillis(startNanos));
					return respond(handler, request.id(), value);
				},
				e -> {
					inFlight--;
					notifyError(handler.descriptor, codeOf(e), durationMillis(startNanos));
					return error(request.id(), errorOf(e));
				});
	}

	private Promise<JsonRpcOutput> dispatchNotification(io.activej.jsonrpc.JsonRpcNotification notification) {
		Handler handler = handlers.get(notification.method());
		// §4.1 forbids answering a notification, so an unknown one is dropped rather than turned into -32601
		if (handler == null) {
			notifyMethodNotFound(notification.method());
			return Promise.of(JsonRpcOutput.none());
		}

		// FR-035: the same bound, and the same aggregate count — but nothing on the wire, because §4.1
		// forbids answering a notification and being overloaded does not change that. Deliberately NOT the
		// failure handler either: load shedding is a property of this server, not an application fault
		if (inFlight >= maxInFlight) {
			notifyRejected();
			return Promise.of(JsonRpcOutput.none());
		}

		notifyRequest(handler.descriptor);

		Object[] args;
		try {
			args = handler.paramsCodec.decode(notification.params());
		} catch (Exception e) {
			// FR-050: nothing goes on the wire, but nothing is swallowed either
			reportFailure(handler.descriptor, e);
			notifyError(handler.descriptor, JsonRpcErrors.INVALID_PARAMS.code(), 0);
			return Promise.of(JsonRpcOutput.none());
		}

		long startNanos = System.nanoTime();
		// a notification's invocation costs exactly what a request's does, so it counts the same (FR-033)
		inFlight++;
		return handler.invoke(args)
			.map(
				value -> {
					inFlight--;
					notifyResponse(handler.descriptor, durationMillis(startNanos));
					return JsonRpcOutput.none();
				},
				e -> {
					inFlight--;
					reportFailure(handler.descriptor, e);
					notifyError(handler.descriptor, codeOf(e), durationMillis(startNanos));
					return JsonRpcOutput.none();
				});
	}

	private void reportFailure(JsonRpcMethodDescriptor descriptor, Exception e) {
		try {
			failureHandler.accept(descriptor, e);
		} catch (Exception ignored) {
			// a failure handler that itself fails must not turn a notification into a failed dispatch;
			// totality (FR-038a) outranks the diagnostic
		}
	}

	// ---------------------------------------------------------------------------------------------------
	// Inspector callbacks — each guarded, each wrapped so a throwing inspector cannot break totality (FR-040).
	// ---------------------------------------------------------------------------------------------------

	private void notifyRequest(JsonRpcMethodDescriptor descriptor) {
		if (inspector == null) return;
		try {
			inspector.onRequest(descriptor);
		} catch (Throwable ignored) {
			// an inspector that itself fails must not break totality (FR-040)
		}
	}

	private void notifyResponse(JsonRpcMethodDescriptor descriptor, long durationMillis) {
		if (inspector == null) return;
		try {
			inspector.onResponse(descriptor, durationMillis);
		} catch (Throwable ignored) {
			// an inspector that itself fails must not break totality (FR-040)
		}
	}

	private void notifyError(JsonRpcMethodDescriptor descriptor, int errorCode, long durationMillis) {
		if (inspector == null) return;
		try {
			inspector.onError(descriptor, errorCode, durationMillis);
		} catch (Throwable ignored) {
			// an inspector that itself fails must not break totality (FR-040)
		}
	}

	private void notifyMethodNotFound(String requestedName) {
		if (inspector == null) return;
		try {
			inspector.onMethodNotFound(requestedName);
		} catch (Throwable ignored) {
			// an inspector that itself fails must not break totality (FR-040)
		}
	}

	private void notifyMalformed() {
		if (inspector == null) return;
		try {
			inspector.onMalformed();
		} catch (Throwable ignored) {
			// an inspector that itself fails must not break totality (FR-040)
		}
	}

	private void notifyRejected() {
		if (inspector == null) return;
		try {
			inspector.onRejected();
		} catch (Throwable ignored) {
			// an inspector that itself fails must not break totality (FR-040)
		}
	}

	/** Invocation duration in whole milliseconds, never negative — the synchronous case is {@code 0}. */
	private static long durationMillis(long startNanos) {
		return Math.max(0, (System.nanoTime() - startNanos) / 1_000_000);
	}

	/**
	 * Renders a successful invocation. Encoding happens <b>here</b> rather than inside the outgoing document,
	 * so that a codec refusing the value (a {@code null} result handed to a non-nullable codec, FR-046a) is
	 * an ordinary {@code -32603} instead of an exception escaping the encoder and breaking totality.
	 * <p>
	 * A dispatcher-owned entry ({@code rpc.discover}) answers from the payload it was built with — bytes
	 * computed once at {@code build()} and re-emitted verbatim by the encoder, with no per-call serialization
	 * and no decode/re-encode round trip (FR-032). The choice is keyed on the <b>handler</b>, an entry of the
	 * frozen table, and never on the shape of the value: a user method may legitimately return anything its
	 * codec accepts.
	 */
	private static JsonRpcOutput respond(Handler handler, JsonRpcId id, Object value) {
		JsonRpcPayload payload = handler.builtInResult;
		if (payload == null) {
			try {
				payload = encodeResult(handler.descriptor, value);
			} catch (Exception e) {
				return error(id, JsonRpcErrors.INTERNAL_ERROR);
			}
		}
		return JsonRpcOutput.single(JsonRpcResponse.ofResult(id, payload));
	}

	@SuppressWarnings("unchecked")
	private static JsonRpcPayload encodeResult(JsonRpcMethodDescriptor descriptor, Object value) {
		JsonCodec<?> codec = descriptor.resultCodec();
		// FR-030: void and Promise<Void> render as the JSON literal null and need no codec for Void at all
		if (codec == null) return JsonRpcPayload.raw(NULL_LITERAL, 0, NULL_LITERAL.length);
		byte[] rendered = JsonUtils.toJsonBytes((JsonCodec<Object>) codec, value);
		return JsonRpcPayload.raw(rendered, 0, rendered.length);
	}

	/**
	 * FR-047 / FR-048: a {@link JsonRpcException} travels verbatim, {@code data} included; anything else is
	 * exactly {@code -32603 Internal error} with <b>no</b> {@code data} and nothing derived from the
	 * exception — no class name, no message, no frame.
	 */
	private static JsonRpcError errorOf(Exception e) {
		return e instanceof JsonRpcException jsonRpc ? jsonRpc.getError() : JsonRpcErrors.INTERNAL_ERROR;
	}

	/**
	 * The code the {@link Inspector} callbacks report: a {@link JsonRpcException}'s own code, or {@code -32603}
	 * — the same choice {@link #errorOf} makes for the document itself.
	 */
	private static int codeOf(Exception e) {
		return e instanceof JsonRpcException jsonRpc ? jsonRpc.getError().code() : JsonRpcErrors.INTERNAL_ERROR.code();
	}

	private static JsonRpcOutput error(JsonRpcId id, JsonRpcError error) {
		return JsonRpcOutput.single(JsonRpcResponse.ofError(id, error));
	}

	/**
	 * The declaration the built-in {@code rpc.discover} descriptor is read from.
	 * <p>
	 * A {@link JsonRpcMethodDescriptor} carries a {@link Method}, and the built-in entry needs a real one —
	 * for {@link JsonRpcMethodDescriptor#toString()} and for any {@link Inspector} that renders a descriptor.
	 * It cannot come from a {@link JsonRpcServiceContract}: contract rule 10 refuses the whole reserved
	 * {@code rpc.} namespace (FR-001), and that refusal is the reason a user method can never collide with
	 * this entry. So the shape is declared here, where it is unreachable from any service interface, and the
	 * method is <b>never invoked</b> — {@link Handler#invoke} short-circuits on {@code builtInResult}.
	 */
	private interface Discovery {
		Promise<OpenRpcDocument> discover();
	}

	private static final Method DISCOVER_METHOD;

	static {
		try {
			DISCOVER_METHOD = Discovery.class.getMethod("discover");
		} catch (NoSuchMethodException e) {
			throw new AssertionError("Discovery.discover() is declared just above", e);
		}
	}

	/**
	 * One wire name's descriptor bound to the instance that answers it. Immutable, built at {@code build()}.
	 *
	 * @param descriptor     everything resolved about the method at contract-validation time
	 * @param implementation the instance the method is invoked on, or {@code null} for a dispatcher-owned
	 *                       entry, which invokes nothing
	 * @param paramsCodec    the per-method {@code params} decoder, built once here rather than per request
	 * @param builtInResult  non-{@code null} <b>iff</b> this is a dispatcher-owned entry: the response
	 *                       payload, already encoded at {@code build()} (FR-032). It is the one field that
	 *                       tells the two kinds of entry apart, so both {@link #invoke} and the enclosing
	 *                       class's {@code respond} branch on it rather than on the value or the wire name
	 */
	private record Handler(
		JsonRpcMethodDescriptor descriptor, @Nullable Object implementation, ParamsCodec paramsCodec,
		@Nullable JsonRpcPayload builtInResult
	) {
		// the params codec is built here, once, with everything else — not allocated per inbound request
		private Handler(JsonRpcMethodDescriptor descriptor, Object implementation) {
			this(descriptor, implementation, new ParamsCodec(descriptor), null);
		}

		/**
		 * The dispatcher's own entry: no implementation to invoke and nothing to encode per call. Its
		 * {@code ParamsCodec} is an ordinary one over a zero-arity descriptor, which is exactly why an absent,
		 * {@code null}, {@code []} or <code>{}</code> {@code params} is accepted and anything else is the
		 * ordinary {@code -32602} — no bespoke params rule was written for discovery (FR-005).
		 */
		static Handler builtIn(JsonRpcMethodDescriptor descriptor, JsonRpcPayload result) {
			return new Handler(descriptor, null, new ParamsCodec(descriptor), Objects.requireNonNull(result));
		}

		/**
		 * The single reflective hop the JDK proxy mechanism costs (verdict 00-B). Every failure — a refused
		 * argument, a throwing body, a {@code null} where a {@code Promise} was declared — leaves as a failed
		 * promise, so one mapping in the caller covers all of them.
		 */
		@SuppressWarnings("unchecked")
		Promise<Object> invoke(Object[] args) {
			// a dispatcher-owned entry has already been answered: the caller reads builtInResult, so there is
			// no instance to invoke and no value to produce
			if (builtInResult != null) return Promise.of(null);

			Method method = descriptor.method();
			Object returned;
			try {
				returned = method.invoke(implementation, args);
			} catch (InvocationTargetException e) {
				Throwable cause = e.getCause();
				// an Error is not a JSON-RPC failure either, but it is the only diagnostic a notification's
				// failure handler will ever see — wrap it, never drop it
				return Promise.ofException(cause instanceof Exception exception ?
					exception :
					new IllegalStateException("the service method failed", cause));
			} catch (Exception e) {
				return Promise.ofException(e);
			}

			if (method.getReturnType() == void.class) return Promise.of(null);
			// FR-046: a synchronous T is wrapped into a completed promise; only the proxy refuses one
			if (descriptor.isSynchronousResult()) return Promise.of(returned);
			// FR-046: a null where a Promise was declared is a failed invocation, never a propagated NPE
			if (returned == null) {
				return Promise.ofException(
					new IllegalStateException("the service method returned null instead of a Promise"));
			}
			return (Promise<Object>) returned;
		}
	}
}
