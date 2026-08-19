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

import io.activej.json.JsonCodecFactory;
import io.activej.json.JsonCodecs;
import io.activej.jsonrpc.service.fixtures.FailingApi;
import io.activej.jsonrpc.service.fixtures.FailingApiImpl;
import io.activej.promise.Promise;
import io.activej.reactor.Reactor;

import java.util.List;

/**
 * The service fixtures the transport-parameterised conformance harnesses are built around (FR-074):
 * the interface the 30 vectors call — {@link #ConformanceApi} — its implementation, the one wire
 * shape {@link #Data} declares, the codec factory that gives it a wire form, and the dispatcher
 * construction every subject answers with.
 * <p>
 * This is the <b>new, shared</b> source of these fixtures, consumed by the reverse-direction harness
 * ({@link AbstractBidirectionalTransportConformanceTest}). {@code AbstractTransportConformanceTest}
 * keeps its own private copies of exactly this logic and MUST stay byte-identical (SC-007 / FR-075):
 * it is the published contract feature 013's subclass compiles against, and factoring it out there
 * would break the zero-change gate on {@code extra/cloud-jsonrpc-http}.
 * <p>
 * The fixtures are deliberately <b>not overridable</b> by a subject: a subject that could choose what
 * {@code subtract} means, or how a response is compared, would be conforming to itself. Everything the
 * two harnesses differ in is the <i>transport</i>, not the semantics of the vectors.
 */
final class ConformanceFixtures {
	private ConformanceFixtures() {}

	/**
	 * Exactly the methods §7's examples name, and <b>not</b> {@code foo.get} — {@code method-not-found}
	 * and the {@code -32601} element of {@code batch-mixed} depend on that method being absent, so
	 * registering it would quietly turn two vectors into assertions about nothing.
	 */
	@JsonRpcService
	interface ConformanceApi {
		/** {@code subtract(42, 23) == 19}; the four positional/named vectors and one element of the batch. */
		@JsonRpcMethod("subtract")
		Promise<Integer> subtract(
			@JsonRpcParam("minuend") int minuend, @JsonRpcParam("subtrahend") int subtrahend);

		/** {@code sum(1, 2, 4) == 7} — the first element of {@code batch-mixed}. */
		@JsonRpcMethod("sum")
		Promise<Integer> sum(@JsonRpcParam("a") int a, @JsonRpcParam("b") int b, @JsonRpcParam("c") int c);

		/** Answers {@code ["hello", 5]} with no params at all — the last element of {@code batch-mixed}. */
		@JsonRpcMethod("get_data")
		Promise<Data> getData();

		/** The notification inside {@code batch-mixed}: invoked, answered with nothing. */
		@JsonRpcNotification("notify_hello")
		void notifyHello(@JsonRpcParam("value") int value);

		/** The first element of {@code batch-all-notifications}. */
		@JsonRpcNotification("notify_sum")
		void notifySum(@JsonRpcParam("a") int a, @JsonRpcParam("b") int b, @JsonRpcParam("c") int c);
	}

	/**
	 * {@code get_data}'s result. The specification renders it as the heterogeneous array
	 * {@code ["hello", 5]}, which no derived codec produces, so a codec is registered for this type on
	 * {@link #codecFactory()} — the ordinary way a service declares a wire shape of its own.
	 */
	record Data(String greeting, int answer) {}

	static final class ConformanceApiImpl implements ConformanceApi {
		@Override
		public Promise<Integer> subtract(int minuend, int subtrahend) {
			return Promise.of(minuend - subtrahend);
		}

		@Override
		public Promise<Integer> sum(int a, int b, int c) {
			return Promise.of(a + b + c);
		}

		@Override
		public Promise<Data> getData() {
			return Promise.of(new Data("hello", 5));
		}

		@Override
		public void notifyHello(int value) {
			// §4.1: a notification has nowhere to put a result, and these two exist to be invoked and to
			// produce nothing — the vectors assert the absence of a response element, not a side effect
		}

		@Override
		public void notifySum(int a, int b, int c) {
			// as above
		}
	}

	/**
	 * The peer of every exchange: the vectors' own service plus {@link FailingApi}, whose {@code fail.*}
	 * names no vector uses and which carries the two error shapes the harnesses' non-vector tests need
	 * and no vector has.
	 *
	 * @param notificationFailures the collector the dispatcher's failure handler appends to, so a
	 *                             notification failure anywhere in a replay fails the suite rather than
	 *                             logging past it (FR-077)
	 */
	static JsonRpcDispatcher dispatcher(Reactor reactor, List<String> notificationFailures) {
		return JsonRpcDispatcher.builder(reactor)
			.withCodecFactory(codecFactory())
			.withService(ConformanceApi.class, new ConformanceApiImpl())
			.withService(FailingApi.class, new FailingApiImpl())
			// EventloopRule installs a RETHROWING fatal-error handler, so the default route for a
			// notification's failure would fail the run at the point a server would merely log (FR-100)
			.withFailureHandler((descriptor, e) -> notificationFailures.add(descriptor.wireName() + ": " + e))
			.build();
	}

	/** The default factory plus the one wire shape {@link Data} declares. */
	static JsonCodecFactory codecFactory() {
		return JsonCodecFactory.defaultInstance().rebuild()
			.with(Data.class, ctx -> JsonCodecs
				.ofArrayObject(JsonCodecs.ofString(), JsonCodecs.ofInteger())
				.transform(
					data -> new Object[]{data.greeting(), data.answer()},
					array -> new Data((String) array[0], (Integer) array[1])))
			.build();
	}
}
