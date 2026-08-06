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

import io.activej.http.HttpHeader;
import io.activej.http.HttpHeaders;
import io.activej.http.HttpMethod;
import io.activej.http.HttpRequest;
import io.activej.test.rules.ByteBufRule;
import org.junit.ClassRule;
import org.junit.Test;

import java.util.EnumSet;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * T098, spec FR-064/FR-065 — <b>what a server is willing to run from early data</b>, the default half.
 *
 * <h2>The rule</h2>
 * {@link Http3EarlyDataPolicy#DEFAULT_POLICY} accepts a request whose method is safe per RFC 9110
 * §9.2.1 — {@code GET}, {@code HEAD}, {@code OPTIONS}, {@code TRACE} — and refuses every other one. A
 * refusal is answered {@code 425 (Too Early)} (RFC 8470) with the servlet never invoked; that half is
 * the dispatch site's (T109), and is asserted end to end by the wiring tests. What is asserted here is
 * the predicate the dispatch site consults, which is where the decision actually lives.
 *
 * <h2>Why the refusal set is asserted exhaustively</h2>
 * Rejection is the safe answer, so the interesting failure is a method that becomes accepted without
 * anyone deciding it should be. Enumerating {@link HttpMethod#values()} rather than a hand-written list
 * makes that structural: a constant added to {@code HttpMethod} tomorrow is refused by this policy and
 * asserted to be, without this test being touched.
 *
 * <h2>"A made-up method"</h2>
 * There is no such request to test with, and that is a property rather than a gap:
 * {@code core-http}'s {@link HttpMethod} is a closed enum, and {@code Http3Headers.toRequestBuilder}
 * fails an unrecognized {@code :method} with {@code H3_MESSAGE_ERROR} long before a message exists to
 * hand a policy. The exhaustive enum sweep is the whole domain the predicate can ever see.
 */
public final class Http3EarlyDataPolicyTest {
	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	private static final String URL = "http://example.test/resource";

	/** RFC 8470 §5.1. Not an {@link HttpHeaders} constant — {@code core-http} gains nothing from this feature. */
	private static final HttpHeader EARLY_DATA = HttpHeaders.of("early-data");

	// ---------------------------------------------------------------- the safe-method set

	@Test
	public void theSafeMethodSetIsExactlyTheOneRfc9110Names() {
		assertEquals(
			Set.of(HttpMethod.GET, HttpMethod.HEAD, HttpMethod.OPTIONS, HttpMethod.TRACE),
			Http3EarlyDataPolicy.SAFE_METHODS);
	}

	@Test(expected = UnsupportedOperationException.class)
	public void theSafeMethodSetCannotBeChangedByAConsumer() {
		Http3EarlyDataPolicy.SAFE_METHODS.add(HttpMethod.POST);
	}

	// ---------------------------------------------------------------- the default verdicts

	@Test
	public void theDefaultAcceptsEverySafeMethod() {
		for (HttpMethod method : Set.of(HttpMethod.GET, HttpMethod.HEAD, HttpMethod.OPTIONS, HttpMethod.TRACE)) {
			assertTrue(method + " is safe per RFC 9110 §9.2.1 and must be accepted from early data",
				accepts(method));
		}
	}

	/**
	 * Every constant {@link HttpMethod} declares that is not one of the four — {@code POST}, {@code PUT},
	 * {@code DELETE}, {@code PATCH}, {@code CONNECT} and the WebDAV set — is refused.
	 */
	@Test
	public void theDefaultRefusesEveryMethodThatIsNotSafe() {
		Set<HttpMethod> refused = EnumSet.complementOf(EnumSet.copyOf(Http3EarlyDataPolicy.SAFE_METHODS));
		assertEquals(13, refused.size());
		for (HttpMethod method : refused) {
			assertFalse(method + " is not safe per RFC 9110 §9.2.1 and must not be run from early data",
				accepts(method));
		}
	}

	/** The four that carry the feature's whole point, named rather than swept, so a regression reads plainly. */
	@Test
	public void theMethodsWithSideEffectsAreRefusedByName() {
		assertFalse(accepts(HttpMethod.POST));
		assertFalse(accepts(HttpMethod.PUT));
		assertFalse(accepts(HttpMethod.DELETE));
		assertFalse(accepts(HttpMethod.PATCH));
	}

	/**
	 * {@code CONNECT} never reaches a servlet over HTTP/3 in this module at all — {@code Http3Headers}
	 * refuses it with {@code H3_REQUEST_REJECTED}. Asserted anyway: the policy states its own rule rather
	 * than relying on an earlier layer's, which is what lets the earlier layer change.
	 */
	@Test
	public void connectIsRefusedOnThisPolicysOwnAuthority() {
		assertFalse(accepts(HttpMethod.CONNECT));
	}

	// ---------------------------------------------------------------- the decision is the method's

	/** Header fields are in the domain, but the default rule reads none of them: a safe method stays accepted. */
	@Test
	public void headerFieldsDoNotChangeTheDefaultVerdict() {
		assertTrue(Http3EarlyDataPolicy.DEFAULT_POLICY.acceptsInEarlyData(HttpRequest.builder(HttpMethod.GET, URL)
			.withHeader(HttpHeaders.CONTENT_LENGTH, "7")
			.withHeader(HttpHeaders.AUTHORIZATION, "Bearer irrelevant")
			.withHeader(EARLY_DATA, "1")
			.build()));

		assertFalse("no header a peer chooses may buy a POST its way into early data",
			Http3EarlyDataPolicy.DEFAULT_POLICY.acceptsInEarlyData(HttpRequest.builder(HttpMethod.POST, URL)
				.withHeader(EARLY_DATA, "1")
				.withHeader(HttpHeaders.CONTENT_LENGTH, "0")
				.build()));
	}

	/** A predicate, not a state machine: the same request answers the same twice, and so do two of them. */
	@Test
	public void theVerdictIsStableAcrossRepeatedEvaluation() {
		HttpRequest request = HttpRequest.builder(HttpMethod.POST, URL).build();

		assertFalse(Http3EarlyDataPolicy.DEFAULT_POLICY.acceptsInEarlyData(request));
		assertFalse(Http3EarlyDataPolicy.DEFAULT_POLICY.acceptsInEarlyData(request));
		assertTrue(Http3EarlyDataPolicy.DEFAULT_POLICY.acceptsInEarlyData(HttpRequest.builder(HttpMethod.GET, URL).build()));
		assertFalse(Http3EarlyDataPolicy.DEFAULT_POLICY.acceptsInEarlyData(request));
	}

	private static boolean accepts(HttpMethod method) {
		return Http3EarlyDataPolicy.DEFAULT_POLICY.acceptsInEarlyData(HttpRequest.builder(method, URL).build());
	}
}
