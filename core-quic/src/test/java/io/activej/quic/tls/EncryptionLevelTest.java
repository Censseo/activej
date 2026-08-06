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

import io.activej.quic.connection.QuicConnection;
import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.Assert.*;

/**
 * T054 and T055 — the two audits research D-5 makes mandatory when {@code EncryptionLevel} gains
 * {@code ZERO_RTT}. They are contract, not review comments: both properties are silent
 * wrong-behaviour risks rather than compile errors, so each one is asserted here.
 * <p>
 * (a) {@code ZERO_RTT} maps to the <b>Application</b> packet number space through a named function
 * ({@link EncryptionLevel#packetNumberSpace()}), never through an {@code ordinal()} coincidence, and
 * there is no fourth space (RFC 9000 §12.3). A fourth space would give 0-RTT its own packet
 * numbering, which is a protocol bug the wire cannot express: a 0-RTT and a 1-RTT packet carrying
 * the same number would be two different packets under one number, and their ACKs would be
 * indistinguishable.
 * <p>
 * (b) {@code ZERO_RTT} carries no CRYPTO stream — RFC 9000 §12.5 forbids a CRYPTO frame in a 0-RTT
 * packet — so its CRYPTO-reassembly slot stays unallocated in both engines, and every pre-existing
 * ordinal is unchanged so the {@code ordinal()}-indexed arrays only widen.
 * <p>
 * No {@code ByteBufRule}: nothing here allocates a pooled buffer.
 */
public final class EncryptionLevelTest {

	// ------------------------------------------------------------ (b) ordinals

	@Test
	public void zeroRttIsAppendedLastSoEveryExistingOrdinalIsUnchanged() {
		assertEquals(0, EncryptionLevel.INITIAL.ordinal());
		assertEquals(1, EncryptionLevel.HANDSHAKE.ordinal());
		assertEquals(2, EncryptionLevel.ONE_RTT.ordinal());
		assertEquals(3, EncryptionLevel.ZERO_RTT.ordinal());
		assertEquals(4, EncryptionLevel.values().length);
		assertEquals(
			List.of("INITIAL", "HANDSHAKE", "ONE_RTT", "ZERO_RTT"),
			Arrays.stream(EncryptionLevel.values()).map(Enum::name).toList());
	}

	// ------------------------------------------------------------ (a) packet number space

	@Test
	public void zeroRttMapsToTheApplicationPacketNumberSpace() {
		assertSame(EncryptionLevel.Space.APPLICATION, EncryptionLevel.ZERO_RTT.packetNumberSpace());
		assertSame(EncryptionLevel.ONE_RTT.packetNumberSpace(), EncryptionLevel.ZERO_RTT.packetNumberSpace());

		assertSame(EncryptionLevel.Space.INITIAL, EncryptionLevel.INITIAL.packetNumberSpace());
		assertSame(EncryptionLevel.Space.HANDSHAKE, EncryptionLevel.HANDSHAKE.packetNumberSpace());
	}

	@Test
	public void theSpaceMappingIsNamedRatherThanAnOrdinalCoincidence() {
		// The two levels that share a space have different ordinals, so no ordinal()-derived scheme
		// could produce this mapping — which is exactly why the mapping has to be a function.
		assertNotEquals(EncryptionLevel.ONE_RTT.ordinal(), EncryptionLevel.ZERO_RTT.ordinal());
		assertSame(EncryptionLevel.ONE_RTT.packetNumberSpace(), EncryptionLevel.ZERO_RTT.packetNumberSpace());
		assertNotEquals(EncryptionLevel.ZERO_RTT.ordinal(), EncryptionLevel.ZERO_RTT.packetNumberSpace().ordinal());
	}

	@Test
	public void thereIsNoFourthPacketNumberSpace() {
		assertEquals(
			List.of("INITIAL", "HANDSHAKE", "APPLICATION"),
			Arrays.stream(EncryptionLevel.Space.values()).map(Enum::name).toList());

		Set<EncryptionLevel.Space> reachable = Arrays.stream(EncryptionLevel.values())
			.map(EncryptionLevel::packetNumberSpace)
			.collect(Collectors.toCollection(() -> EnumSet.noneOf(EncryptionLevel.Space.class)));
		assertEquals(EnumSet.allOf(EncryptionLevel.Space.class), reachable);
		assertEquals(3, reachable.size());
	}

	@Test
	public void theConnectionKeysItsPacketNumberSpacesBySpaceNotByLevel() throws Exception {
		// The structural half of "no fourth space": the only per-connection container of packet
		// number spaces is keyed by Space, so a ZERO_RTT key is not expressible at all.
		Field spaces = QuicConnection.class.getDeclaredField("spaces");
		ParameterizedType type = (ParameterizedType) spaces.getGenericType();
		assertSame(EncryptionLevel.Space.class, type.getActualTypeArguments()[0]);
	}

	// ------------------------------------------------------------ (b) CRYPTO stream

	@Test
	public void zeroRttCarriesNoCryptoStream() {
		assertFalse(EncryptionLevel.ZERO_RTT.hasCryptoStream());

		assertTrue(EncryptionLevel.INITIAL.hasCryptoStream());
		assertTrue(EncryptionLevel.HANDSHAKE.hasCryptoStream());
		assertTrue(EncryptionLevel.ONE_RTT.hasCryptoStream());
	}

	@Test
	public void neitherEngineAllocatesACryptoReassemblySlotForZeroRtt() throws Exception {
		assertNoReassemblySlotForZeroRtt(TlsClientEngine.class);
		assertNoReassemblySlotForZeroRtt(TlsServerEngine.class);
	}

	private static void assertNoReassemblySlotForZeroRtt(Class<?> engine) throws Exception {
		Method factory = engine.getDeclaredMethod("newReassemblyBuffers");
		factory.setAccessible(true);
		Object[] buffers = (Object[]) factory.invoke(null);

		assertEquals(EncryptionLevel.values().length, buffers.length);
		assertNull(engine.getSimpleName() + " allocated a CRYPTO reassembly slot for ZERO_RTT",
			buffers[EncryptionLevel.ZERO_RTT.ordinal()]);
		for (EncryptionLevel level : EncryptionLevel.values()) {
			if (level == EncryptionLevel.ZERO_RTT) continue;
			assertNotNull(engine.getSimpleName() + " has no CRYPTO reassembly slot for " + level,
				buffers[level.ordinal()]);
		}
	}
}
