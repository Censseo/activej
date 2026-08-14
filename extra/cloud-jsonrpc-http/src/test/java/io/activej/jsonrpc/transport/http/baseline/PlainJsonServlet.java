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

package io.activej.jsonrpc.transport.http.baseline;

import io.activej.common.MemSize;
import io.activej.common.builder.AbstractBuilder;
import io.activej.http.AsyncServlet;
import io.activej.http.HttpMethod;
import io.activej.http.HttpRequest;
import io.activej.http.HttpResponse;
import io.activej.json.JsonCodec;
import io.activej.json.JsonCodecFactory;
import io.activej.json.JsonUtils;
import io.activej.jsonrpc.JsonRpcDecoder;
import io.activej.jsonrpc.JsonRpcInput;
import io.activej.jsonrpc.JsonRpcLimits;
import io.activej.jsonrpc.JsonRpcRequest;
import io.activej.jsonrpc.service.JsonRpcMethodDescriptor;
import io.activej.jsonrpc.service.JsonRpcServiceContract;
import io.activej.jsonrpc.service.impl.ParamsCodec;
import io.activej.promise.Promise;
import io.activej.reactor.AbstractReactive;
import io.activej.reactor.Reactor;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Objects;

import static io.activej.common.Checks.checkArgument;
import static io.activej.http.HttpHeaders.ALLOW;
import static io.activej.http.HttpHeaders.CONTENT_LENGTH;
import static io.activej.http.HttpHeaders.CONTENT_TYPE;
import static io.activej.jsonrpc.transport.http.JsonRpcHttpMediaTypes.isAccepted;
import static io.activej.reactor.Reactive.checkInReactorThread;

/**
 * The US5 denominator (T055 — FR-070): an {@link AsyncServlet} deliberately written <b>without</b>
 * JSON-RPC, existing only so that "protocol overhead" has a number. It parses the same JSON body
 * with the <b>same codecs</b> as {@code JsonRpcServlet}'s dispatch path and returns the <b>same
 * payload</b>, with no JSON-RPC envelope and no dispatch — the payload is the bare {@code result}
 * value, and {@link PlainJsonServletTest} pins it to the JSON-RPC path's {@code result} member so
 * the denominator cannot drift from the numerator (FR-074a).
 * <p>
 * <b>What is shared, exactly.</b> The servlet is an endpoint for <b>one</b> wire method of one
 * annotated service. Per request it performs precisely the steps the dispatch path performs,
 * minus the envelope and the method table:
 * <ol>
 *     <li>the same HTTP gates as {@code JsonRpcServlet} — {@code 405}/{@code 415}/{@code 413} in
 *     the same contractual order (these are HTTP-transport semantics, common to any JSON-RPC-over-
 *     HTTP implementation, so they are deliberately <i>not</i> part of the measured difference);</li>
 *     <li>the same body handling: {@code loadBody(maxBodySize)} → {@code takeBody()} →
 *     {@code asArray()} (the take-first order of FR-026);</li>
 *     <li>the <b>same decoder</b> the dispatch path uses — {@link JsonRpcDecoder#decode(byte[])} —
 *     to parse the request document;</li>
 *     <li>the <b>same params codec</b> — the descriptor's {@link ParamsCodec}, exactly what
 *     {@code JsonRpcDispatcher}'s handler table builds — to decode {@code params};</li>
 *     <li>the <b>same service invocation</b> — the single reflective hop of the dispatch path's
 *     {@code Handler.invoke} — so the payload work is identical;</li>
 *     <li>the <b>same result codec</b> (the descriptor's {@code resultCodec()}) rendered through
 *     the same {@code JsonUtils} machinery the envelope encoder itself uses, to produce the payload
 *     bytes.</li>
 * </ol>
 * What is <b>not</b> done: no {@code JsonRpcOutput} is constructed, no response envelope is
 * rendered, no method table is consulted, no batch/notification/malformed handling exists. A
 * document that is not exactly one {@code test.add}-shaped request is refused with a plain HTTP
 * {@code 400}; a service failure is a plain {@code 500}. Nothing on this path ever emits a
 * JSON-RPC envelope (FR-070).
 * <p>
 * <b>Scope of the resulting figure.</b> Because the request document is parsed with the same
 * envelope decoder (step 3), the request-side envelope decode is common to both paths and the
 * measured overhead is the <b>marginal</b> cost of the dispatch table and the response-envelope
 * construction/encoding — a conservative lower bound on the full protocol cost, stated as such in
 * the harness output.
 * <p>
 * <b>Retention rule.</b> This is a retained correctness fixture (FR-074a), not a benchmark: it
 * lives in {@code src/test}, is covered by {@link PlainJsonServletTest}, and the timing harness
 * ({@link ProtocolOverheadHarness}) is invoked explicitly rather than collected by Surefire.
 */
public final class PlainJsonServlet extends AbstractReactive implements AsyncServlet {
	private final JsonRpcMethodDescriptor descriptor;
	private final Object implementation;
	private final ParamsCodec paramsCodec;
	private final JsonCodec<?> resultCodec;
	private MemSize maxBodySize = JsonRpcLimits.MAX_BODY_SIZE;

	private PlainJsonServlet(Reactor reactor, JsonRpcMethodDescriptor descriptor, Object implementation) {
		super(reactor);
		this.descriptor = descriptor;
		this.implementation = implementation;
		this.paramsCodec = new ParamsCodec(descriptor);
		this.resultCodec = descriptor.resultCodec();
	}

	/**
	 * The no-configuration shortcut: the reference endpoint for one wire method of
	 * {@code serviceType} over {@code implementation}, with all defaults.
	 *
	 * @param serviceType    the same annotated interface the comparison dispatcher is built over
	 * @param implementation the same implementation instance type — resolved through the same
	 *                       {@link JsonCodecFactory#defaultInstance()} the fixture dispatcher uses,
	 *                       so every codec is the one the dispatch path resolves
	 * @param wireName       the one wire method this endpoint answers, e.g. {@code "test.add"}
	 * @throws IllegalArgumentException if {@code wireName} is not a non-notification method of the
	 *                                  contract
	 */
	public static <T> PlainJsonServlet create(
		Reactor reactor, Class<T> serviceType, T implementation, String wireName
	) {
		Objects.requireNonNull(reactor, "reactor");
		Objects.requireNonNull(serviceType, "serviceType");
		Objects.requireNonNull(implementation, "implementation");
		Objects.requireNonNull(wireName, "wireName");
		// the same one-pass contract resolution the dispatcher's build() performs (feature 012)
		JsonRpcServiceContract contract = JsonRpcServiceContract.of(serviceType, JsonCodecFactory.defaultInstance());
		JsonRpcMethodDescriptor descriptor = contract.methods().get(wireName);
		checkArgument(descriptor != null,
			"no method with wire name '%s' in %s", wireName, serviceType.getName());
		checkArgument(!descriptor.isNotification(),
			"'%s' is a notification — the reference endpoint answers requests only", wireName);
		return builder(reactor, descriptor, implementation).build();
	}

	/** The builder: construction-time entry point, not guarded (matching {@code JsonRpcServlet.builder}). */
	public static Builder builder(
		Reactor reactor, JsonRpcMethodDescriptor descriptor, Object implementation
	) {
		return new PlainJsonServlet(
			Objects.requireNonNull(reactor, "reactor"),
			Objects.requireNonNull(descriptor, "descriptor"),
			Objects.requireNonNull(implementation, "implementation"))
			.new Builder();
	}

	public final class Builder extends AbstractBuilder<Builder, PlainJsonServlet> {
		private Builder() {}

		/**
		 * The request-body bound, mirroring {@code JsonRpcServlet}: defaults to
		 * {@link JsonRpcLimits#MAX_BODY_SIZE}; a non-positive value is refused at {@link #build()}.
		 * Both servlets measure against the same bound, so the ratio is unaffected by it.
		 */
		public Builder withMaxBodySize(MemSize maxBodySize) {
			checkNotBuilt(this);
			PlainJsonServlet.this.maxBodySize = Objects.requireNonNull(maxBodySize, "maxBodySize");
			return this;
		}

		@Override
		protected PlainJsonServlet doBuild() {
			checkArgument(maxBodySize.toInt() > 0, "maxBodySize must be positive");
			return PlainJsonServlet.this;
		}
	}

	@Override
	public Promise<HttpResponse> serve(HttpRequest request) {
		checkInReactorThread(this);
		// ---- the shared HTTP layer, in the same contractual order as JsonRpcServlet (FR-015/016/022)
		// These gates are HTTP-transport semantics, not JSON-RPC envelope or dispatch; keeping them
		// on the reference path isolates the measured difference to the envelope and dispatch.
		if (request.getMethod() != HttpMethod.POST) {
			return HttpResponse.ofCode(405)
				.withHeader(ALLOW, "POST")
				.toPromise();
		}
		if (!isAccepted(request.getHeader(CONTENT_TYPE))) {
			return HttpResponse.ofCode(415).toPromise();
		}
		String contentLengthHeader = request.getHeader(CONTENT_LENGTH);
		if (contentLengthHeader != null) {
			long declaredContentLength;
			try {
				declaredContentLength = Long.parseLong(contentLengthHeader.trim());
			} catch (NumberFormatException e) {
				declaredContentLength = -1;
			}
			if (declaredContentLength > maxBodySize.toInt()) {
				return HttpResponse.ofCode(413).toPromise();
			}
		}
		// ---- body: same take-first conversion as the dispatch path (FR-026) ----------------------
		return request.loadBody(maxBodySize)
			.then($ -> {
				byte[] body = request.takeBody().asArray();
				// the SAME decoder the dispatch path uses (T055): parsing the request document
				JsonRpcInput input = JsonRpcDecoder.decode(body);
				// no dispatch: this endpoint is exactly one wire method, and nothing else
				if (!(input instanceof JsonRpcRequest jsonRpcRequest)
					|| !jsonRpcRequest.method().equals(descriptor.wireName())) {
					return HttpResponse.ofCode(400).toPromise();
				}
				Object[] args;
				try {
					args = paramsCodec.decode(jsonRpcRequest.params());
				} catch (Exception e) {
					// a hand-written endpoint refuses a body its codec cannot read
					return HttpResponse.ofCode(400).toPromise();
				}
				return invoke(args).then(
					PlainJsonServlet.this::renderPayload,
					e -> HttpResponse.ofCode(500).toPromise());
			});
	}

	/**
	 * The same single reflective service call the dispatch path's {@code Handler.invoke} performs —
	 * the payload work must be identical on both paths, so the ratio measures the envelope and
	 * dispatch alone, never the service itself.
	 */
	@SuppressWarnings("unchecked")
	private Promise<Object> invoke(Object[] args) {
		Method method = descriptor.method();
		Object returned;
		try {
			returned = method.invoke(implementation, args);
		} catch (InvocationTargetException e) {
			Throwable cause = e.getCause();
			return Promise.ofException(cause instanceof Exception exception ?
				exception :
				new IllegalStateException("the service method failed", cause));
		} catch (Exception e) {
			return Promise.ofException(e);
		}
		if (method.getReturnType() == void.class) return Promise.of(null);
		if (descriptor.isSynchronousResult()) return Promise.of(returned);
		if (returned == null) {
			return Promise.ofException(
				new IllegalStateException("the service method returned null instead of a Promise"));
		}
		return (Promise<Object>) returned;
	}

	/**
	 * Renders the payload — the bare {@code result} value, no envelope — with the <b>same result
	 * codec</b> the dispatch path resolves, through the same {@code JsonUtils} machinery
	 * {@code JsonRpcEncoder} itself uses. A codec refusing the value is a plain {@code 500}.
	 */
	@SuppressWarnings("unchecked")
	private Promise<HttpResponse> renderPayload(Object value) {
		try {
			byte[] payload = JsonUtils.toJsonBytes((JsonCodec<Object>) resultCodec, value);
			return HttpResponse.ok200()
				.withHeader(CONTENT_TYPE, "application/json")
				.withBody(payload)
				.toPromise();
		} catch (RuntimeException e) {
			return HttpResponse.ofCode(500).toPromise();
		}
	}
}
