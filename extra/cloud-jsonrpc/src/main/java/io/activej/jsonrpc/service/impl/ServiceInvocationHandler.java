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

package io.activej.jsonrpc.service.impl;

import com.dslplatform.json.JsonReader;
import com.dslplatform.json.JsonWriter;
import io.activej.common.annotation.ExposedInternals;
import io.activej.common.exception.MalformedDataException;
import io.activej.json.JsonCodec;
import io.activej.json.JsonUtils;
import io.activej.jsonrpc.JsonRpcPayload;
import io.activej.jsonrpc.service.JsonRpcMethodDescriptor;
import io.activej.jsonrpc.service.JsonRpcParamDescriptor;
import io.activej.jsonrpc.service.JsonRpcParamStyle;
import io.activej.jsonrpc.service.JsonRpcServiceContract;
import io.activej.promise.Promise;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The single reflective hop behind {@code JsonRpcClient.proxy(...)}: what a {@link java.lang.reflect.Proxy}
 * built over an annotated interface does with a call (FR-060, FR-064, FR-071, FR-075).
 *
 * <h2>Three kinds of method, decided before anything is encoded</h2>
 * <ol>
 *     <li>An {@link Object} method — {@code equals}, {@code hashCode}, {@code toString} — is answered
 *     <b>locally</b>. Identity equality is the only equality a remote proxy can honestly claim.</li>
 *     <li>A method the contract does not know is either an <b>unannotated {@code default}</b> method, invoked
 *     locally through {@link InvocationHandler#invokeDefault}, or a programming error this proxy refuses. A
 *     {@code default} method with no annotation is the author's own helper, not a wire method (FR-023).</li>
 *     <li>Anything else is a wire method: arguments are rendered, one document is handed over, and a
 *     {@code Promise} comes back.</li>
 * </ol>
 * Only the third kind touches the reactor, which is why the thread guard sits at the head of that branch and
 * not of {@code invoke}: {@code toString()} on a proxy is not reactor state.
 *
 * <h2>Encoding happens here, and eagerly</h2>
 * The {@code params} member is rendered to bytes <b>before</b> the client is asked for an identifier, so a
 * value no codec can write fails the call with a {@link MalformedDataException} having produced no document
 * and no correlation entry (FR-074). A {@link JsonRpcPayload.Encoded} payload would defer the same failure
 * into the middle of the outgoing document, where it is no longer recoverable.
 *
 * <h2>Not part of the supported API surface</h2>
 * {@code io.activej.jsonrpc.service.impl} is the client's own machinery.
 */
@ExposedInternals
public final class ServiceInvocationHandler implements InvocationHandler {
	private static final Object[] NO_ARGS = new Object[0];

	/**
	 * What this handler needs from its client, and nothing more.
	 * <p>
	 * The seam exists so that {@code call} / {@code sendNotification} do not have to be public methods of
	 * {@code JsonRpcClient}: its published surface is {@code proxy}, {@code inFlightCount} and
	 * {@code closeEx}, and a proxy living in another package must not widen it.
	 */
	public interface Caller {
		/** The reactor-thread guard, run before a wire method touches anything (FR-079). */
		void checkReactorThread();

		/**
		 * Allocates an identifier, registers a correlation entry, and sends one request document.
		 *
		 * @return a promise completed when the answer is correlated, or failed if the document cannot be sent
		 */
		Promise<Object> call(JsonRpcMethodDescriptor descriptor, JsonRpcPayload params);

		/**
		 * Sends one notification document. Creates no correlation entry (FR-071).
		 *
		 * @return a promise completed when the document is <b>written</b> — a notification is never answered
		 */
		Promise<Void> sendNotification(JsonRpcMethodDescriptor descriptor, JsonRpcPayload params);

		/** Where a failure with no caller goes — a {@code void} notification's send failure (FR-071). */
		void reportFailure(Exception e);
	}

	private final JsonRpcServiceContract contract;
	private final Caller caller;
	private final Map<JsonRpcMethodDescriptor, ParamsEncoder> encoders = new IdentityHashMap<>();

	/**
	 * @param contract   a contract already validated by the client, including the client-only rules
	 * @param paramStyle the emission style for every method of this proxy (FR-089)
	 * @param caller     the client this proxy speaks through
	 */
	public ServiceInvocationHandler(JsonRpcServiceContract contract, JsonRpcParamStyle paramStyle, Caller caller) {
		this.contract = Objects.requireNonNull(contract, "contract");
		this.caller = Objects.requireNonNull(caller, "caller");
		Objects.requireNonNull(paramStyle, "paramStyle");
		// one encoder per method, built here rather than per call: FR-063 is about repeating neither the
		// reflection nor the codec resolution, and this is the last place either could sneak back in
		for (JsonRpcMethodDescriptor descriptor : contract.methods().values()) {
			encoders.put(descriptor, new ParamsEncoder(descriptor.params(), paramStyle == JsonRpcParamStyle.NAMED));
		}
	}

	@Override
	public @Nullable Object invoke(Object proxy, Method method, Object @Nullable [] args) throws Throwable {
		if (method.getDeclaringClass() == Object.class) return invokeObjectMethod(proxy, method, args);

		JsonRpcMethodDescriptor descriptor = contract.byJavaMethod(method);
		if (descriptor == null) {
			// FR-075: an unannotated default method is invoked locally, through the JDK's own default-method
			// invocation, and produces no document
			if (method.isDefault()) {
				return InvocationHandler.invokeDefault(proxy, method, args == null ? NO_ARGS : args);
			}
			throw new UnsupportedOperationException(
				method + " is not a JSON-RPC method of " + contract.serviceType().getName() +
				" and has no default implementation");
		}

		caller.checkReactorThread();

		JsonRpcPayload params;
		try {
			params = encodeParams(descriptor, args == null ? NO_ARGS : args);
		} catch (MalformedDataException e) {
			// FR-074: nothing was sent and nothing was recorded, so the failure has only the caller to go to
			return failed(descriptor, method, e);
		}

		if (descriptor.isNotification()) {
			Promise<Void> sent = caller.sendNotification(descriptor, params);
			if (method.getReturnType() == void.class) {
				// FR-071: a void notification returns immediately; its send failure has no caller waiting
				sent.whenException(caller::reportFailure);
				return null;
			}
			return sent;
		}

		return caller.call(descriptor, params);
	}

	private @Nullable Object failed(JsonRpcMethodDescriptor descriptor, Method method, Exception e) {
		if (descriptor.isNotification() && method.getReturnType() == void.class) {
			caller.reportFailure(e);
			return null;
		}
		return Promise.ofException(e);
	}

	private Object invokeObjectMethod(Object proxy, Method method, Object @Nullable [] args) {
		return switch (method.getName()) {
			// identity is the only equality a proxy over a remote service can honestly claim
			case "equals" -> args != null && args.length == 1 && proxy == args[0];
			case "hashCode" -> System.identityHashCode(proxy);
			case "toString" -> "JsonRpcProxy[" + contract.serviceType().getName() + ']';
			default -> throw new UnsupportedOperationException(method.toString());
		};
	}

	/**
	 * Renders the {@code params} member of one call.
	 *
	 * @return the rendered payload, or {@link JsonRpcPayload#absent()} for a zero-argument method — which
	 * omits the member entirely rather than emitting {@code []} or <code>{}</code> (FR-089a)
	 * @throws MalformedDataException if any argument's codec refuses it. The message names the wire method
	 *                                and nothing else; the cause carries the codec's own detail
	 */
	private JsonRpcPayload encodeParams(JsonRpcMethodDescriptor descriptor, Object[] args)
		throws MalformedDataException {
		if (descriptor.params().isEmpty()) return JsonRpcPayload.absent();
		byte[] rendered;
		try {
			rendered = JsonUtils.toJsonBytes(encoders.get(descriptor), args);
		} catch (RuntimeException e) {
			throw new MalformedDataException(
				"the arguments of '" + descriptor.wireName() + "' could not be encoded", e);
		}
		return JsonRpcPayload.raw(rendered, 0, rendered.length);
	}

	@Override
	public String toString() {
		return "ServiceInvocationHandler[" + contract.serviceType().getName() + ']';
	}

	/**
	 * The outgoing counterpart of {@code ParamsCodec}, which only decodes. Encode-only for the same reason in
	 * reverse: a client renders the style it was configured with, while a server reads whichever style arrived
	 * (FR-088).
	 */
	private static final class ParamsEncoder implements JsonCodec<Object[]> {
		private final List<JsonRpcParamDescriptor> params;
		private final boolean named;

		private ParamsEncoder(List<JsonRpcParamDescriptor> params, boolean named) {
			this.params = params;
			this.named = named;
		}

		@SuppressWarnings("unchecked")
		@Override
		public void write(JsonWriter writer, Object[] args) {
			writer.writeByte(named ? JsonWriter.OBJECT_START : JsonWriter.ARRAY_START);
			for (int i = 0; i < params.size(); i++) {
				if (i != 0) writer.writeByte(JsonWriter.COMMA);
				JsonRpcParamDescriptor param = params.get(i);
				if (named) {
					// never null: proxy(...) refuses an unnamed parameter in named style before this runs
					writer.writeString(param.name());
					writer.writeByte(JsonWriter.SEMI);
				}
				((JsonCodec<Object>) param.codec()).write(writer, args[i]);
			}
			writer.writeByte(named ? JsonWriter.OBJECT_END : JsonWriter.ARRAY_END);
		}

		@Override
		public Object[] read(JsonReader<?> reader) {
			throw new UnsupportedOperationException(
				"this encoder only writes; a server decodes params through ParamsCodec");
		}
	}
}
