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

package io.activej.http3;

import io.activej.http.HttpMessage;
import io.activej.http.HttpRequest;

/**
 * The per-request opt-in to 0-RTT early data (spec FR-068).
 *
 * <h2>What the default is, and why</h2>
 * {@link Http3Client} puts a request in early data only when its method is <b>safe</b> per
 * RFC 9110 §9.2.1 — {@code GET}, {@code HEAD}, {@code OPTIONS}, {@code TRACE} — mirroring the default
 * server-side policy of RFC 8470, so an ordinary application never provokes a rejection round trip.
 * Anything else waits for the handshake: it is <b>held back, not refused</b>, so nothing about it
 * changes except when it goes out.
 *
 * <h2>What opting in does and does not do</h2>
 * {@link #allow} says "this request may be replayed" — which is the guarantee 0-RTT actually needs, and
 * which only the consumer can give, since it is a statement about what the request does at the origin
 * rather than about its method. It reaches past the method rule and <b>nothing else</b>.
 * <p>
 * In particular, it does not reach past the <b>replayability</b> rule: a request carrying a body is
 * never sent in early data, opted in or not. That is a mechanical limit rather than a policy one. A
 * rejected early-data request is re-issued by re-sending the same {@link HttpRequest}, and a message's
 * body stream can only be taken once — so a retry of a body-bearing request would not replay it, it
 * would send the same request <i>without</i> its body. Refusing to expose such a request to a rejection
 * at all is the only honest answer.
 *
 * <h2>The risk being accepted</h2>
 * Early data can be <b>replayed by an attacker</b> (RFC 8470 §3, RFC 9001 §9.2): a recorded 0-RTT flight
 * can be delivered again, and a server's anti-replay defence is best-effort. Opting a request in accepts
 * that its effect at the origin may happen more than once.
 *
 * <p>A marker on one request, never a mode: it is carried on the existing {@link HttpMessage}
 * attachment mechanism — the same one {@link Http3Trailers} uses — so it travels with the request it was
 * put on and with nothing else, and needs no {@code core-http} API of its own.
 *
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9110#section-9.2.1">RFC 9110 §9.2.1 — Safe Methods</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc8470#section-3">RFC 8470 §3 — Early Data in HTTP</a>
 */
public final class Http3EarlyData {
	private Http3EarlyData() {
	}

	/** The {@link HttpMessage#attach(String, Object)} key the opt-in is stored under. */
	public static final String ATTACHMENT_KEY = "io.activej.http3.earlyData";

	/**
	 * Marks {@code request} as safe to send in early data whatever its method, and returns it — so it
	 * composes with the builder chain that produced it:
	 * <pre>{@code
	 * client.request(Http3EarlyData.allow(HttpRequest.post(url).build()));
	 * }</pre>
	 * Mutates the request it is given rather than copying it; {@link HttpRequest} has no copy
	 * constructor, and an attachment is exactly the kind of out-of-band context this mechanism exists
	 * for. A request with a body is still held back — see this class's Javadoc.
	 */
	public static HttpRequest allow(HttpRequest request) {
		request.attach(ATTACHMENT_KEY, Boolean.TRUE);
		return request;
	}

	/** Whether {@link #allow} was called on {@code request}. */
	public static boolean isAllowed(HttpRequest request) {
		return Boolean.TRUE.equals(request.getAttachment(ATTACHMENT_KEY));
	}
}
