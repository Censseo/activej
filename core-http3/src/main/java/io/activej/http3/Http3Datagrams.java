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
import org.jetbrains.annotations.Nullable;

/**
 * The HTTP/3 datagram handle bound to one exchange (RFC 9297, spec FR-084), carried on the existing
 * {@link HttpMessage#attach(String, Object)} / {@link HttpMessage#getAttachment(String)} mechanism —
 * the same seam phase 1 used for {@link Http3Trailers}, and therefore <b>zero {@code core-http} API
 * change</b> (research D-8).
 * <p>
 * A servlet reaches the channel from the {@code HttpRequest} it is serving; a client caller reaches it
 * from the {@code HttpRequest} it issued and from the {@code HttpResponse} it received:
 * <pre>{@code
 * Http3DatagramChannel datagrams = Http3Datagrams.of(request);
 * if (datagrams != null && datagrams.isAvailable()) {
 *     datagrams.setReceiveHandler(payload -> { ... payload.recycle(); });
 *     datagrams.send(payload);   // takes ownership of payload on every path
 * }
 * }</pre>
 * {@code null} means this exchange has no datagram channel — either
 * {@link Http3Settings#datagramsEnabled()} is off, which is the default (FR-086), or the message is not
 * an HTTP/3 one at all. An {@link Http3DatagramChannel#isAvailable() unavailable} channel is a different
 * thing: it exists, and it can become available once the peer's SETTINGS land.
 * <p>
 * Like {@code Http3Trailers} this does not pre-empt {@code docs/http/spec.md}'s open question about
 * whether {@code core-http} gains a first-class extension API; if it does, this becomes a thin delegate.
 */
public final class Http3Datagrams {
	private Http3Datagrams() {
	}

	/** The {@link HttpMessage#attach(String, Object)} key the datagram channel is stored under. */
	public static final String ATTACHMENT_KEY = "io.activej.http3.datagrams";

	/**
	 * The datagram channel bound to {@code message}'s exchange, or {@code null} if it has none — which is
	 * every message with {@link Http3Settings#datagramsEnabled()} off, and every non-HTTP/3 message.
	 */
	public static @Nullable Http3DatagramChannel of(HttpMessage message) {
		return message.getAttachment(ATTACHMENT_KEY);
	}

	/**
	 * Package-private, which is the one divergence from the {@link Http3Trailers} precedent it otherwise
	 * copies exactly. A servlet legitimately sets trailers on a response it is building; an application
	 * attaching a datagram channel it did not receive from a connection would be asserting a capability
	 * that does not exist, since only an {@code Http3Connection} can bind one to a request stream.
	 */
	static void set(HttpMessage message, Http3DatagramChannel channel) {
		message.attach(ATTACHMENT_KEY, channel);
	}
}
