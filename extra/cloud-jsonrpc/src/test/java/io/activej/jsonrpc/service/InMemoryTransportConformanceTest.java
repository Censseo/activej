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

import io.activej.jsonrpc.service.fixtures.InMemoryTransport;
import io.activej.jsonrpc.transport.JsonRpcTransport;

/**
 * The harness's first subject (FR-095): {@link InMemoryTransport} joined to a dispatcher by one method
 * reference, inheriting the whole conformance suite.
 *
 * <h2>Why this exists inside this feature</h2>
 * The parameterisation is the deliverable, and an abstraction with no second implementation is a guess. This
 * class is the evidence that {@link AbstractTransportConformanceTest} really is parameterised over a transport
 * before feature 04 builds the first real one against it — and the whole of what a transport author has to
 * write is below: two methods, one of them optional.
 *
 * <h2>Nothing is skipped</h2>
 * {@link #skippedVectors()} is not overridden, so every vector feature 01 publishes is replayed end to end,
 * the oversize and malformed ones included. This double never fragments a document and never refuses one by
 * size (see its Javadoc), so it has no excuse for a skip. A real transport may — by name, with a reason.
 */
public class InMemoryTransportConformanceTest extends AbstractTransportConformanceTest {

	@Override
	protected JsonRpcTransport createTransport(JsonRpcDispatcher peer) {
		// Peer is Promise<byte[]> respond(byte[]), which is exactly dispatch(byte[])'s shape
		return InMemoryTransport.create(peer::dispatch);
	}

	@Override
	protected ReorderableTransport createReorderableTransport(JsonRpcDispatcher peer) {
		InMemoryTransport transport = InMemoryTransport.create(peer::dispatch);
		return new ReorderableTransport() {
			@Override
			public JsonRpcTransport transport() {
				return transport;
			}

			@Override
			public void startHolding() {
				transport.startHolding();
			}

			@Override
			public int heldCount() {
				return transport.heldCount();
			}

			@Override
			public void releaseInReverseOrder() {
				transport.releaseInReverseOrder();
			}
		};
	}
}
