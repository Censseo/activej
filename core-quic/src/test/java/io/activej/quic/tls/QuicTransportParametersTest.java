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

package io.activej.quic.tls;

import io.activej.bytebuf.ByteBuf;
import io.activej.bytebuf.ByteBufPool;
import io.activej.common.exception.MalformedDataException;
import io.activej.common.exception.TruncatedDataException;
import io.activej.quic.codec.QuicVarInts;
import io.activej.test.rules.ByteBufRule;
import org.junit.ClassRule;
import org.junit.Test;

import java.util.Random;

import static org.junit.Assert.*;

/**
 * RFC 9000 §18 transport parameters: full-set round-trip, RFC defaults for absent parameters,
 * duplicate detection, unknown-id tolerance and encode-time mandatory-parameter enforcement.
 */
public class QuicTransportParametersTest {

	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	private final Random random = new Random(9000);

	@Test
	public void roundTripsFullParameterSet() throws Exception {
		QuicTransportParameters parameters = new QuicTransportParameters(
			randomBytes(8),             // originalDestinationConnectionId
			30_000,                     // maxIdleTimeout
			randomBytes(16),            // statelessResetToken
			1452,                       // maxUdpPayloadSize
			10_000_000,                 // initialMaxData
			2_000_000,                  // initialMaxStreamDataBidiLocal
			3_000_000,                  // initialMaxStreamDataBidiRemote
			4_000_000,                  // initialMaxStreamDataUni
			100,                        // initialMaxStreamsBidi
			50,                         // initialMaxStreamsUni
			4,                          // ackDelayExponent
			30,                         // maxAckDelay
			true,                       // disableActiveMigration
			randomBytes(41),            // preferredAddress
			8,                          // activeConnectionIdLimit
			randomBytes(8),             // initialSourceConnectionId
			randomBytes(6));            // retrySourceConnectionId
		assertRoundTrip(parameters, true);
	}

	@Test
	public void roundTripsClientParameterSet() throws Exception {
		QuicTransportParameters parameters = new QuicTransportParameters(
			null, 0, null, 65527, 5_000_000, 1_000_000, 1_000_000, 1_000_000, 16, 16,
			3, 25, false, null, 2, randomBytes(8), null);
		assertRoundTrip(parameters, false);
	}

	@Test
	public void absentParametersGetRfcDefaults() throws Exception {
		// RFC 9000 §18.2: an absent parameter has its default value.
		ByteBuf buf = ByteBufPool.allocate(1);
		QuicTransportParameters parameters = QuicTransportParameters.read(buf);
		buf.recycle();

		assertNull(parameters.originalDestinationConnectionId());
		assertEquals(0, parameters.maxIdleTimeout());
		assertNull(parameters.statelessResetToken());
		assertEquals(65527, parameters.maxUdpPayloadSize());
		assertEquals(0, parameters.initialMaxData());
		assertEquals(0, parameters.initialMaxStreamDataBidiLocal());
		assertEquals(0, parameters.initialMaxStreamDataBidiRemote());
		assertEquals(0, parameters.initialMaxStreamDataUni());
		assertEquals(0, parameters.initialMaxStreamsBidi());
		assertEquals(0, parameters.initialMaxStreamsUni());
		assertEquals(3, parameters.ackDelayExponent());
		assertEquals(25, parameters.maxAckDelay());
		assertFalse(parameters.disableActiveMigration());
		assertNull(parameters.preferredAddress());
		assertEquals(2, parameters.activeConnectionIdLimit());
		assertNull(parameters.initialSourceConnectionId());
		assertNull(parameters.retrySourceConnectionId());
	}

	@Test
	public void duplicateParameterThrowsMalformedDataException() {
		// RFC 9000 §18.1: receipt of a duplicate transport parameter is a transport-parameter
		// error, surfaced here as a parse failure for the connection layer to map.
		ByteBuf buf = ByteBufPool.allocate(8);
		QuicVarInts.write(buf, 0x01); // max_idle_timeout
		QuicVarInts.write(buf, 1);
		QuicVarInts.write(buf, 10);
		QuicVarInts.write(buf, 0x01); // max_idle_timeout again
		QuicVarInts.write(buf, 1);
		QuicVarInts.write(buf, 20);
		try {
			QuicTransportParameters.read(buf);
			fail("expected MalformedDataException");
		} catch (TruncatedDataException e) {
			fail("unexpected TruncatedDataException: " + e);
		} catch (MalformedDataException expected) {
			// expected
		} finally {
			buf.recycle();
		}
	}

	@Test
	public void unknownParameterIsTolerated() throws Exception {
		ByteBuf buf = ByteBufPool.allocate(16);
		QuicVarInts.write(buf, 0x01); // max_idle_timeout = 42
		QuicVarInts.write(buf, 1);
		QuicVarInts.write(buf, 42);
		QuicVarInts.write(buf, 0x2f1a); // unknown id: skipped
		QuicVarInts.write(buf, 3);
		buf.put(new byte[] {7, 8, 9});
		QuicVarInts.write(buf, 0x0b); // max_ack_delay = 50
		QuicVarInts.write(buf, 1);
		QuicVarInts.write(buf, 50);

		QuicTransportParameters parameters = QuicTransportParameters.read(buf);
		assertFalse(buf.canRead());
		assertEquals(42, parameters.maxIdleTimeout());
		assertEquals(50, parameters.maxAckDelay());
		assertEquals(65527, parameters.maxUdpPayloadSize());
		buf.recycle();
	}

	@Test
	public void encodeWithoutInitialSourceConnectionIdThrowsForBothRoles() {
		QuicTransportParameters missing = QuicTransportParameters.defaults(null);
		for (boolean server : new boolean[] {false, true}) {
			ByteBuf buf = ByteBufPool.allocate(64);
			try {
				missing.writeTo(buf, server);
				fail("expected IllegalStateException for server=" + server);
			} catch (IllegalStateException expected) {
				// expected
			} finally {
				buf.recycle();
			}
		}
	}

	@Test
	public void serverEncodeWithoutOriginalDestinationConnectionIdThrows() {
		QuicTransportParameters missing = QuicTransportParameters.defaults(new byte[] {1, 2, 3, 4});
		ByteBuf buf = ByteBufPool.allocate(64);
		try {
			missing.writeTo(buf, true);
			fail("expected IllegalStateException");
		} catch (IllegalStateException expected) {
			// expected
		} finally {
			buf.recycle();
		}
	}

	@Test
	public void clientEncodeWithoutOriginalDestinationConnectionIdIsAllowed() throws Exception {
		QuicTransportParameters parameters = QuicTransportParameters.defaults(new byte[] {1, 2, 3, 4});
		ByteBuf buf = ByteBufPool.allocate(parameters.encodedLength());
		parameters.writeTo(buf, false);
		QuicTransportParameters decoded = QuicTransportParameters.read(buf);
		assertFalse(buf.canRead());
		assertEquals(parameters, decoded);
		buf.recycle();
	}

	@Test
	public void statelessResetTokenWithWrongLengthThrowsMalformedDataException() {
		ByteBuf buf = ByteBufPool.allocate(32);
		QuicVarInts.write(buf, 0x02); // stateless_reset_token
		QuicVarInts.write(buf, 15);   // must be exactly 16 bytes
		buf.put(new byte[15]);
		try {
			QuicTransportParameters.read(buf);
			fail("expected MalformedDataException");
		} catch (MalformedDataException expected) {
			// expected
		} finally {
			buf.recycle();
		}
	}

	@Test
	public void disableActiveMigrationWithNonEmptyValueThrowsMalformedDataException() {
		ByteBuf buf = ByteBufPool.allocate(8);
		QuicVarInts.write(buf, 0x0c); // disable_active_migration
		QuicVarInts.write(buf, 1);    // must be zero-length
		buf.put(new byte[] {0});
		try {
			QuicTransportParameters.read(buf);
			fail("expected MalformedDataException");
		} catch (MalformedDataException expected) {
			// expected
		} finally {
			buf.recycle();
		}
	}

	@Test
	public void declaredLengthExceedingRemainingThrowsMalformedDataException() {
		ByteBuf buf = ByteBufPool.allocate(8);
		QuicVarInts.write(buf, 0x04);          // initial_max_data
		QuicVarInts.write(buf, 1_000_000);     // declared length cannot fit
		buf.put(new byte[] {1, 2});
		try {
			QuicTransportParameters.read(buf);
			fail("expected MalformedDataException");
		} catch (TruncatedDataException e) {
			fail("unexpected TruncatedDataException: " + e);
		} catch (MalformedDataException expected) {
			// expected
		} finally {
			buf.recycle();
		}
	}

	@Test
	public void varintParameterWithTrailingBytesThrowsMalformedDataException() {
		ByteBuf buf = ByteBufPool.allocate(8);
		QuicVarInts.write(buf, 0x0b); // max_ack_delay
		QuicVarInts.write(buf, 2);    // declares 2 value bytes
		buf.put(new byte[] {25, 99}); // a 1-byte varint followed by garbage
		try {
			QuicTransportParameters.read(buf);
			fail("expected MalformedDataException");
		} catch (MalformedDataException expected) {
			// expected
		} finally {
			buf.recycle();
		}
	}

	// ---- helpers ----

	private void assertRoundTrip(QuicTransportParameters parameters, boolean server) throws Exception {
		ByteBuf buf = ByteBufPool.allocate(parameters.encodedLength());
		parameters.writeTo(buf, server);
		assertEquals(parameters.encodedLength(), buf.readRemaining());
		QuicTransportParameters decoded = QuicTransportParameters.read(buf);
		assertFalse(buf.canRead());
		assertEquals(parameters, decoded);
		assertEquals(parameters.hashCode(), decoded.hashCode());
		buf.recycle();
	}

	private byte[] randomBytes(int length) {
		byte[] bytes = new byte[length];
		random.nextBytes(bytes);
		return bytes;
	}
}
