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

package io.activej.http3.frame;

import io.activej.bytebuf.ByteBuf;
import io.activej.bytebuf.ByteBufPool;
import io.activej.http3.Http3Exception;
import io.activej.quic.codec.QuicVarInts;
import io.activej.test.rules.ByteBufRule;
import org.junit.ClassRule;
import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * T119 / spec FR-079: {@code SETTINGS_H3_DATAGRAM} (0x33, RFC 9297 §2.1.1) at the <b>frame</b> level —
 * the identifier and its legal value set, plus the transport-parameter cross-check as a pure function.
 * <p>
 * Closing the connection with {@code H3_SETTINGS_ERROR} is the connection layer's job, and neither
 * predicate below throws: an unknown or unenforced identifier is carried by {@link SettingsFrame#read}
 * without complaint exactly as GREASE is (RFC 9114 §9), and the connection decides. These tests pin the
 * verdicts the connection layer must reuse rather than re-derive.
 */
public class Http3DatagramSettingTest {

	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	@Test
	public void identifierIsTheRfc9297Value() {
		assertEquals(0x33, SettingsFrame.H3_DATAGRAM);
	}

	@Test
	public void zeroAndOneAreTheOnlyLegalValues() {
		assertTrue(SettingsFrame.isValidH3DatagramValue(0));
		assertTrue(SettingsFrame.isValidH3DatagramValue(1));
	}

	@Test
	public void anyOtherValueIsMalformed() {
		for (long value : new long[]{2, 3, 63, 64, 1 << 20, QuicVarInts.MAX_VALUE, -1}) {
			assertFalse("SETTINGS_H3_DATAGRAM = " + value, SettingsFrame.isValidH3DatagramValue(value));
		}
	}

	@Test
	public void oneWithoutMaxDatagramFrameSizeIsInconsistent() {
		assertFalse(SettingsFrame.isH3DatagramSettingConsistent(1, 0));
	}

	@Test
	public void oneWithMaxDatagramFrameSizeIsConsistent() {
		assertTrue(SettingsFrame.isH3DatagramSettingConsistent(1, 1200));
		assertTrue(SettingsFrame.isH3DatagramSettingConsistent(1, 65535));
	}

	@Test
	public void zeroIsConsistentWithOrWithoutMaxDatagramFrameSize() {
		assertTrue(SettingsFrame.isH3DatagramSettingConsistent(0, 0));
		assertTrue(SettingsFrame.isH3DatagramSettingConsistent(0, 1200));
	}

	@Test
	public void theSettingRoundTripsThroughTheFrame() throws Http3Exception {
		SettingsFrame decoded = readPayload(SettingsFrame.H3_DATAGRAM, 1);

		assertArrayEquals(new long[]{SettingsFrame.H3_DATAGRAM}, decoded.identifiers);
		assertArrayEquals(new long[]{1}, decoded.values);
		assertTrue(SettingsFrame.isValidH3DatagramValue(decoded.values[0]));
	}

	@Test
	public void anIllegalValueSurvivesDecodeSoTheConnectionCanClassifyIt() throws Http3Exception {
		SettingsFrame decoded = readPayload(SettingsFrame.H3_DATAGRAM, 5);

		assertEquals(5, decoded.values[0]);
		assertFalse(SettingsFrame.isValidH3DatagramValue(decoded.values[0]));
	}

	private static SettingsFrame readPayload(long identifier, long value) throws Http3Exception {
		ByteBuf payload = ByteBufPool.allocate(32);
		QuicVarInts.write(payload, identifier);
		QuicVarInts.write(payload, value);
		try {
			return SettingsFrame.read(payload);
		} finally {
			payload.recycle();
		}
	}
}
