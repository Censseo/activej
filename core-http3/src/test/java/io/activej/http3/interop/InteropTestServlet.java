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

package io.activej.http3.interop;

import io.activej.bytebuf.ByteBuf;
import io.activej.http.AsyncServlet;
import io.activej.http.HttpHeader;
import io.activej.http.HttpHeaderValue;
import io.activej.http.HttpMethod;
import io.activej.http.HttpRequest;
import io.activej.http.HttpResponse;
import io.activej.http.RoutingServlet;
import io.activej.reactor.Reactor;

import java.util.Arrays;
import java.util.Map;

/**
 * The servlet the interop matrix drives (T011): {@code GET /} answers a fixed body,
 * {@code POST /echo} returns the request body verbatim, and {@code GET /headers} echoes back every
 * request field whose name starts with {@value #CUSTOM_FIELD_PREFIX} as a response field with the
 * same name and value.
 * <p>
 * Public {@code core-http} API only (FR-034): the echo is the {@code loadBody()} + copy shape the
 * manual {@link Http3InteropServer} harness already uses. {@code loadBody()}'s buffer belongs to
 * the request and is released with it, so the response carries an independent {@code byte[]} copy
 * of the body rather than a zero-copy view over the request's pooled buffer array. A view's
 * correctness would rest on an unstated main-source release-ordering property: the loaded body is
 * drained into {@code ChannelConsumers.recycling()} when {@code Http3RequestStream.sendResponse}
 * releases the inbound message, and under {@code ByteBufPool.clearOnRecycle=true} a recycled
 * pooled buffer is zeroed — a view could stream zeros if the response outlived that release. The
 * copy makes the echo correct regardless of release timing. Body bytes and field <b>values</b> are
 * never asserted by the suite, only lengths and digests (FR-013); this servlet is deliberately
 * value-agnostic.
 * <p>
 * Build with the reactor of the server that will serve it ({@code RoutingServlet} is
 * {@code AbstractReactive} and guards its reactor thread) — the {@link Http3ServerReactorFixture}
 * applies this factory on its own reactor thread.
 */
public final class InteropTestServlet {
	/** The fixed body {@code GET /} answers. */
	public static final String FIXED_BODY = "Hello from ActiveJ over HTTP/3\n";
	/** Request fields with this prefix are echoed back by {@code /headers}. */
	public static final String CUSTOM_FIELD_PREFIX = "x-interop-";

	private InteropTestServlet() {}

	public static AsyncServlet create(Reactor reactor) {
		return RoutingServlet.builder(reactor)
			.with(HttpMethod.GET, "/", request -> HttpResponse.ok200()
				.withPlainText(FIXED_BODY)
				.toPromise())
			.with(HttpMethod.POST, "/echo", request -> request.loadBody()
				.map(body -> {
					// loadBody's buffer belongs to the request and is released with it, so the
					// response gets an independent byte[] copy — not a view over the request's
					// pooled array, whose release timing is the main source's (class Javadoc).
					byte[] bodyBytes = Arrays.copyOfRange(body.getArray(), body.head(), body.tail());
					return HttpResponse.ok200().withBody(ByteBuf.wrapForReading(bodyBytes)).build();
				}))
			.with(HttpMethod.GET, "/headers", request -> {
				HttpResponse.Builder response = HttpResponse.ok200();
				for (Map.Entry<HttpHeader, HttpHeaderValue> field : request.getHeaders()) {
					String name = field.getKey().toString();
					if (name.startsWith(CUSTOM_FIELD_PREFIX)) {
						response.withHeader(field.getKey(), field.getValue().toString());
					}
				}
				return response.withPlainText("headers echoed\n").toPromise();
			})
			.build();
	}
}
