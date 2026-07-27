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

package io.activej.quic;

import io.activej.test.rules.ByteBufRule;
import org.junit.ClassRule;
import org.junit.Test;

import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Arrays;

import static org.junit.Assert.*;

public class QuicConnectionIdTest {

	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	@Test
	public void acceptsLengthsInValidRange() {
		assertEquals(0, QuicConnectionId.of(new byte[0]).length());
		assertEquals(1, QuicConnectionId.of(new byte[1]).length());
		assertEquals(20, QuicConnectionId.of(new byte[20]).length());
	}

	@Test(expected = IllegalArgumentException.class)
	public void rejectsTooLongLength() {
		QuicConnectionId.of(new byte[21]);
	}

	@Test(expected = IllegalArgumentException.class)
	public void rejectsTooLongRandomLength() {
		QuicConnectionId.random(21, new SecureRandom());
	}

	@Test(expected = IllegalArgumentException.class)
	public void rejectsNegativeRandomLength() {
		QuicConnectionId.random(-1, new SecureRandom());
	}

	@Test
	public void valueEquality() {
		QuicConnectionId a = QuicConnectionId.of(new byte[] {1, 2, 3});
		QuicConnectionId b = QuicConnectionId.of(new byte[] {1, 2, 3});
		QuicConnectionId c = QuicConnectionId.of(new byte[] {1, 2, 4});

		assertEquals(a, b);
		assertEquals(a.hashCode(), b.hashCode());
		assertNotEquals(a, c);
		assertNotEquals(a, QuicConnectionId.of(new byte[] {1, 2}));
	}

	@Test
	public void randomWithoutExplicitSourceUsesASharedDefault() {
		QuicConnectionId a = QuicConnectionId.random(12);
		QuicConnectionId b = QuicConnectionId.random(12);
		assertEquals(12, a.length());
		assertEquals(12, b.length());
		assertNotEquals("astronomically unlikely to collide", a, b);
	}

	@Test
	public void randomGenerationIsDeterministicForASeededSource() throws NoSuchAlgorithmException {
		// SHA1PRNG is documented to produce a repeatable sequence for a given seed, unlike
		// platform-default algorithms (e.g. NativePRNG) which mix in external entropy.
		QuicConnectionId a = QuicConnectionId.random(8, seededSha1Prng());
		QuicConnectionId b = QuicConnectionId.random(8, seededSha1Prng());
		assertEquals(a, b);
		assertEquals(8, a.length());
	}

	private static SecureRandom seededSha1Prng() throws NoSuchAlgorithmException {
		SecureRandom random = SecureRandom.getInstance("SHA1PRNG");
		random.setSeed(new byte[] {42});
		return random;
	}

	@Test
	public void randomZeroLengthProducesEmptyId() {
		QuicConnectionId id = QuicConnectionId.random(0, new SecureRandom());
		assertEquals(0, id.length());
		assertArrayEquals(new byte[0], id.bytes());
	}

	@Test
	public void bytesAreDefensivelyCopiedOnConstruction() {
		byte[] source = {1, 2, 3};
		QuicConnectionId id = QuicConnectionId.of(source);
		source[0] = 99;
		assertArrayEquals(new byte[] {1, 2, 3}, id.bytes());
	}

	@Test
	public void bytesAreDefensivelyCopiedOnRetrieval() {
		QuicConnectionId id = QuicConnectionId.of(new byte[] {1, 2, 3});
		byte[] retrieved = id.bytes();
		retrieved[0] = 99;
		assertArrayEquals(new byte[] {1, 2, 3}, id.bytes());
	}

	@Test
	public void equalsIsValueBasedNotArrayIdentity() {
		byte[] raw = {5, 6, 7};
		QuicConnectionId id1 = QuicConnectionId.of(raw);
		QuicConnectionId id2 = QuicConnectionId.of(Arrays.copyOf(raw, raw.length));
		assertEquals(id1, id2);
	}
}
