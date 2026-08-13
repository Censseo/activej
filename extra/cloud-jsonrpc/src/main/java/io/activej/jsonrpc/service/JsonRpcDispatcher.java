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

import io.activej.common.builder.AbstractBuilder;
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
import io.activej.jsonrpc.JsonRpcMalformed;
import io.activej.jsonrpc.JsonRpcMessage;
import io.activej.jsonrpc.JsonRpcOutput;
import io.activej.jsonrpc.JsonRpcPayload;
import io.activej.jsonrpc.JsonRpcRequest;
import io.activej.jsonrpc.JsonRpcResponse;
import io.activej.jsonrpc.service.impl.ParamsCodec;
import io.activej.promise.Promise;
import io.activej.promise.Promises;
import io.activej.reactor.AbstractReactive;
import io.activej.reactor.Reactor;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiConsumer;

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
 * </table>
 */
public final class JsonRpcDispatcher extends AbstractReactive implements JsonRpcPeerHandler {
	/** The wire rendering of a {@code Promise<Void>} result: the JSON literal {@code null} (FR-030). */
	private static final byte[] NULL_LITERAL = "null".getBytes(US_ASCII);

	private Map<String, Handler> handlers = Map.of();
	private Set<String> wireNames = Set.of();
	private BiConsumer<JsonRpcMethodDescriptor, Exception> failureHandler;

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
		 * @throws JsonRpcContractException if any registered interface breaks the contract, or if two
		 *                                  interfaces claim the same wire name (FR-036, FR-037)
		 */
		@Override
		protected JsonRpcDispatcher doBuild() {
			Map<String, Handler> built = new LinkedHashMap<>();
			List<String> collisions = new ArrayList<>();

			for (Map.Entry<Class<?>, Object> entry : services.entrySet()) {
				Class<?> serviceType = entry.getKey();
				Object implementation = entry.getValue();
				// FR-036: a broken interface fails here, before the dispatcher exists and before any
				// transport could have been constructed
				JsonRpcServiceContract contract = JsonRpcServiceContract.of(serviceType, codecFactory);

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

			JsonRpcDispatcher.this.handlers = built;
			JsonRpcDispatcher.this.wireNames = Collections.unmodifiableSet(built.keySet());
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
			case JsonRpcMalformed malformed -> Promise.of(JsonRpcOutput.single(malformed.toResponse()));
		};
	}

	private Promise<JsonRpcOutput> dispatchRequest(JsonRpcRequest request) {
		Handler handler = handlers.get(request.method());
		// FR-041: a miss answers -32601 and invokes nothing at all
		if (handler == null) return Promise.of(error(request.id(), JsonRpcErrors.METHOD_NOT_FOUND));

		Object[] args;
		try {
			args = ParamsCodec.decode(handler.descriptor, request.params());
		} catch (Exception e) {
			// FR-045: the decoder's message embeds the offending input by construction, so it is dropped here
			// rather than mapped into the error object
			return Promise.of(error(request.id(), JsonRpcErrors.INVALID_PARAMS));
		}

		return handler.invoke(args)
			.map(
				value -> respond(handler.descriptor, request.id(), value),
				e -> error(request.id(), errorOf(e)));
	}

	private Promise<JsonRpcOutput> dispatchNotification(io.activej.jsonrpc.JsonRpcNotification notification) {
		Handler handler = handlers.get(notification.method());
		// §4.1 forbids answering a notification, so an unknown one is dropped rather than turned into -32601
		if (handler == null) return Promise.of(JsonRpcOutput.none());

		Object[] args;
		try {
			args = ParamsCodec.decode(handler.descriptor, notification.params());
		} catch (Exception e) {
			// FR-050: nothing goes on the wire, but nothing is swallowed either
			reportFailure(handler.descriptor, e);
			return Promise.of(JsonRpcOutput.none());
		}

		return handler.invoke(args)
			.map(
				value -> JsonRpcOutput.none(),
				e -> {
					reportFailure(handler.descriptor, e);
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

	/**
	 * Renders a successful invocation. Encoding happens <b>here</b> rather than inside the outgoing document,
	 * so that a codec refusing the value (a {@code null} result handed to a non-nullable codec, FR-046a) is
	 * an ordinary {@code -32603} instead of an exception escaping the encoder and breaking totality.
	 */
	private static JsonRpcOutput respond(JsonRpcMethodDescriptor descriptor, JsonRpcId id, Object value) {
		JsonRpcPayload payload;
		try {
			payload = encodeResult(descriptor, value);
		} catch (Exception e) {
			return error(id, JsonRpcErrors.INTERNAL_ERROR);
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

	private static JsonRpcOutput error(JsonRpcId id, JsonRpcError error) {
		return JsonRpcOutput.single(JsonRpcResponse.ofError(id, error));
	}

	/** One wire name's descriptor bound to the instance that answers it. Immutable, built at {@code build()}. */
	private record Handler(JsonRpcMethodDescriptor descriptor, Object implementation) {
		/**
		 * The single reflective hop the JDK proxy mechanism costs (verdict 00-B). Every failure — a refused
		 * argument, a throwing body, a {@code null} where a {@code Promise} was declared — leaves as a failed
		 * promise, so one mapping in the caller covers all of them.
		 */
		@SuppressWarnings("unchecked")
		Promise<Object> invoke(Object[] args) {
			Method method = descriptor.method();
			Object returned;
			try {
				returned = method.invoke(implementation, args);
			} catch (InvocationTargetException e) {
				Throwable cause = e.getCause();
				return Promise.ofException(cause instanceof Exception exception ?
					exception :
					new IllegalStateException("the service method failed"));
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
