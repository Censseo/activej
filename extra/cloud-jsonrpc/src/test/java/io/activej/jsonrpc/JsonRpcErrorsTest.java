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

package io.activej.jsonrpc;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * FR-014, FR-015, FR-016, research Decision 12 — the nine named codes, the reserved-range predicates, and
 * the deliberate asymmetry between the application-facing factory and the decode path.
 */
public class JsonRpcErrorsTest {

	// ---------------------------------------------------------------------------------------------------
	// The nine constants. Codes and messages are the specification's (§5.1) for the five predefined ones and
	// this feature's published contract for the four implementation-defined ones — changing either is a
	// breaking change, so they are asserted literally rather than derived.
	// ---------------------------------------------------------------------------------------------------

	@Test
	public void thePredefinedFive() {
		assertError(-32700, "Parse error", JsonRpcErrors.PARSE_ERROR);
		assertError(-32600, "Invalid Request", JsonRpcErrors.INVALID_REQUEST);
		assertError(-32601, "Method not found", JsonRpcErrors.METHOD_NOT_FOUND);
		assertError(-32602, "Invalid params", JsonRpcErrors.INVALID_PARAMS);
		assertError(-32603, "Internal error", JsonRpcErrors.INTERNAL_ERROR);
	}

	@Test
	public void theFourThisFeatureAllocates() {
		assertError(-32001, "Request too large", JsonRpcErrors.REQUEST_TOO_LARGE);
		assertError(-32002, "Batch too large", JsonRpcErrors.BATCH_TOO_LARGE);
		assertError(-32003, "Nesting too deep", JsonRpcErrors.NESTING_TOO_DEEP);
		assertError(-32004, "Invalid response", JsonRpcErrors.INVALID_RESPONSE);
	}

	@Test
	public void allNineLiveInsideTheReservedRangeAndCarryNoData() {
		for (JsonRpcError error : nine()) {
			assertTrue(error.code() + " must be reserved", JsonRpcErrors.isReserved(error.code()));
			assertTrue("a predefined error carries no data by default", error.data().isAbsent());
		}
	}

	@Test
	public void theFourImplementationDefinedCodesAreServerErrors() {
		// §5.1 reserves -32099 … -32000 for implementation-defined server errors; that is where these four sit
		for (JsonRpcError error : List.of(JsonRpcErrors.REQUEST_TOO_LARGE, JsonRpcErrors.BATCH_TOO_LARGE,
			JsonRpcErrors.NESTING_TOO_DEEP, JsonRpcErrors.INVALID_RESPONSE)) {
			assertTrue(error.code() + " must be a server error", JsonRpcErrors.isServerError(error.code()));
		}
		// while the five predefined ones sit outside that sub-range
		for (JsonRpcError error : List.of(JsonRpcErrors.PARSE_ERROR, JsonRpcErrors.INVALID_REQUEST,
			JsonRpcErrors.METHOD_NOT_FOUND, JsonRpcErrors.INVALID_PARAMS, JsonRpcErrors.INTERNAL_ERROR)) {
			assertFalse(error.code() + " must not be a server error", JsonRpcErrors.isServerError(error.code()));
		}
	}

	@Test
	public void theNineCodesAreDistinct() {
		assertEquals(9, nine().stream().map(JsonRpcError::code).distinct().count());
	}

	// ---------------------------------------------------------------------------------------------------
	// Range predicates — asserted at the boundaries, which is the only place an off-by-one can hide.
	// ---------------------------------------------------------------------------------------------------

	@Test
	public void reservedRangeBoundaries() {
		assertFalse(JsonRpcErrors.isReserved(-32769));
		assertTrue(JsonRpcErrors.isReserved(-32768));
		assertTrue(JsonRpcErrors.isReserved(-32100));
		assertTrue(JsonRpcErrors.isReserved(-32099));
		assertTrue(JsonRpcErrors.isReserved(-32000));
		assertFalse(JsonRpcErrors.isReserved(-31999));
		assertFalse(JsonRpcErrors.isReserved(0));
		assertFalse(JsonRpcErrors.isReserved(1000));
	}

	@Test
	public void serverErrorRangeBoundaries() {
		assertFalse(JsonRpcErrors.isServerError(-32768));
		assertFalse(JsonRpcErrors.isServerError(-32100));
		assertTrue(JsonRpcErrors.isServerError(-32099));
		assertTrue(JsonRpcErrors.isServerError(-32050));
		assertTrue(JsonRpcErrors.isServerError(-32000));
		assertFalse(JsonRpcErrors.isServerError(-31999));
	}

	@Test
	public void everyServerErrorIsAlsoReserved() {
		for (int code = -32768; code <= -31999; code++) {
			if (JsonRpcErrors.isServerError(code)) {
				assertTrue(code + " is a server error but not reserved", JsonRpcErrors.isReserved(code));
			}
		}
	}

	// ---------------------------------------------------------------------------------------------------
	// The asymmetry (research Decision 12): refuse locally, accept from a peer.
	// ---------------------------------------------------------------------------------------------------

	@Test
	public void ofAcceptsAnApplicationCode() {
		JsonRpcError error = JsonRpcErrors.of(-1, "User not found");
		assertEquals(-1, error.code());
		assertEquals("User not found", error.message());
		assertTrue(error.data().isAbsent());

		JsonRpcError withData = JsonRpcErrors.of(42, "Retry later",
			new JsonRpcPayload.Encoded<>(io.activej.json.JsonCodecs.ofInteger(), 5));
		assertEquals(42, withData.code());
		assertFalse(withData.data().isAbsent());
	}

	@Test
	public void ofRejectsEveryCodeInTheReservedRange() {
		for (int code : new int[]{-32768, -32700, -32603, -32600, -32100, -32099, -32004, -32001, -32000}) {
			IllegalArgumentException e = assertThrows("code " + code + " must be refused",
				IllegalArgumentException.class, () -> JsonRpcErrors.of(code, "mine"));
			assertTrue("the refusal must name the reserved range, got: " + e.getMessage(),
				e.getMessage().contains("-32768") && e.getMessage().contains("-32000"));
		}
	}

	@Test
	public void ofAcceptsTheCodesJustOutsideTheReservedRange() {
		assertEquals(-32769, JsonRpcErrors.of(-32769, "mine").code());
		assertEquals(-31999, JsonRpcErrors.of(-31999, "mine").code());
	}

	@Test
	public void ofAnyAcceptsAReservedCode() {
		// a peer's -32601 must survive decoding verbatim; refusing it would discard exactly the information
		// the peer meant to convey, and turn a well-formed error response into a second error
		JsonRpcError peerError = JsonRpcErrors.ofAny(-32601, "Method not found", JsonRpcPayload.absent());
		assertEquals(-32601, peerError.code());
		assertEquals("Method not found", peerError.message());

		assertEquals(-32768, JsonRpcErrors.ofAny(-32768, "peer's", JsonRpcPayload.absent()).code());
		assertEquals(-32000, JsonRpcErrors.ofAny(-32000, "peer's", JsonRpcPayload.absent()).code());
		// and a non-reserved code too — ofAny is permissive, not reserved-only
		assertEquals(7, JsonRpcErrors.ofAny(7, "peer's", JsonRpcPayload.absent()).code());
	}

	@Test
	public void ofAnyReproducesThePredefinedConstants() {
		assertEquals(JsonRpcErrors.PARSE_ERROR,
			JsonRpcErrors.ofAny(-32700, "Parse error", JsonRpcPayload.absent()));
	}

	// ---------------------------------------------------------------------------------------------------
	// The error object itself — FR-014.
	// ---------------------------------------------------------------------------------------------------

	@Test
	public void anErrorRefusesANullMessageAndNullData() {
		assertThrows(NullPointerException.class, () -> new JsonRpcError(-1, null, JsonRpcPayload.absent()));
		assertThrows(NullPointerException.class, () -> new JsonRpcError(-1, "message", null));
		assertThrows(NullPointerException.class, () -> JsonRpcErrors.of(-1, null));
	}

	@Test
	public void errorsAreValues() {
		assertEquals(JsonRpcErrors.of(-1, "same"), JsonRpcErrors.of(-1, "same"));
		assertEquals(JsonRpcErrors.of(-1, "same").hashCode(), JsonRpcErrors.of(-1, "same").hashCode());
		assertNotEquals(JsonRpcErrors.of(-1, "same"), JsonRpcErrors.of(-2, "same"));
		assertNotEquals(JsonRpcErrors.of(-1, "same"), JsonRpcErrors.of(-1, "other"));
	}

	@Test
	public void constantsAreSingletons() {
		assertSame(JsonRpcErrors.PARSE_ERROR, JsonRpcErrors.PARSE_ERROR);
	}

	private static List<JsonRpcError> nine() {
		return List.of(
			JsonRpcErrors.PARSE_ERROR, JsonRpcErrors.INVALID_REQUEST, JsonRpcErrors.METHOD_NOT_FOUND,
			JsonRpcErrors.INVALID_PARAMS, JsonRpcErrors.INTERNAL_ERROR,
			JsonRpcErrors.REQUEST_TOO_LARGE, JsonRpcErrors.BATCH_TOO_LARGE, JsonRpcErrors.NESTING_TOO_DEEP,
			JsonRpcErrors.INVALID_RESPONSE);
	}

	private static void assertError(int code, String message, JsonRpcError actual) {
		assertEquals(code, actual.code());
		assertEquals(message, actual.message());
	}
}
