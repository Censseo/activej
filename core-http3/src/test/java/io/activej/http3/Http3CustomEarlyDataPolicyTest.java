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

import io.activej.bytebuf.ByteBuf;
import io.activej.csp.supplier.ChannelSupplier;
import io.activej.http.HttpHeader;
import io.activej.http.HttpHeaders;
import io.activej.http.HttpMethod;
import io.activej.http.HttpRequest;
import io.activej.promise.Promise;
import io.activej.test.rules.ByteBufRule;
import org.junit.ClassRule;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * T103, spec FR-065 — <b>a consumer's own early-data policy</b>, and the two properties the interface
 * has to keep for one to be writable at all: the verdict is a pure function of method and header
 * fields, and the body is not readable at the moment it is asked for.
 *
 * <h2>What is being tested, and what is not</h2>
 * The <i>substitution</i> — {@code Http3Server.Builder.withEarlyDataPolicy(...)} reaching the dispatch
 * site — is T111/T109's, and the wiring stage asserts it end to end over a real exchange. What is
 * asserted here is the contract a consumer writes against: that a lambda is all an
 * {@link Http3EarlyDataPolicy} is, that a consumer's verdict is the one a dispatch site acts on, and
 * that the request it is handed cannot tell it anything but its method and its fields.
 * {@link Dispatch} is a stand-in for that dispatch site, deliberately trivial: it exists to name the
 * decision T109 makes, not to reproduce it.
 *
 * <h2>Why the body cannot be observed</h2>
 * Structural, not a promise in prose. Two independent reasons, both asserted below:
 * <ol>
 *   <li>The predicate returns a {@code boolean} <b>synchronously</b>, while an inbound HTTP/3 body is
 *       a CSP channel whose bytes arrive over later QUIC packets. There is no answer a policy could
 *       wait for, so no body byte can reach its verdict.</li>
 *   <li>At the decision point nothing has read the body, so every <i>synchronous</i> body accessor on
 *       the message throws: the request carries a stream and no loaded buffer.</li>
 * </ol>
 * A policy that reached for the body stream anyway would take it from the exchange that owns it —
 * which is a contract violation, not an observation. It is documented on the interface; what the
 * interface can enforce is that the default never does it, and that a body arriving later cannot
 * change an answer already given.
 */
public final class Http3CustomEarlyDataPolicyTest {
	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	private static final String URL = "http://example.test/resource";

	private static final HttpHeader REPLAY_SAFE = HttpHeaders.of("x-replay-safe");

	// ---------------------------------------------------------------- a consumer's policy decides

	/** A lambda is the whole implementation: the interface is functional, and nothing else is required. */
	@Test
	public void aConsumerPolicyIsALambda() {
		Http3EarlyDataPolicy acceptEverything = request -> true;

		assertTrue(acceptEverything.acceptsInEarlyData(request(HttpMethod.POST)));
		assertTrue(acceptEverything.acceptsInEarlyData(request(HttpMethod.GET)));
	}

	/** A policy wider than the default is honoured: the {@code POST} the default refuses is served. */
	@Test
	public void aWiderConsumerPolicyIsHonoured() {
		Dispatch dispatch = new Dispatch(request -> request.getMethod() != HttpMethod.DELETE);

		assertEquals(200, dispatch.serve(request(HttpMethod.POST)));
		assertEquals(200, dispatch.serve(request(HttpMethod.GET)));
		assertEquals(425, dispatch.serve(request(HttpMethod.DELETE)));
		assertEquals(List.of(HttpMethod.POST, HttpMethod.GET), dispatch.served);
	}

	/** And a policy narrower than the default equally so — including one that refuses everything. */
	@Test
	public void aNarrowerConsumerPolicyIsHonoured() {
		Dispatch dispatch = new Dispatch(request -> false);

		assertEquals(425, dispatch.serve(request(HttpMethod.GET)));
		assertEquals(425, dispatch.serve(request(HttpMethod.HEAD)));
		assertEquals("a refusal must not reach the servlet", List.of(), dispatch.served);
		assertEquals(2, dispatch.consulted);
	}

	/** Consulted once per request, and its answer is the decision — not a hint the dispatch site may revise. */
	@Test
	public void thePolicyIsConsultedOncePerRequestAndItsAnswerIsFinal() {
		Dispatch dispatch = new Dispatch(request -> request.getMethod() == HttpMethod.PATCH);

		assertEquals(200, dispatch.serve(request(HttpMethod.PATCH)));
		assertEquals(1, dispatch.consulted);
		assertEquals(425, dispatch.serve(request(HttpMethod.GET)));
		assertEquals(2, dispatch.consulted);
		assertEquals("the safe-method default is a default, not a floor the dispatch site re-applies",
			List.of(HttpMethod.PATCH), dispatch.served);
	}

	/** A request that never arrived in early data is never judged: the policy governs early data only. */
	@Test
	public void aRequestThatDidNotArriveInEarlyDataIsNotJudged() {
		Dispatch dispatch = new Dispatch(request -> false);

		assertEquals(200, dispatch.serve(request(HttpMethod.POST), false));
		assertEquals(0, dispatch.consulted);
		assertEquals(List.of(HttpMethod.POST), dispatch.served);
	}

	// ---------------------------------------------------------------- header fields are in the domain

	/** A policy may read header fields — that is the other half of what "pure function of method and headers" allows. */
	@Test
	public void aConsumerPolicyMayDecideOnAHeaderField() {
		Dispatch dispatch = new Dispatch(request -> "1".equals(request.getHeader(REPLAY_SAFE)));

		assertEquals(200, dispatch.serve(HttpRequest.builder(HttpMethod.POST, URL)
			.withHeader(REPLAY_SAFE, "1")
			.build()));
		assertEquals(425, dispatch.serve(HttpRequest.builder(HttpMethod.POST, URL)
			.withHeader(REPLAY_SAFE, "0")
			.build()));
		assertEquals(425, dispatch.serve(request(HttpMethod.POST)));
	}

	// ---------------------------------------------------------------- the body is not readable

	/**
	 * The default policy evaluates a request whose body stream fails the test if anything so much as
	 * looks at it, and returns its verdict without touching it.
	 */
	@Test
	public void theDefaultPolicyNeverTouchesTheBodyStream() {
		BodyTripwire refused = new BodyTripwire();
		assertFalse(Http3EarlyDataPolicy.DEFAULT_POLICY.acceptsInEarlyData(
			HttpRequest.builder(HttpMethod.POST, URL).withBodyStream(refused).build()));
		assertEquals(0, refused.touches);

		BodyTripwire accepted = new BodyTripwire();
		assertTrue(Http3EarlyDataPolicy.DEFAULT_POLICY.acceptsInEarlyData(
			HttpRequest.builder(HttpMethod.GET, URL).withBodyStream(accepted).build()));
		assertEquals("an acceptance is as blind to the body as a refusal", 0, accepted.touches);
	}

	/**
	 * The same for a consumer's policy driven through the dispatch stand-in, and the reason it holds is
	 * not the policy's good manners: at the decision point the message carries a stream nobody has read,
	 * so every synchronous body accessor on it throws.
	 */
	@Test
	public void noBodyByteIsReadableAtTheMomentTheDecisionIsMade() {
		BodyTripwire tripwire = new BodyTripwire();
		HttpRequest request = HttpRequest.builder(HttpMethod.POST, URL)
			.withBodyStream(tripwire)
			.build();

		List<Boolean> hasBody = new ArrayList<>();
		Dispatch dispatch = new Dispatch(judged -> {
			hasBody.add(judged.hasBody());
			assertThrows(IllegalStateException.class, judged::getBody);
			assertThrows(IllegalStateException.class, judged::takeBody);
			return true;
		});

		assertEquals(200, dispatch.serve(request));
		assertEquals(List.of(false), hasBody);
		assertEquals(0, tripwire.touches);
	}

	/**
	 * The verdict is a value, not a view: bytes that arrive after it was given cannot revise it. This is
	 * what "the body has not been read when the decision is made" means operationally — the decision is
	 * already final by the time a body exists.
	 */
	@Test
	public void bodyBytesArrivingLaterCannotReviseAVerdictAlreadyGiven() {
		Dispatch dispatch = new Dispatch(Http3EarlyDataPolicy.DEFAULT_POLICY);
		HttpRequest request = HttpRequest.builder(HttpMethod.GET, URL)
			.withBodyStream(new BodyTripwire())
			.build();

		assertEquals(200, dispatch.serve(request));
		assertEquals(1, dispatch.consulted);
	}

	// ---------------------------------------------------------------- harness

	private static HttpRequest request(HttpMethod method) {
		return HttpRequest.builder(method, URL).build();
	}

	/**
	 * The decision T109 makes, and nothing else: for a request that arrived in early data, ask the
	 * policy; on a refusal answer {@code 425 (Too Early)} without invoking the servlet.
	 */
	private static final class Dispatch {
		private static final int TOO_EARLY = 425;

		final Http3EarlyDataPolicy policy;
		final List<HttpMethod> served = new ArrayList<>();
		int consulted;

		Dispatch(Http3EarlyDataPolicy policy) {
			this.policy = policy;
		}

		int serve(HttpRequest request) {
			return serve(request, true);
		}

		int serve(HttpRequest request, boolean arrivedInEarlyData) {
			if (arrivedInEarlyData) {
				consulted++;
				if (!policy.acceptsInEarlyData(request)) return TOO_EARLY;
			}
			served.add(request.getMethod());
			return 200;
		}
	}

	/**
	 * An inbound body channel that has produced nothing yet and fails the test if it is asked to.
	 * Implements {@link ChannelSupplier} directly rather than extending {@code AbstractChannelSupplier},
	 * which would need a reactor this test has no other use for.
	 */
	private static final class BodyTripwire implements ChannelSupplier<ByteBuf> {
		int touches;

		@Override
		public Promise<ByteBuf> get() {
			touches++;
			throw new AssertionError("An early-data policy read the request body");
		}

		@Override
		public void closeEx(Exception e) {
			touches++;
			throw new AssertionError("An early-data policy closed the request body channel");
		}
	}
}
