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

import io.activej.jsonrpc.JsonRpcDecoded;
import io.activej.jsonrpc.JsonRpcErrors;
import io.activej.jsonrpc.JsonRpcOutput;
import io.activej.jsonrpc.JsonRpcRequest;
import io.activej.jsonrpc.JsonRpcResponse;
import io.activej.promise.Promise;

/**
 * The server&rarr;client seam: what a {@link JsonRpcClient} does with an inbound element that is <b>not</b>
 * an answer to one of its own pending calls (FR-076).
 *
 * <h2>One method, on purpose</h2>
 * A transport carrying JSON-RPC is duplex, so a peer may send a request down the same channel a client sends
 * its own. This interface is the whole of what a client offers that direction; a later feature's
 * bidirectional wiring is a call to {@code withPeerHandler(...)} rather than a redesign.
 *
 * <h2>A dispatcher is the handler</h2>
 * {@code JsonRpcDispatcher} implements this interface directly — its {@code handle(JsonRpcDecoded)} is a
 * one-line forward to the broader {@code dispatch(JsonRpcInput)}, since {@link JsonRpcDecoded} is a narrower
 * {@code JsonRpcInput} and a client never hands a peer handler a whole batch: batches are split by the client
 * so that responses to its own calls are correlated element by element (FR-077). The wiring is therefore a
 * plain reference to the instance:
 * <pre>{@code
 * JsonRpcClient client = JsonRpcClient.builder(reactor, transport)
 *     .withPeerHandler(dispatcher)
 *     .build();
 * }</pre>
 *
 * <h2>Obligations</h2>
 * <ul>
 *     <li>Answer with {@link JsonRpcOutput#none()} when there is nothing to send. A notification must never
 *     be answered (&sect;4.1), and a returned {@code none()} is zero bytes rather than an empty document.</li>
 *     <li>Never complete the returned promise exceptionally where an error <i>document</i> would do; a client
 *     routes a failed handler to its failure handler and sends nothing, which is a diagnostic the peer never
 *     sees. {@code JsonRpcDispatcher} is total for exactly this reason.</li>
 *     <li>Run on the reactor thread — a client calls this from its transport's inbound path.</li>
 * </ul>
 */
@FunctionalInterface
public interface JsonRpcPeerHandler {
	/**
	 * Handles one inbound element.
	 *
	 * @param incoming a decoded element that is not an answer to a pending call: a request, a notification,
	 *                 or a {@code JsonRpcMalformed} the decoder produced
	 * @return what to send back, or {@link JsonRpcOutput#none()}
	 */
	Promise<JsonRpcOutput> handle(JsonRpcDecoded incoming);

	/**
	 * The default: {@code -32601 Method not found} for an inbound <b>request</b>, and nothing at all for
	 * anything else — a notification included, since &sect;4.1 forbids answering one.
	 * <p>
	 * It is the honest answer for a client that exposes no service: the peer asked for a method this endpoint
	 * does not have, because this endpoint has none.
	 */
	static JsonRpcPeerHandler methodNotFound() {
		return incoming -> Promise.of(incoming instanceof JsonRpcRequest request ?
			JsonRpcOutput.single(JsonRpcResponse.ofError(request.id(), JsonRpcErrors.METHOD_NOT_FOUND)) :
			JsonRpcOutput.none());
	}
}
