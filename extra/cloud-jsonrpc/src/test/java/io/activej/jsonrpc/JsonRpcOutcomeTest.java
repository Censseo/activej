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

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * FR-043, FR-044, FR-080 and data-model §5–6 — the decode-outcome and outgoing-document types.
 */
public class JsonRpcOutcomeTest {

	// ---------------------------------------------------------------------------------------------------
	// Decode outcomes.
	// ---------------------------------------------------------------------------------------------------

	@Test
	public void anInputIsEitherOneDecodedElementOrABatch() {
		assertTrue(JsonRpcInput.class.isSealed());
		assertEquals(
			Set.of(JsonRpcDecoded.class, JsonRpcBatch.class),
			Set.of(JsonRpcInput.class.getPermittedSubclasses()));
	}

	@Test
	public void aDecodedElementIsEitherAMessageOrAFailure() {
		assertTrue(JsonRpcDecoded.class.isSealed());
		assertEquals(
			Set.of(JsonRpcMessage.class, JsonRpcMalformed.class),
			Set.of(JsonRpcDecoded.class.getPermittedSubclasses()));
	}

	@Test
	public void aValidMessageIsItsOwnOutcomeWithNoWrapper() {
		// the success path allocates no wrapper: JsonRpcMessage is itself a JsonRpcDecoded, and a
		// JsonRpcDecoded is itself a JsonRpcInput
		JsonRpcMessage message = new JsonRpcNotification("update");
		assertTrue(message instanceof JsonRpcDecoded);
		assertTrue(message instanceof JsonRpcInput);
	}

	@Test
	public void anOutcomeIsExhaustivelySwitchable() {
		List<JsonRpcInput> inputs = List.of(
			new JsonRpcRequest(new JsonRpcId.Num(1), "sum"),
			new JsonRpcMalformed(JsonRpcId.NULL, JsonRpcErrors.INVALID_REQUEST),
			new JsonRpcBatch(List.of(new JsonRpcNotification("update"))));

		for (JsonRpcInput input : inputs) {
			String kind = switch (input) {
				case JsonRpcMessage ignored -> "message";
				case JsonRpcMalformed ignored -> "malformed";
				case JsonRpcBatch ignored -> "batch";
			};
			assertTrue(Set.of("message", "malformed", "batch").contains(kind));
		}
	}

	@Test
	public void aMalformedElementCarriesItsRecoveredIdAndBecomesAResponse() {
		JsonRpcMalformed recovered = new JsonRpcMalformed(new JsonRpcId.Num(7), JsonRpcErrors.INVALID_REQUEST);
		JsonRpcResponse response = recovered.toResponse();
		assertEquals(new JsonRpcId.Num(7), response.id());
		assertSame(JsonRpcErrors.INVALID_REQUEST, response.error());
		assertTrue(response.result().isAbsent());
		assertTrue(response.isError());

		// FR-037: a failure before an id could be read carries the JSON literal null
		JsonRpcMalformed unrecoverable = new JsonRpcMalformed(JsonRpcId.NULL, JsonRpcErrors.PARSE_ERROR);
		assertEquals(JsonRpcId.NULL, unrecoverable.toResponse().id());
	}

	@Test
	public void aMalformedElementRefusesNulls() {
		assertThrows(NullPointerException.class, () -> new JsonRpcMalformed(null, JsonRpcErrors.PARSE_ERROR));
		assertThrows(NullPointerException.class, () -> new JsonRpcMalformed(JsonRpcId.NULL, null));
	}

	// ---------------------------------------------------------------------------------------------------
	// Batch.
	// ---------------------------------------------------------------------------------------------------

	@Test
	public void aBatchHoldsItsElementsInOrderAndImmutably() {
		List<JsonRpcDecoded> source = new ArrayList<>(List.of(
			new JsonRpcRequest(new JsonRpcId.Num(1), "sum"),
			new JsonRpcNotification("update"),
			new JsonRpcMalformed(JsonRpcId.NULL, JsonRpcErrors.INVALID_REQUEST)));

		JsonRpcBatch batch = new JsonRpcBatch(source);
		assertEquals(3, batch.size());

		source.clear();
		assertEquals("the batch must have copied its list, not aliased it", 3, batch.size());
		assertThrows(UnsupportedOperationException.class,
			() -> batch.elements().add(new JsonRpcNotification("sneaky")));
	}

	@Test
	public void aBatchRefusesAnEmptyList() {
		// an empty top-level array is a single -32600, never a batch (FR-039) — so a batch of nothing must be
		// unconstructible rather than merely unused
		assertThrows(IllegalArgumentException.class, () -> new JsonRpcBatch(List.of()));
		assertThrows(NullPointerException.class, () -> new JsonRpcBatch(null));
	}

	@Test
	public void aBatchIsNotAMessageSoItCannotNest() {
		JsonRpcBatch batch = new JsonRpcBatch(List.of(new JsonRpcNotification("update")));
		assertTrue(batch instanceof JsonRpcInput);
		// `batch instanceof JsonRpcDecoded` does not even compile once the hierarchy is right, so the check has
		// to be reflective — the compiler is the real assertion here
		assertFalse("a batch must not typecheck as an element, or [[…]] would be legal",
			JsonRpcDecoded.class.isAssignableFrom(JsonRpcBatch.class));
		assertFalse(JsonRpcMessage.class.isAssignableFrom(JsonRpcBatch.class));
	}

	@Test
	public void batchesAreValues() {
		assertEquals(
			new JsonRpcBatch(List.of(new JsonRpcNotification("update"))),
			new JsonRpcBatch(List.of(new JsonRpcNotification("update"))));
		assertNotEquals(
			new JsonRpcBatch(List.of(new JsonRpcNotification("update"))),
			new JsonRpcBatch(List.of(new JsonRpcNotification("other"))));
	}

	// ---------------------------------------------------------------------------------------------------
	// Outgoing documents — FR-043, FR-044.
	// ---------------------------------------------------------------------------------------------------

	@Test
	public void anOutputIsNoneSingleOrBatch() {
		assertTrue(JsonRpcOutput.class.isSealed());
		assertEquals(
			Set.of(JsonRpcOutput.None.class, JsonRpcOutput.Single.class, JsonRpcOutput.Batch.class),
			Set.of(JsonRpcOutput.class.getPermittedSubclasses()));
	}

	@Test
	public void noneIsDistinguishableFromAnEmptyArrayByType() {
		// FR-044: a lone notification and an all-notification batch render zero bytes, which is NOT "[]"
		JsonRpcOutput none = JsonRpcOutput.NONE;
		assertTrue(none instanceof JsonRpcOutput.None);
		assertSame(JsonRpcOutput.NONE, JsonRpcOutput.NONE);
		assertEquals(JsonRpcOutput.NONE, new JsonRpcOutput.None());

		// and the type system offers no empty batch to confuse it with
		assertThrows(IllegalArgumentException.class, () -> new JsonRpcOutput.Batch(List.of()));
	}

	@Test
	public void aSingleCarriesOneMessage() {
		JsonRpcMessage message = JsonRpcResponse.ofError(new JsonRpcId.Num(1), JsonRpcErrors.METHOD_NOT_FOUND);
		JsonRpcOutput.Single single = new JsonRpcOutput.Single(message);
		assertSame(message, single.message());
		assertThrows(NullPointerException.class, () -> new JsonRpcOutput.Single(null));
	}

	@Test
	public void aBatchOfOneIsStillABatch() {
		// FR-043: batch-ness survives rendering exactly one response
		JsonRpcOutput.Batch batch = new JsonRpcOutput.Batch(
			List.of(JsonRpcResponse.ofError(JsonRpcId.NULL, JsonRpcErrors.INVALID_REQUEST)));
		assertEquals(1, batch.messages().size());
		assertTrue(batch instanceof JsonRpcOutput);
	}

	@Test
	public void anOutputBatchIsImmutable() {
		List<JsonRpcMessage> source = new ArrayList<>(List.of(new JsonRpcNotification("update")));
		JsonRpcOutput.Batch batch = new JsonRpcOutput.Batch(source);
		source.clear();
		assertEquals(1, batch.messages().size());
		assertThrows(UnsupportedOperationException.class,
			() -> batch.messages().add(new JsonRpcNotification("sneaky")));
	}

	@Test
	public void anOutputIsExhaustivelySwitchable() {
		List<JsonRpcOutput> outputs = List.of(
			JsonRpcOutput.NONE,
			new JsonRpcOutput.Single(new JsonRpcNotification("update")),
			new JsonRpcOutput.Batch(List.of(new JsonRpcNotification("update"))));

		for (JsonRpcOutput output : outputs) {
			String kind = switch (output) {
				case JsonRpcOutput.None ignored -> "none";
				case JsonRpcOutput.Single ignored -> "single";
				case JsonRpcOutput.Batch ignored -> "batch";
			};
			assertTrue(Set.of("none", "single", "batch").contains(kind));
		}
	}
}
