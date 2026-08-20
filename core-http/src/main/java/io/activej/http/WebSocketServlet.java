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

package io.activej.http;

import io.activej.bytebuf.ByteBuf;
import io.activej.common.Checks;
import io.activej.csp.queue.ChannelZeroBuffer;
import io.activej.csp.supplier.ChannelSupplier;
import io.activej.promise.Promise;
import io.activej.promise.SettableCallback;
import io.activej.reactor.AbstractReactive;
import io.activej.reactor.Reactor;

import java.util.Arrays;

import static io.activej.common.Checks.checkState;
import static io.activej.http.AbstractHttpConnection.WEB_SOCKET_VERSION;
import static io.activej.http.HttpClient.UPGRADE_HEADER;
import static io.activej.http.HttpClient.WEBSOCKET_HEADER;
import static io.activej.http.HttpHeaders.*;
import static io.activej.http.HttpUtils.getWebSocketAnswer;
import static io.activej.http.WebSocketConstants.NOT_A_WEB_SOCKET_REQUEST;
import static io.activej.http.WebSocketConstants.REGULAR_CLOSE;
import static io.activej.reactor.Reactive.checkInReactorThread;

/**
 * A servlet for handling web socket upgrade requests.
 * An implementation may inspect incoming HTTP request, based on which an implementation MUST call a provided function
 * with a corresponding HTTP response.
 * <p>
 * If a response has a code {@code 101} it is considered successful and the resulted promise of a web socket will be
 * completed with a {@link IWebSocket}. A successful response must have no body or body stream.
 * <p>
 * If a response has code different from {@code 101}, it will be sent as is and the resulted promise will be completed
 * exceptionally.
 */
public abstract class WebSocketServlet extends AbstractReactive
	implements AsyncServlet {
	private static final boolean CHECKS = Checks.isEnabled(WebSocketServlet.class);

	protected WebSocketServlet(Reactor reactor) {
		super(reactor);
		checkState(IWebSocket.ENABLED, "Web sockets are disabled by application settings");
	}

	/**
	 * Decides whether the upgrade is accepted. A {@code 101} response accepts it and MUST carry neither
	 * a body nor a body stream; any other code refuses it and is sent as is. Refusing by failing — a
	 * failed promise or a throw — is equally supported: the request body stream is taken only once a
	 * legal {@code 101} has been produced, so no failure path can strand it.
	 */
	protected Promise<HttpResponse> onRequest(HttpRequest request) {
		return HttpResponse.ofCode(101).toPromise();
	}

	protected abstract void onWebSocket(IWebSocket webSocket);

	@Override
	public final Promise<HttpResponse> serve(HttpRequest request) {
		if (CHECKS) checkInReactorThread(this);
		return validateHeaders(request)
			.<String>thenCallback(cb -> processAnswer(request, cb))
			.then(answer -> onRequest(request)
				.map(response -> {
					if (response.getCode() != 101) {
						return response;
					}

					if (response.body != null || response.bodyStream != null) {
						response.recycleBody();
						throw new IllegalStateException("Illegal body or stream");
					}

					// The ownership of the request body stream is transferred here and nowhere earlier:
					// until this point every refusal and every failure leaves the stream with the
					// request, whose owner (HttpServerConnection) recycles it.
					ChannelSupplier<ByteBuf> rawStream = request.takeBodyStream();

					ChannelZeroBuffer<ByteBuf> buffer = new ChannelZeroBuffer<>();

					response.bodyStream = buffer.getSupplier();
					response.headers.add(UPGRADE, WEBSOCKET_HEADER);
					response.headers.add(CONNECTION, UPGRADE_HEADER);
					response.headers.add(SEC_WEBSOCKET_ACCEPT, HttpHeaderValue.of(answer));

					WebSocketFramesToBufs encoder = WebSocketFramesToBufs.create(false);
					WebSocketBufsToFrames decoder = WebSocketBufsToFrames.create(
						request.maxBodySize,
						encoder::sendPong,
						ByteBuf::recycle,
						true);

					bindWebSocketTransformers(rawStream, encoder, decoder);

					onWebSocket(new WebSocket(
						request,
						response,
						rawStream.transformWith(decoder),
						buffer.getConsumer().transformWith(encoder),
						decoder::onProtocolError,
						request.maxBodySize
					));

					return response;
				}));
	}

	private static void bindWebSocketTransformers(ChannelSupplier<ByteBuf> rawStream, WebSocketFramesToBufs encoder, WebSocketBufsToFrames decoder) {
		encoder.getCloseSentPromise()
			.then(decoder::getCloseReceivedPromise)
			.whenResult(rawStream::closeEx)
			.whenException(rawStream::closeEx);

		decoder.getProcessCompletion()
			.whenResult(() -> encoder.sendCloseFrame(REGULAR_CLOSE))
			.whenException(encoder::closeEx);
	}

	private static boolean isUpgradeHeaderMissing(HttpMessage message) {
		String headerValue = message.getHeader(HttpHeaders.CONNECTION);
		if (headerValue != null) {
			for (String val : headerValue.split(",")) {
				if ("upgrade".equalsIgnoreCase(val.trim())) {
					return false;
				}
			}
		}
		return true;
	}

	private static Promise<Void> validateHeaders(HttpRequest request) {
		if (isUpgradeHeaderMissing(request) ||
			!Arrays.equals(WEB_SOCKET_VERSION, request.getHeader(SEC_WEBSOCKET_VERSION, ByteBuf::getArray))
		) {
			return Promise.ofException(NOT_A_WEB_SOCKET_REQUEST);
		}
		return Promise.complete();
	}

	private static void processAnswer(HttpRequest request, SettableCallback<String> cb) {
		String header = request.getHeader(SEC_WEBSOCKET_KEY);
		if (header == null) {
			cb.setException(NOT_A_WEB_SOCKET_REQUEST);
			return;
		}
		cb.set(getWebSocketAnswer(header.trim()));
	}
}
