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

import io.activej.http.HttpMethod;
import io.activej.http.HttpRequest;

import java.util.Set;

/**
 * What a server is willing to run from <b>0-RTT early data</b> (spec FR-064, FR-065), asked of every
 * request whose HEADERS arrived at {@code ZERO_RTT} and of no other.
 *
 * <h2>The question it answers</h2>
 * Not "is this request valid" but "<b>may this request happen twice</b>". Early data can be replayed
 * by an attacker who recorded it (RFC 8470 §3, RFC 9001 §9.2), so a request accepted here is a request
 * whose effect at the origin the deployment is willing to see repeated. A refusal is answered
 * {@code 425 (Too Early)} (RFC 8470 §5.2) <b>without the servlet being invoked</b>, and the client
 * re-issues it once the handshake completes — a round trip, not a failure.
 *
 * <h2>The default, and why it is not merely advisory</h2>
 * {@link #DEFAULT_POLICY} accepts only the methods RFC 9110 §9.2.1 defines as <b>safe</b> —
 * {@link #SAFE_METHODS}. It is the same set {@code Http3Client} will put in early data of its own
 * accord, so an ordinary application never provokes a rejection round trip.
 * <p>
 * It is also the <b>only</b> defence that survives a load balancer. The single-use replay register
 * ({@code QuicReplayGuard}) is process-local: a flight replayed to a <i>different</i> instance of this
 * server is not caught by it, and cannot be, without a strike register shared between nodes — which
 * this feature explicitly does not build. Widening this policy therefore widens the exposure of a
 * multi-instance deployment, not only of one process.
 *
 * <h2>A pure predicate</h2>
 * A verdict is a function of the request's <b>method and header fields</b>, and of nothing else. Two
 * consequences, both of them load-bearing rather than stylistic:
 * <ul>
 *   <li><b>The body is not observable.</b> Not by convention — structurally: this method returns a
 *       {@code boolean} synchronously, while an inbound HTTP/3 body is a CSP channel whose bytes
 *       arrive over QUIC packets that have not been read yet. There is no value an implementation
 *       could wait for, so no body byte can reach the answer. At the moment it is asked, the message
 *       carries a stream and no loaded buffer, so every synchronous body accessor on it throws.
 *       An implementation must not reach for {@link HttpRequest#takeBodyStream()} either: that would
 *       take the body from the exchange that owns it and break the request rather than inspect it.</li>
 *   <li><b>No side effects, and no blocking.</b> It runs on the reactor thread, inside the dispatch of
 *       a request the peer chose to send, before that request has been authorized by anything. It must
 *       not do I/O, must not mutate the request, and must answer the same for the same message —
 *       otherwise the security property it exists to state is not a property of anything.</li>
 * </ul>
 *
 * <p>Replaceable through {@code Http3Server.Builder.withEarlyDataPolicy(...)}. A request this accepts
 * reaches the servlet carrying the RFC 8470 {@code Early-Data: 1} field (spec FR-066), so application
 * code can still apply its own rule on top of a deployment-wide one.
 *
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9110#section-9.2.1">RFC 9110 §9.2.1 — Safe Methods</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc8470">RFC 8470 — Using Early Data in HTTP</a>
 * @see Http3EarlyData the client-side counterpart: what this client is willing to <i>send</i> early
 */
@FunctionalInterface
public interface Http3EarlyDataPolicy {
	/**
	 * The methods RFC 9110 §9.2.1 defines as safe — {@code GET}, {@code HEAD}, {@code OPTIONS},
	 * {@code TRACE} — and so the ones a replay cannot turn into a second side effect at the origin.
	 * <p>
	 * Held here rather than on {@code HttpMethod}: safety is an HTTP semantic this module reads, and
	 * {@code core-http} gains nothing from this feature (contracts/core-http-delta.md). Exposed so a
	 * consumer can widen the default rule without restating the set it widens.
	 */
	Set<HttpMethod> SAFE_METHODS = Set.of(HttpMethod.GET, HttpMethod.HEAD, HttpMethod.OPTIONS, HttpMethod.TRACE);

	/**
	 * Whether {@code request}, which arrived in early data, may be dispatched to the servlet before the
	 * handshake completes — which is to say whether the deployment accepts that a replay of the flight
	 * carrying it would run it a second time.
	 * <p>
	 * {@code false} is answered {@code 425 (Too Early)} with the servlet never invoked. Read only
	 * {@code request}'s method and header fields; see this interface's Javadoc for why the body is not
	 * among the things there is to read.
	 */
	boolean acceptsInEarlyData(HttpRequest request);

	/**
	 * RFC 9110 §9.2.1 safe methods and nothing else — the default of every {@code Http3Server}, and the
	 * reason 0-RTT can be turned on without the deployment having to write a policy first.
	 */
	Http3EarlyDataPolicy DEFAULT_POLICY = request -> SAFE_METHODS.contains(request.getMethod());
}
