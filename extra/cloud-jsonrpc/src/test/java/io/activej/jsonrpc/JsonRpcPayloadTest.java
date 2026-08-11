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

import io.activej.common.exception.MalformedDataException;
import io.activej.json.JsonCodec;
import io.activej.json.JsonCodecs;
import io.activej.jsonrpc.impl.RawPayloadView;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * FR-020…FR-026 — the undecoded payload: its bounds contract, its three states, and the retention property.
 */
public class JsonRpcPayloadTest {

	// ---------------------------------------------------------------------------------------------------
	// Bounds contract (T008) — FR-021, verdict 00-A threat 10.
	// A capture rule that is correct on trusted fixtures is not correct by default on hostile input, so this
	// check is unconditional: it is NOT gated behind Checks/CHECKS and must hold with -Dchk=off.
	// ---------------------------------------------------------------------------------------------------

	@Test
	public void rawRefusesANegativeStart() {
		byte[] envelope = "[1,2,3]".getBytes(UTF_8);
		assertThrows(IllegalArgumentException.class, () -> new JsonRpcPayload.Raw(envelope, -1, 3));
	}

	@Test
	public void rawRefusesAnEndPastTheArray() {
		byte[] envelope = "[1,2,3]".getBytes(UTF_8);
		assertThrows(IllegalArgumentException.class, () -> new JsonRpcPayload.Raw(envelope, 0, envelope.length + 1));
	}

	@Test
	public void rawRefusesAnEndBeforeItsStart() {
		byte[] envelope = "[1,2,3]".getBytes(UTF_8);
		assertThrows(IllegalArgumentException.class, () -> new JsonRpcPayload.Raw(envelope, 5, 4));
	}

	@Test
	public void rawRefusesANullArray() {
		assertThrows(NullPointerException.class, () -> new JsonRpcPayload.Raw(null, 0, 0));
	}

	@Test
	public void rawAcceptsTheDegenerateAndFullRanges() {
		byte[] envelope = "[1,2,3]".getBytes(UTF_8);
		assertEquals(0, new JsonRpcPayload.Raw(envelope, 0, 0).size());
		assertEquals(0, new JsonRpcPayload.Raw(envelope, envelope.length, envelope.length).size());
		assertEquals(envelope.length, new JsonRpcPayload.Raw(envelope, 0, envelope.length).size());
	}

	/**
	 * FR-021 — the bound check is <b>unconditional</b>: it must hold with {@code -Dchk=off}.
	 * <p>
	 * {@code Checks.isEnabled} resolves into a {@code static final} at class-initialisation, so no test can
	 * flip the gate after the fact and observe the difference. What a test <i>can</i> do is assert that the
	 * gate is not there at all — the payload source must contain no {@code CHECKS} constant and must not
	 * reference {@code io.activej.common.Checks}. This is untrusted-input territory (verdict 00-A threat 10),
	 * where WI-10 forbids a gated check outright.
	 */
	@Test
	public void theBoundsCheckIsNotBehindTheChecksGate() throws IOException {
		Path source = Path.of("src", "main", "java", "io", "activej", "jsonrpc", "JsonRpcPayload.java");
		if (!Files.isRegularFile(source)) {
			source = Path.of("extra", "cloud-jsonrpc", "src", "main", "java", "io", "activej", "jsonrpc",
				"JsonRpcPayload.java");
		}
		String text = Files.readString(source, UTF_8);
		assertFalse("JsonRpcPayload must not import io.activej.common.Checks",
			text.contains("io.activej.common.Checks"));
		assertFalse("JsonRpcPayload must not gate anything behind a CHECKS constant", text.contains("CHECKS"));
	}

	// ---------------------------------------------------------------------------------------------------
	// States contract (T009) — FR-020, FR-023, FR-025.
	// ---------------------------------------------------------------------------------------------------

	@Test
	public void permitsExactlyThreeStates() {
		assertTrue("JsonRpcPayload must be sealed", JsonRpcPayload.class.isSealed());
		assertEquals(
			Set.of(JsonRpcPayload.Absent.class, JsonRpcPayload.Raw.class, JsonRpcPayload.Encoded.class),
			Set.of(JsonRpcPayload.class.getPermittedSubclasses()));
	}

	@Test
	public void absentIsDistinguishableFromARawOverTheNullLiteral() {
		byte[] envelope = "{\"params\":null}".getBytes(UTF_8);
		JsonRpcPayload explicitNull = new JsonRpcPayload.Raw(envelope, 10, 14);
		JsonRpcPayload absent = JsonRpcPayload.absent();

		assertEquals("null", new String(explicitNull.toByteArray(), UTF_8));

		assertTrue(absent.isAbsent());
		assertFalse(explicitNull.isAbsent());
		assertNotEquals(absent, explicitNull);
		assertTrue(absent instanceof JsonRpcPayload.Absent);
		assertTrue(explicitNull instanceof JsonRpcPayload.Raw);
	}

	@Test
	public void absentIsASingleton() {
		assertSame(JsonRpcPayload.absent(), JsonRpcPayload.absent());
		assertEquals(JsonRpcPayload.absent(), new JsonRpcPayload.Absent());
	}

	@Test
	public void decodingAnAbsentPayloadFails() {
		// "no value" cannot produce a T, and silently handing back null would push the failure to the caller's
		// caller. MalformedDataException is what JsonUtils already uses for "this input cannot become a T".
		MalformedDataException e = assertThrows(MalformedDataException.class,
			() -> JsonRpcPayload.absent().decode(JsonCodecs.ofString()));
		assertTrue(e.getMessage(), e.getMessage().toLowerCase().contains("absent"));
	}

	@Test
	public void toByteArrayOnAnAbsentPayloadFails() {
		assertThrows(IllegalStateException.class, () -> JsonRpcPayload.absent().toByteArray());
	}

	@Test
	public void absentReportsZeroSize() {
		assertEquals(0, JsonRpcPayload.absent().size());
	}

	@Test
	public void toByteArrayReturnsAnIndependentCopy() {
		byte[] envelope = "{\"params\":[1,2,3]}".getBytes(UTF_8);
		JsonRpcPayload.Raw raw = new JsonRpcPayload.Raw(envelope, 10, 17);
		assertEquals("[1,2,3]", new String(raw.toByteArray(), UTF_8));

		byte[] first = raw.toByteArray();
		byte[] second = raw.toByteArray();
		assertNotSame("each call must hand back its own array", first, second);
		assertNotSame("the copy must not alias the envelope", envelope, first);
		assertArrayEquals(first, second);

		first[0] = '#';
		assertArrayEquals("mutating the copy must not touch the envelope",
			"{\"params\":[1,2,3]}".getBytes(UTF_8), envelope);
		assertEquals("[1,2,3]", new String(raw.toByteArray(), UTF_8));
	}

	@Test
	public void sizeReportsTheByteLengthNotTheDecodedLength() {
		// a 4-byte UTF-8 sequence inside the slice: the byte length has no relationship to the string length
		byte[] envelope = "{\"p\":\"😀\"}".getBytes(UTF_8);
		int start = 5;
		int end = envelope.length - 1;
		JsonRpcPayload.Raw raw = new JsonRpcPayload.Raw(envelope, start, end);
		assertEquals(end - start, raw.size());
		assertEquals(6, raw.size());                     // '"' + 4 bytes + '"'
		assertEquals(raw.size(), raw.toByteArray().length);
	}

	@Test
	public void aRawPayloadDecodesThroughACallerSuppliedCodec() throws MalformedDataException {
		byte[] envelope = "{\"jsonrpc\":\"2.0\",\"method\":\"sum\",\"params\":[1,2,3]}".getBytes(UTF_8);
		int start = envelope.length - 8;
		JsonRpcPayload.Raw raw = new JsonRpcPayload.Raw(envelope, start, envelope.length - 1);
		assertEquals("[1,2,3]", new String(raw.toByteArray(), UTF_8));

		assertEquals(List.of(1, 2, 3), raw.decode(JsonCodecs.ofList(JsonCodecs.ofInteger())));
	}

	@Test
	public void aRawPayloadReportsADecodeFailureAsMalformedData() {
		byte[] envelope = "[1,2,3]".getBytes(UTF_8);
		JsonRpcPayload.Raw raw = new JsonRpcPayload.Raw(envelope, 0, envelope.length);
		assertThrows(MalformedDataException.class, () -> raw.decode(JsonCodecs.ofString()));
	}

	@Test
	public void aRawPayloadAppliesTheTrailingDataCompletenessCheck() {
		// the same check JsonUtils.fromJsonBytes applies: a slice that is not consumed whole is malformed,
		// which is what turns a missing right-trim in the capture rule into a failure instead of a silent pass
		byte[] envelope = "[1,2,3] trailing".getBytes(UTF_8);
		JsonRpcPayload.Raw raw = new JsonRpcPayload.Raw(envelope, 0, envelope.length);
		assertThrows(MalformedDataException.class, () -> raw.decode(JsonCodecs.ofList(JsonCodecs.ofInteger())));
	}

	/**
	 * {@code decode} is documented as reporting a decode failure as {@link MalformedDataException}, and that
	 * contract must hold for <b>every</b> byte sequence, not only the ones dsl-json fails cleanly on.
	 * <p>
	 * Probing dsl-json 1.10.0 showed it does not: a lone continuation byte inside a string surfaces as an
	 * unchecked {@code ArrayIndexOutOfBoundsException} out of {@code JsonUtils.fromJsonBytes}. A caller
	 * decoding a peer's payload is entitled to the documented exception, so it is wrapped.
	 */
	@Test
	public void aDecodeFailureIsAlwaysMalformedDataEvenWhenDslJsonThrowsUnchecked() {
		byte[][] hostile = {
			{'"', 'a', (byte) 0x80, 'b', '"'},      // a lone UTF-8 continuation byte
			{'"', (byte) 0xFF, '"'},                // a byte that is never valid UTF-8
			{'"', (byte) 0xFE, '"'},
		};
		for (byte[] payload : hostile) {
			JsonRpcPayload.Raw raw = new JsonRpcPayload.Raw(payload, 0, payload.length);
			try {
				raw.decode(JsonCodecs.ofString());
				// dsl-json may mis-decode rather than fail on some of these; what must never happen is an
				// unchecked exception escaping a method documented to throw MalformedDataException
			} catch (MalformedDataException expected) {
				// the documented contract
			} catch (RuntimeException e) {
				throw new AssertionError(
					"decode() leaked an unchecked " + e.getClass().getName() + " for a hostile payload", e);
			}
		}
	}

	@Test
	public void anEncodedPayloadCarriesItsValueAndCodec() throws MalformedDataException {
		JsonCodec<List<Integer>> codec = JsonCodecs.ofList(JsonCodecs.ofInteger());
		JsonRpcPayload.Encoded<List<Integer>> encoded = new JsonRpcPayload.Encoded<>(codec, List.of(1, 2, 3));

		assertFalse(encoded.isAbsent());
		assertEquals(List.of(1, 2, 3), encoded.decode(codec));
		assertEquals("[1,2,3]", new String(encoded.toByteArray(), UTF_8));
	}

	@Test
	public void anEncodedPayloadRefusesAForeignCodec() {
		JsonCodec<List<Integer>> codec = JsonCodecs.ofList(JsonCodecs.ofInteger());
		JsonRpcPayload.Encoded<List<Integer>> encoded = new JsonRpcPayload.Encoded<>(codec, List.of(1, 2, 3));
		assertThrows(MalformedDataException.class, () -> encoded.decode(JsonCodecs.ofString()));
	}

	@Test
	public void anEncodedPayloadHasNoSizeUntilItIsWritten() {
		// size() exists so a caller can bound work before decoding a captured slice; an Encoded payload has no
		// byte length until its codec has run, and silently allocating inside a method named size() would be a
		// worse answer than refusing. Relaxing a refusal into a value later is not a breaking change.
		JsonRpcPayload.Encoded<String> encoded = new JsonRpcPayload.Encoded<>(JsonCodecs.ofString(), "x");
		assertThrows(UnsupportedOperationException.class, encoded::size);
	}

	@Test
	public void anEncodedPayloadRefusesNullComponents() {
		assertThrows(NullPointerException.class, () -> new JsonRpcPayload.Encoded<>(null, "x"));
		assertThrows(NullPointerException.class, () -> new JsonRpcPayload.Encoded<>(JsonCodecs.ofString(), null));
	}

	// ---------------------------------------------------------------------------------------------------
	// The [start, end) pair is not API (T011) — FR-026, ADR-010.
	// ---------------------------------------------------------------------------------------------------

	@Test
	public void theIndexPairIsReachableOnlyThroughTheExposedInternalsView() {
		byte[] envelope = "{\"params\":[1,2,3]}".getBytes(UTF_8);
		JsonRpcPayload.Raw raw = new JsonRpcPayload.Raw(envelope, 10, 17);

		RawPayloadView view = raw.view();
		assertSame("the view must not copy — that is the whole reason it exists", envelope, view.array());
		assertEquals(10, view.start());
		assertEquals(17, view.end());

		// the supported surface never exposes the pair
		assertFalse("Raw must not publish start()/end()/array() as supported API",
			hasNoArgMethod(JsonRpcPayload.Raw.class, "start") ||
			hasNoArgMethod(JsonRpcPayload.Raw.class, "end") ||
			hasNoArgMethod(JsonRpcPayload.Raw.class, "array"));
	}

	@Test
	public void theViewRefusesAnOutOfRangePairToo() {
		byte[] envelope = "[]".getBytes(UTF_8);
		assertThrows(IllegalArgumentException.class, () -> new RawPayloadView(envelope, 0, 3));
		assertThrows(IllegalArgumentException.class, () -> new RawPayloadView(envelope, -1, 1));
		assertThrows(IllegalArgumentException.class, () -> new RawPayloadView(envelope, 2, 1));
	}

	private static boolean hasNoArgMethod(Class<?> type, String name) {
		try {
			type.getMethod(name);
			return true;
		} catch (NoSuchMethodException e) {
			return false;
		}
	}
}
