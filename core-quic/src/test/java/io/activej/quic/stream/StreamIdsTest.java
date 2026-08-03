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

package io.activej.quic.stream;

import io.activej.quic.connection.QuicConnection.Role;
import io.activej.test.rules.ByteBufRule;
import org.junit.ClassRule;
import org.junit.Test;

import static io.activej.quic.connection.QuicConnection.Role.CLIENT;
import static io.activej.quic.connection.QuicConnection.Role.SERVER;
import static org.junit.Assert.*;

/**
 * RFC 9000 §2.1 stream identifiers: the two low bits, the ordinal above them, and the send/receive
 * permission table.
 */
public class StreamIdsTest {

	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	// The four stream ids of ordinal 0, i.e. the RFC 9000 §2.1 Table 1 type codes themselves.
	private static final long CLIENT_BIDI = 0x0;
	private static final long SERVER_BIDI = 0x1;
	private static final long CLIENT_UNI = 0x2;
	private static final long SERVER_UNI = 0x3;

	@Test
	public void bitLayoutOfTheFourTypes() {
		assertTrue(StreamIds.isClientInitiated(CLIENT_BIDI));
		assertTrue(StreamIds.isBidirectional(CLIENT_BIDI));

		assertFalse(StreamIds.isClientInitiated(SERVER_BIDI));
		assertTrue(StreamIds.isBidirectional(SERVER_BIDI));

		assertTrue(StreamIds.isClientInitiated(CLIENT_UNI));
		assertFalse(StreamIds.isBidirectional(CLIENT_UNI));

		assertFalse(StreamIds.isClientInitiated(SERVER_UNI));
		assertFalse(StreamIds.isBidirectional(SERVER_UNI));
	}

	@Test
	public void bitLayoutIsIndependentOfTheOrdinal() {
		// the type bits are the two low bits whatever sits above them
		for (long ordinal : new long[]{0, 1, 2, 3, 7, 63, 1_000_000, StreamIds.MAX_ORDINAL}) {
			assertTrue(StreamIds.isClientInitiated(StreamIds.of(ordinal, true, true)));
			assertTrue(StreamIds.isBidirectional(StreamIds.of(ordinal, true, true)));

			assertFalse(StreamIds.isClientInitiated(StreamIds.of(ordinal, false, true)));
			assertTrue(StreamIds.isBidirectional(StreamIds.of(ordinal, false, true)));

			assertTrue(StreamIds.isClientInitiated(StreamIds.of(ordinal, true, false)));
			assertFalse(StreamIds.isBidirectional(StreamIds.of(ordinal, true, false)));

			assertFalse(StreamIds.isClientInitiated(StreamIds.of(ordinal, false, false)));
			assertFalse(StreamIds.isBidirectional(StreamIds.of(ordinal, false, false)));
		}
	}

	@Test
	public void ofProducesTheRfcTypeCodesAtOrdinalZero() {
		assertEquals(CLIENT_BIDI, StreamIds.of(0, true, true));
		assertEquals(SERVER_BIDI, StreamIds.of(0, false, true));
		assertEquals(CLIENT_UNI, StreamIds.of(0, true, false));
		assertEquals(SERVER_UNI, StreamIds.of(0, false, false));
	}

	@Test
	public void theFourTypesAreIndependentCounters() {
		// stream 0 is client bidi #0 and stream 2 is client uni #0 — same ordinal, unrelated streams
		assertEquals(0, StreamIds.ordinal(CLIENT_BIDI));
		assertEquals(0, StreamIds.ordinal(CLIENT_UNI));
		assertNotEquals(CLIENT_BIDI, CLIENT_UNI);

		// consecutive ordinals of one type are 4 apart on the wire
		assertEquals(4, StreamIds.of(1, true, true));
		assertEquals(8, StreamIds.of(2, true, true));
		assertEquals(1, StreamIds.ordinal(4));
		assertEquals(2, StreamIds.ordinal(8));
	}

	@Test
	public void ordinalRoundTrips() {
		for (long ordinal : new long[]{0, 1, 2, 3, 5, 17, 255, 1L << 20, (1L << 59) - 1, StreamIds.MAX_ORDINAL}) {
			for (boolean clientInitiated : new boolean[]{true, false}) {
				for (boolean bidirectional : new boolean[]{true, false}) {
					long streamId = StreamIds.of(ordinal, clientInitiated, bidirectional);
					assertEquals(ordinal, StreamIds.ordinal(streamId));
					assertEquals(clientInitiated, StreamIds.isClientInitiated(streamId));
					assertEquals(bidirectional, StreamIds.isBidirectional(streamId));
				}
			}
		}
	}

	@Test
	public void maxOrdinalIsTwoToTheSixtyMinusOne() {
		assertEquals((1L << 60) - 1, StreamIds.MAX_ORDINAL);
	}

	@Test
	public void maxOrdinalEncodesToTheLargestStreamId() {
		// RFC 9000 §2.1: a stream id is a 62-bit varint value, so the largest one is 2^62 - 1
		assertEquals((1L << 62) - 1, StreamIds.of(StreamIds.MAX_ORDINAL, false, false));
		assertEquals(StreamIds.MAX_ORDINAL, StreamIds.ordinal((1L << 62) - 1));
		assertFalse(StreamIds.isClientInitiated((1L << 62) - 1));
		assertFalse(StreamIds.isBidirectional((1L << 62) - 1));
	}

	@Test
	public void ofRejectsAnOrdinalPastMaxOrdinal() {
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
			() -> StreamIds.of(StreamIds.MAX_ORDINAL + 1, true, true));
		assertTrue(e.getMessage(), e.getMessage().contains("ordinal"));
	}

	@Test
	public void ofRejectsANegativeOrdinal() {
		assertThrows(IllegalArgumentException.class, () -> StreamIds.of(-1, true, true));
		assertThrows(IllegalArgumentException.class, () -> StreamIds.of(Long.MIN_VALUE, false, false));
	}

	@Test
	public void bidirectionalStreamsAreReadableAndWritableByBothRoles() {
		long[] bidirectional = {CLIENT_BIDI, SERVER_BIDI, StreamIds.of(9, true, true), StreamIds.of(9, false, true)};
		for (long streamId : bidirectional) {
			for (Role role : Role.values()) {
				assertTrue(StreamIds.canSend(streamId, role));
				assertTrue(StreamIds.canReceive(streamId, role));
			}
		}
	}

	@Test
	public void onlyTheInitiatorSendsOnAUnidirectionalStream() {
		assertTrue(StreamIds.canSend(CLIENT_UNI, CLIENT));
		assertFalse(StreamIds.canSend(CLIENT_UNI, SERVER));

		assertFalse(StreamIds.canSend(SERVER_UNI, CLIENT));
		assertTrue(StreamIds.canSend(SERVER_UNI, SERVER));
	}

	@Test
	public void onlyTheNonInitiatorReceivesOnAUnidirectionalStream() {
		assertFalse(StreamIds.canReceive(CLIENT_UNI, CLIENT));
		assertTrue(StreamIds.canReceive(CLIENT_UNI, SERVER));

		assertTrue(StreamIds.canReceive(SERVER_UNI, CLIENT));
		assertFalse(StreamIds.canReceive(SERVER_UNI, SERVER));
	}

	@Test
	public void permissionTableHoldsAtEveryOrdinal() {
		for (long ordinal : new long[]{0, 1, 42, StreamIds.MAX_ORDINAL}) {
			long clientUni = StreamIds.of(ordinal, true, false);
			long serverUni = StreamIds.of(ordinal, false, false);
			long clientBidi = StreamIds.of(ordinal, true, true);
			long serverBidi = StreamIds.of(ordinal, false, true);

			assertTrue(StreamIds.canSend(clientUni, CLIENT));
			assertFalse(StreamIds.canReceive(clientUni, CLIENT));
			assertFalse(StreamIds.canSend(clientUni, SERVER));
			assertTrue(StreamIds.canReceive(clientUni, SERVER));

			assertFalse(StreamIds.canSend(serverUni, CLIENT));
			assertTrue(StreamIds.canReceive(serverUni, CLIENT));
			assertTrue(StreamIds.canSend(serverUni, SERVER));
			assertFalse(StreamIds.canReceive(serverUni, SERVER));

			for (long bidi : new long[]{clientBidi, serverBidi}) {
				assertTrue(StreamIds.canSend(bidi, CLIENT));
				assertTrue(StreamIds.canReceive(bidi, CLIENT));
				assertTrue(StreamIds.canSend(bidi, SERVER));
				assertTrue(StreamIds.canReceive(bidi, SERVER));
			}
		}
	}
}
