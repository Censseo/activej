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

package io.activej.jsonrpc.transport.http;

import io.activej.http.MediaType;
import io.activej.test.rules.ByteBufRule;
import org.junit.ClassRule;
import org.junit.Test;

import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * FR-016 as a pure matcher contract — the accepted-media-type allow-list, unit-tested on
 * {@link JsonRpcHttpMediaTypes#isAccepted(String)} booleans (T018).
 * <p>
 * Deliberately <b>not</b> an HTTP test: the {@code 415} mapping of an unexpected media type is
 * T027's, and this class must not pre-empt it. Here "text/plain is rejected" means
 * {@code isAccepted("text/plain") == false}, not a {@code 415} on the wire.
 * <p>
 * The inputs are <b>not</b> normalised before calling {@code isAccepted} — the point of the test
 * is that the matcher does the normalisation: per probe R4 the servlet sees the raw header string
 * ({@code application/json; charset=UTF-8} arrives verbatim), so the matcher itself must strip
 * parameters and match case-insensitively (RFC 2045 §2).
 * <p>
 * {@link ByteBufRule} alone is declared: no reactor is driven and no buffer moves (FR-025a's
 * literal "every test class carries {@code ByteBufRule}" holds; its reactor rules apply to every
 * class driving a reactor).
 */
public final class JsonRpcHttpMediaTypesTest {
	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	/** The three accepted media types — the allow-list is the contract (FR-016). */
	@Test
	public void theThreeAcceptedMediaTypesAreAccepted() {
		for (String accepted : JsonRpcHttpMediaTypes.RAW_ACCEPTED) {
			assertTrue(accepted + " must be accepted", JsonRpcHttpMediaTypes.isAccepted(accepted));
		}
	}

	/** Media-type parameters such as {@code charset} are ignored when matching — on every accepted type. */
	@Test
	public void aCharsetParameterIsIgnored() {
		for (String accepted : JsonRpcHttpMediaTypes.RAW_ACCEPTED) {
			assertTrue(accepted + "; charset=UTF-8 must be accepted",
				JsonRpcHttpMediaTypes.isAccepted(accepted + "; charset=UTF-8"));
		}
	}

	/** RFC 2045 §2 — media types are case-insensitive; the matcher must be, too. */
	@Test
	public void mediaTypesAreMatchedCaseInsensitively() {
		assertTrue("media types are case-insensitive (RFC 2045 §2)",
			JsonRpcHttpMediaTypes.isAccepted("Application/JSON"));
	}

	/** Anything outside the allow-list is rejected — {@code text/plain} included. */
	@Test
	public void otherMediaTypesAreRejected() {
		assertFalse(JsonRpcHttpMediaTypes.isAccepted("text/plain"));
		assertFalse(JsonRpcHttpMediaTypes.isAccepted("application/xml"));
	}

	/** An absent {@code Content-Type} is rejected, not assumed (a deliberate strictness decision, FR-016). */
	@Test
	public void anAbsentHeaderIsRejected() {
		assertFalse("an absent Content-Type is rejected, not assumed", JsonRpcHttpMediaTypes.isAccepted(null));
	}

	/** The introspectable {@code ACCEPTED} set holds exactly the three media types, nothing more. */
	@Test
	public void theAcceptedSetIsExactlyTheThreeMediaTypes() {
		Set<String> names = JsonRpcHttpMediaTypes.ACCEPTED.stream()
			.map(MediaType::toString)
			.collect(Collectors.toSet());
		assertEquals(Set.of("application/json", "application/json-rpc", "application/jsonrequest"), names);
	}
}
