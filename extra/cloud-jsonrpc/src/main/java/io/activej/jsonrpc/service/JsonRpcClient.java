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

import io.activej.async.exception.AsyncCloseException;
import io.activej.async.process.AsyncCloseable;
import io.activej.common.builder.AbstractBuilder;
import io.activej.common.exception.MalformedDataException;
import io.activej.json.JsonCodec;
import io.activej.json.JsonCodecFactory;
import io.activej.jsonrpc.JsonRpcBatch;
import io.activej.jsonrpc.JsonRpcDecoded;
import io.activej.jsonrpc.JsonRpcDecoder;
import io.activej.jsonrpc.JsonRpcEncoder;
import io.activej.jsonrpc.JsonRpcErrors;
import io.activej.jsonrpc.JsonRpcException;
import io.activej.jsonrpc.JsonRpcId;
import io.activej.jsonrpc.JsonRpcInput;
import io.activej.jsonrpc.JsonRpcMalformed;
import io.activej.jsonrpc.JsonRpcMessage;
import io.activej.jsonrpc.JsonRpcOutput;
import io.activej.jsonrpc.JsonRpcPayload;
import io.activej.jsonrpc.JsonRpcRequest;
import io.activej.jsonrpc.JsonRpcResponse;
import io.activej.jsonrpc.service.impl.PendingCall;
import io.activej.jsonrpc.service.impl.ServiceInvocationHandler;
import io.activej.jsonrpc.transport.JsonRpcTransport;
import io.activej.promise.Promise;
import io.activej.promise.Promises;
import io.activej.promise.SettablePromise;
import io.activej.reactor.AbstractReactive;
import io.activej.reactor.Reactor;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

import static io.activej.reactor.Reactive.checkInReactorThread;

/**
 * The client half: a typed interface in, one JSON-RPC document out, and the answer correlated back by
 * {@code id} (FR-059…FR-080).
 *
 * <pre>{@code
 * JsonRpcClient client = JsonRpcClient.builder(reactor, transport).build();
 * UserApi api = client.proxy(UserApi.class);
 *
 * api.getUser(42).whenResult(user -> ...);   // {"jsonrpc":"2.0","id":1,"method":"user.get","params":[42]}
 * api.touch(42);                             // {"jsonrpc":"2.0","method":"user.touch","params":[42]}
 * }</pre>
 *
 * <h2>The correlation table, and its single removal path</h2>
 * A call registers one {@link PendingCall} in a reactor-confined map keyed by {@link JsonRpcId} — by the
 * whole identifier and not by a {@code long}, because a bidirectional peer may answer with a form this client
 * never chose, and {@code Str("1")} is not {@code Num(1)} (FR-066).
 * <p>
 * There is exactly <b>one</b> private {@code remove(id)} (FR-068). A successful answer, a remote error, a
 * transport send failure, a local {@code close()} and a peer close all funnel through it, and a local
 * encoding failure funnels through it vacuously — it fails before an identifier is allocated, so it never
 * creates the entry it would have to remove. A second removal site is precisely the divergence ADR-030's
 * third detail warns against.
 * <p>
 * Removal <b>precedes</b> decoding (FR-069): the entry is taken before the response payload is turned into a
 * value, so a payload that fails to decode still leaves the table empty and an orphan value is never
 * constructed. That is the stronger form of {@code cloud-rpc}'s orphan-result rule — there is nothing to
 * discard, because nothing was made.
 *
 * <h2>Inbound documents this client did not ask for</h2>
 * Anything that is not an answer to a pending call goes to the {@link JsonRpcPeerHandler} (FR-076), whose
 * default answers a request {@code -32601} and ignores a notification. A {@code JsonRpcDispatcher} implements
 * this interface directly — {@code withPeerHandler(dispatcher)} — which is the whole of the server&rarr;client
 * direction. An inbound <b>batch</b> is split element by element, its responses correlated individually and
 * the outputs of the rest assembled into one outbound batch (FR-077).
 * <p>
 * A response whose {@code id} is in no entry is <b>ignored silently</b>: no exception, no failure handler, no
 * entry created (FR-070). A duplicate answer is the same case by construction, since the first one already
 * emptied the slot.
 *
 * <h2>Closing</h2>
 * {@link #closeEx} completes every pending call through the removal path, empties the table and closes the
 * transport; it is idempotent. A close originating at the peer takes the same path with the transport's cause
 * — or {@link AsyncCloseException} when it supplied none (FR-078a). After either, a further call fails
 * immediately, hands no document to the transport and records nothing (FR-078b).
 */
public final class JsonRpcClient extends AbstractReactive implements AsyncCloseable {
	private final JsonRpcTransport transport;

	/** The correlation table. Reactor-confined, so a plain {@link HashMap} is the honest structure. */
	private final Map<JsonRpcId, PendingCall> pending = new HashMap<>();

	/** One proxy per interface, so repeat calls repeat neither reflection nor codec resolution (FR-063). */
	private final Map<Class<?>, Object> proxies = new HashMap<>();

	private final ServiceInvocationHandler.Caller caller = new ProxyCaller();

	private JsonCodecFactory codecFactory = JsonCodecFactory.defaultInstance();
	private JsonRpcPeerHandler peerHandler = JsonRpcPeerHandler.methodNotFound();
	private JsonRpcParamStyle paramStyle = JsonRpcParamStyle.POSITIONAL;
	private Consumer<Exception> failureHandler;

	/** The identifier counter: monotonic, starting at 1, reactor-confined (FR-065). */
	private long lastId;

	private boolean closed;
	private @Nullable Exception closeException;

	private JsonRpcClient(Reactor reactor, JsonRpcTransport transport) {
		super(reactor);
		this.transport = transport;
		this.failureHandler = e -> reactor.logFatalError(e, this);
	}

	/**
	 * Starts a client on {@code reactor} over {@code transport}. The transport's listener is registered at
	 * {@link Builder#build()} and not before, so a document arriving during construction cannot reach a
	 * half-built client.
	 *
	 * @throws NullPointerException if either argument is {@code null}
	 */
	public static Builder builder(Reactor reactor, JsonRpcTransport transport) {
		return new JsonRpcClient(
			Objects.requireNonNull(reactor, "reactor"),
			Objects.requireNonNull(transport, "transport")).new Builder();
	}

	/** Every option is optional; {@code build()} is what joins the client to its transport. */
	public final class Builder extends AbstractBuilder<Builder, JsonRpcClient> {
		private Builder() {}

		/** The factory every parameter and result codec is resolved through. Defaults to the shared instance. */
		public Builder withCodecFactory(JsonCodecFactory codecFactory) {
			checkNotBuilt(this);
			JsonRpcClient.this.codecFactory = Objects.requireNonNull(codecFactory, "codecFactory");
			return this;
		}

		/**
		 * What answers an inbound element that is not a response to a pending call (FR-076). Defaults to
		 * {@link JsonRpcPeerHandler#methodNotFound()} — {@code -32601} for a request, nothing for a
		 * notification.
		 */
		public Builder withPeerHandler(JsonRpcPeerHandler peerHandler) {
			checkNotBuilt(this);
			JsonRpcClient.this.peerHandler = Objects.requireNonNull(peerHandler, "peerHandler");
			return this;
		}

		/**
		 * Where a failure with no caller goes: a {@code void} notification's send failure, an undecodable
		 * inbound document, a peer handler that failed. Defaults to {@code Reactor.logFatalError(e, this)}.
		 */
		public Builder withFailureHandler(Consumer<Exception> failureHandler) {
			checkNotBuilt(this);
			JsonRpcClient.this.failureHandler = Objects.requireNonNull(failureHandler, "failureHandler");
			return this;
		}

		/**
		 * The {@code params} style every proxy of this client emits (FR-089). Defaults to
		 * {@link JsonRpcParamStyle#POSITIONAL}.
		 * <p>
		 * Per client rather than per method or per service: the style is a property of how this peer chooses
		 * to talk, not of the contract. {@link JsonRpcParamStyle#NAMED} makes
		 * {@link #proxy(Class) proxy(...)} refuse any method with an unannotated parameter (FR-090).
		 */
		public Builder withParamStyle(JsonRpcParamStyle paramStyle) {
			checkNotBuilt(this);
			JsonRpcClient.this.paramStyle = Objects.requireNonNull(paramStyle, "paramStyle");
			return this;
		}

		@Override
		protected JsonRpcClient doBuild() {
			transport.setListener(new JsonRpcTransport.Listener() {
				@Override
				public void onDocument(byte[] document) {
					JsonRpcClient.this.onDocument(document);
				}

				@Override
				public void onClosed(@Nullable Exception e) {
					JsonRpcClient.this.onTransportClosed(e);
				}
			});
			return JsonRpcClient.this;
		}
	}

	/**
	 * Builds — or returns the already built — proxy for one annotated interface.
	 * <p>
	 * The contract is validated in full at this point, and this client applies <b>one more</b> rule than a
	 * dispatcher does: a {@code @JsonRpcMethod} declaring a synchronous {@code T} is refused, naming the
	 * method, because answering it would mean blocking the reactor (FR-062). In
	 * {@link JsonRpcParamStyle#NAMED} style a method with an unannotated parameter is refused too, naming the
	 * method and the position (FR-090).
	 *
	 * @return the proxy, the <b>same instance</b> for every call with the same interface on this client
	 * (FR-063)
	 * @throws JsonRpcContractException if the interface breaks the shared contract or either client-only
	 *                                  rule; the exception carries <b>every</b> violation
	 */
	public <T> T proxy(Class<T> serviceType) {
		checkInReactorThread(this);
		Objects.requireNonNull(serviceType, "serviceType");

		Object existing = proxies.get(serviceType);
		if (existing != null) return serviceType.cast(existing);

		JsonRpcServiceContract contract = JsonRpcServiceContract.of(serviceType, codecFactory);
		validateClientRules(contract);

		Object proxy = Proxy.newProxyInstance(
			serviceType.getClassLoader(),
			new Class<?>[]{serviceType},
			new ServiceInvocationHandler(contract, paramStyle, caller));
		proxies.put(serviceType, proxy);
		return serviceType.cast(proxy);
	}

	/**
	 * The number of calls awaiting an answer (FR-059a) — the diagnostic that makes "the table is empty" an
	 * assertion rather than an inference, and the natural place for a later feature's in-flight bound to be
	 * observed.
	 */
	public int inFlightCount() {
		checkInReactorThread(this);
		return pending.size();
	}

	/**
	 * Completes every pending call with {@code e} through the single removal path, empties the table and
	 * closes the transport. Idempotent: a second call, with any cause, does nothing (WI-9).
	 */
	@Override
	public void closeEx(Exception e) {
		checkInReactorThread(this);
		Objects.requireNonNull(e, "e");
		if (closed) return;
		doClose(e);
		transport.closeEx(e);
	}

	@Override
	public String toString() {
		return "JsonRpcClient[" + pending.size() + " in flight" + (closed ? ", closed" : "") + ']';
	}

	// ---------------------------------------------------------------------------------------------------
	// The correlation table. Exactly one removal path (FR-068).
	// ---------------------------------------------------------------------------------------------------

	/**
	 * <b>The</b> removal path. Every exit from the table — success, remote error, transport send failure,
	 * local close, peer close, and any future expiry — is this method and nothing else (FR-068). The sixth
	 * trigger of FR-068, a local encoding failure, reaches it vacuously: it fails before an identifier is
	 * allocated, so there is no entry to remove.
	 *
	 * @return the entry that was removed, or {@code null} when the identifier was in no entry: an orphan
	 * answer, which is ignored silently (FR-070)
	 */
	private @Nullable PendingCall remove(JsonRpcId id) {
		return pending.remove(id);
	}

	private Promise<Object> call(JsonRpcMethodDescriptor descriptor, JsonRpcPayload params) {
		if (closed) return Promise.ofException(closedException());

		JsonRpcId id = new JsonRpcId.Num(++lastId);
		byte[] document;
		try {
			document = JsonRpcEncoder.encode(new JsonRpcRequest(id, descriptor.wireName(), params));
		} catch (RuntimeException e) {
			// the params member is already rendered bytes by now, so this is the envelope's own failure;
			// no entry exists yet and nothing was sent (FR-074)
			return Promise.ofException(new MalformedDataException(
				"the request for '" + descriptor.wireName() + "' could not be encoded", e));
		}

		SettablePromise<Object> promise = new SettablePromise<>();
		// registered BEFORE the send: a transport that answers inside send() — the in-memory one does — must
		// find the entry already there
		pending.put(id, new PendingCall(id, promise, descriptor.resultCodec()));

		transport.send(document)
			.whenException(e -> {
				PendingCall failed = remove(id);
				// null when the answer already arrived and the send failed afterwards: the call is settled
				if (failed != null) failed.promise.setException(e);
			});

		return promise;
	}

	private Promise<Void> sendNotification(JsonRpcMethodDescriptor descriptor, JsonRpcPayload params) {
		if (closed) return Promise.ofException(closedException());

		byte[] document;
		try {
			// the envelope record, not this package's @JsonRpcNotification annotation — the two share a
			// simple name on purpose, and this is one of the two files that must tell them apart
			document = JsonRpcEncoder.encode(
				new io.activej.jsonrpc.JsonRpcNotification(descriptor.wireName(), params));
		} catch (RuntimeException e) {
			return Promise.ofException(new MalformedDataException(
				"the notification '" + descriptor.wireName() + "' could not be encoded", e));
		}

		// FR-071: no id, no entry, and the promise means written rather than handled
		return transport.send(document);
	}

	// ---------------------------------------------------------------------------------------------------
	// Inbound.
	// ---------------------------------------------------------------------------------------------------

	private void onDocument(byte[] document) {
		// the transport SPI is deliberately not Reactive (FR-087), so this guard is the only thing between
		// an off-thread delivery and silent corruption of the correlation table
		checkInReactorThread(this);
		if (closed) return;

		JsonRpcInput input;
		try {
			input = JsonRpcDecoder.decode(document);
		} catch (RuntimeException e) {
			// JsonRpcDecoder.decode is documented total; this is the belt to that braces, and an inbound
			// document nobody asked for has no caller to fail
			reportFailure(e);
			return;
		}

		switch (input) {
			case JsonRpcDecoded decoded -> handleElement(decoded).whenResult(this::send);
			// FR-077: element by element, so that answers to this client's own calls are correlated
			// individually rather than handed wholesale to a peer handler that knows nothing about them
			case JsonRpcBatch batch -> handleBatch(batch);
		}
	}

	private void handleBatch(JsonRpcBatch batch) {
		List<Promise<JsonRpcOutput>> outputs = new ArrayList<>(batch.size());
		for (JsonRpcDecoded element : batch.elements()) {
			outputs.add(handleElement(element));
		}
		Promises.toList(outputs).whenResult(list -> {
			List<JsonRpcMessage> messages = new ArrayList<>(list.size());
			for (JsonRpcOutput output : list) {
				switch (output) {
					case JsonRpcOutput.Single single -> messages.add(single.message());
					// a peer handler answering one element with a batch is not expected, but flattening it is
					// the only reading that keeps one inbound batch to one outbound document
					case JsonRpcOutput.Batch nested -> messages.addAll(nested.messages());
					case JsonRpcOutput.None ignored -> {}
				}
			}
			// FR-054: a batch that produced nothing is zero bytes, never the "[]" that is itself a -32600
			if (!messages.isEmpty()) send(JsonRpcOutput.batch(messages));
		});
	}

	/** Never completes exceptionally: an inbound element has no caller, so every failure is reported instead. */
	private Promise<JsonRpcOutput> handleElement(JsonRpcDecoded element) {
		if (element instanceof JsonRpcResponse response) {
			complete(response);
			return Promise.of(JsonRpcOutput.none());
		}
		// a peer's Response object that violates §5 reaches us as a -32004 JsonRpcMalformed carrying whatever
		// id was recovered: it is an answer, not a request, so it correlates like one (FR-073)
		if (element instanceof JsonRpcMalformed malformed &&
			malformed.error().code() == JsonRpcErrors.INVALID_RESPONSE.code()) {
			fail(malformed);
			return Promise.of(JsonRpcOutput.none());
		}
		try {
			return peerHandler.handle(element)
				.map(output -> output, e -> {
					reportFailure(e);
					return JsonRpcOutput.none();
				});
		} catch (RuntimeException e) {
			reportFailure(e);
			return Promise.of(JsonRpcOutput.none());
		}
	}

	/**
	 * The success and remote-error paths. The entry is taken <b>first</b>, and only then is a value produced
	 * from the payload (FR-069) — which is why an undecodable result leaves the table empty rather than
	 * leaking the entry it could not satisfy.
	 */
	private void complete(JsonRpcResponse response) {
		PendingCall call = remove(response.id());
		if (call == null) return;                                  // FR-070: an orphan answer, silently

		if (response.isError()) {
			// FR-072: code, message and data travel verbatim, reserved codes included
			//noinspection DataFlowIssue - isError() is exactly error != null
			call.promise.setException(new JsonRpcException(response.error()));
			return;
		}

		Object value;
		try {
			value = decodeResult(call, response.result());
		} catch (MalformedDataException e) {
			call.promise.setException(e);
			return;
		}
		call.promise.set(value);
	}

	private void fail(JsonRpcMalformed malformed) {
		PendingCall call = remove(malformed.id());
		if (call == null) return;
		call.promise.setException(new JsonRpcException(malformed.error()));
	}

	@SuppressWarnings("unchecked")
	private static @Nullable Object decodeResult(PendingCall call, JsonRpcPayload result)
		throws MalformedDataException {
		JsonCodec<?> codec = call.resultCodec;
		// FR-030: a Promise<Void> result is the JSON literal null and involves no codec for Void at all
		if (codec == null) return null;
		return result.decode((JsonCodec<Object>) codec);
	}

	private void send(JsonRpcOutput output) {
		if (output instanceof JsonRpcOutput.None) return;
		if (closed) return;
		byte[] document;
		try {
			document = JsonRpcEncoder.encode(output);
		} catch (RuntimeException e) {
			// a peer handler's own JsonRpcException#data codec (FR-047) can throw while being written; this
			// path has no caller to fail (handleElement's contract is "never completes exceptionally"), so
			// the failure is reported instead of left to escape into whatever Promise chain called send()
			reportFailure(e);
			return;
		}
		// obligation 3: "no response" is the absence of a call, not an empty document
		if (document.length == 0) return;
		transport.send(document).whenException(this::reportFailure);
	}

	// ---------------------------------------------------------------------------------------------------
	// Closing (FR-078, FR-078a, FR-078b).
	// ---------------------------------------------------------------------------------------------------

	private void onTransportClosed(@Nullable Exception e) {
		// same threading contract as onDocument: the pending table is drained here and must stay reactor-confined
		checkInReactorThread(this);
		if (closed) return;
		// FR-078a: the same path as a local close, with the transport's cause when it supplied one
		doClose(e != null ? e : new AsyncCloseException("the transport closed"));
	}

	private void doClose(Exception e) {
		// set first: completing a promise below may run a continuation that issues another call, and that
		// call must fail immediately rather than register an entry nothing will ever remove (FR-078b)
		closed = true;
		closeException = e;
		// a snapshot, because a continuation may touch the table while it is being drained
		for (JsonRpcId id : List.copyOf(pending.keySet())) {
			PendingCall call = remove(id);
			if (call != null) call.promise.setException(e);
		}
	}

	private Exception closedException() {
		Exception e = closeException;
		return e != null ? e : new AsyncCloseException("the client is closed");
	}

	private void reportFailure(Exception e) {
		try {
			failureHandler.accept(e);
		} catch (RuntimeException ignored) {
			// a failure handler that itself fails must not become a second failure with nowhere to go
		}
	}

	/** What {@link ServiceInvocationHandler} is given, so that none of it has to be public API. */
	private final class ProxyCaller implements ServiceInvocationHandler.Caller {
		@Override
		public void checkReactorThread() {
			checkInReactorThread(JsonRpcClient.this);
		}

		@Override
		public Promise<Object> call(JsonRpcMethodDescriptor descriptor, JsonRpcPayload params) {
			return JsonRpcClient.this.call(descriptor, params);
		}

		@Override
		public Promise<Void> sendNotification(JsonRpcMethodDescriptor descriptor, JsonRpcPayload params) {
			return JsonRpcClient.this.sendNotification(descriptor, params);
		}

		@Override
		public void reportFailure(Exception e) {
			JsonRpcClient.this.reportFailure(e);
		}
	}

	// ---------------------------------------------------------------------------------------------------
	// The two client-only contract rules (FR-062, FR-090).
	// ---------------------------------------------------------------------------------------------------

	private void validateClientRules(JsonRpcServiceContract contract) {
		List<String> violations = new ArrayList<>();
		for (JsonRpcMethodDescriptor descriptor : contract.methods().values()) {
			// FR-056 / FR-062: the dispatcher accepts a synchronous T and wraps it; a proxy cannot answer one
			// without blocking the reactor, and blocking the reactor is forbidden
			if (descriptor.isSynchronousResult()) {
				violations.add(where(descriptor) + " declares a synchronous result; a client proxy cannot " +
							   "answer it without blocking the reactor — declare Promise<" +
							   descriptor.method().getReturnType().getSimpleName() + "> instead (FR-062)");
			}
			if (paramStyle == JsonRpcParamStyle.NAMED) {
				for (JsonRpcParamDescriptor param : descriptor.params()) {
					if (param.name() != null) continue;
					violations.add(where(descriptor) + " parameter " + param.index() + " carries no " +
								   "@JsonRpcParam name, which this client's NAMED parameter style requires " +
								   "(FR-090)");
				}
			}
		}
		if (!violations.isEmpty()) throw new JsonRpcContractException(contract.serviceType(), violations);
	}

	/** {@code declaring.Type.method(paramTypes)} — the same rendering the shared contract's violations use. */
	private static String where(JsonRpcMethodDescriptor descriptor) {
		StringBuilder sb = new StringBuilder(descriptor.method().getDeclaringClass().getName())
			.append('.').append(descriptor.method().getName()).append('(');
		Class<?>[] parameterTypes = descriptor.method().getParameterTypes();
		for (int i = 0; i < parameterTypes.length; i++) {
			if (i != 0) sb.append(", ");
			sb.append(parameterTypes[i].getSimpleName());
		}
		return sb.append(')').toString();
	}
}
