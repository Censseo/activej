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

import io.activej.test.rules.ByteBufRule;
import org.junit.ClassRule;
import org.junit.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.Assert.*;

/**
 * T099a, T100, T101 — the bounded single-use early-data replay register (spec FR-069, FR-070,
 * FR-071; RFC 8446 §8).
 * <p>
 * Three properties are under test, and the second and third are the ones a wrong implementation
 * gets silently wrong:
 * <ol>
 *     <li><b>single use</b> — a ticket identity accepted for early data once is refused for ever
 *     after, and the refusal is a return value rather than an exception, because a replay is a
 *     routine event on a public port;</li>
 *     <li><b>fail closed</b> — the register never drops a live record to make room. A full window
 *     refuses the <i>new</i> presentation; the only way a slot is reclaimed is its own ticket
 *     expiring, and such a ticket is refused on presentation anyway. A register that treated an
 *     evicted record as unseen would make its own bound the attack: flood it, then replay;</li>
 *     <li><b>constant-time lookup, not-found included</b> — every lookup performs the same fixed
 *     number of comparisons over the same fixed width, through the same JDK primitive the PSK
 *     binder check uses.</li>
 * </ol>
 * <b>No wall-clock timing assertion is made</b>: under JIT, GC and a shared CI machine such an
 * assertion is flaky by construction and proves nothing. What is asserted instead is mechanical and
 * stable — a fixed comparison count, a fixed comparison width, {@link MessageDigest#isEqual} as the
 * comparator, and a scan loop with no early exit.
 * <p>
 * Two residuals are deliberately <b>not</b> equalised and are stated rather than hidden: which slots
 * a lookup touches is a function of the ticket identity, which travels in the clear in the
 * ClientHello and is not secret; and the accept/refuse answer is observable by design, since the
 * caller acts on it. What is defended is that nothing <i>beyond</i> that answer leaks.
 */
public final class QuicReplayGuardTest {

	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	private static final long HOUR = 3_600_000L;
	private static final long ROTATION = 6 * HOUR;
	private static final long LIFETIME = HOUR;
	private static final long T0 = 1_700_000_000_000L;

	// ---- T099a: single use ----

	@Test
	public void aTicketIsGrantedOnceAndRefusedOnEveryLaterPresentation() {
		QuicReplayGuard guard = QuicReplayGuard.create(64);
		QuicSessionTicket ticket = ticket(1);

		assertTrue(guard.tryConsume(ticket, T0));
		assertFalse(guard.tryConsume(ticket, T0));
		assertFalse(guard.tryConsume(ticket, T0 + 1));
		assertFalse(guard.tryConsume(ticket, T0 + LIFETIME / 2));

		assertEquals(1, guard.granted());
		assertEquals(3, guard.refusedReplayed());
		assertEquals(0, guard.refusedAtCapacity());
		assertEquals(0, guard.refusedExpired());
		assertEquals(1, guard.records());
	}

	@Test
	public void aRepresentedTicketIsRefusedByReturnValueAndNeverByException() {
		QuicReplayGuard guard = QuicReplayGuard.create(64);
		QuicSessionTicket ticket = ticket(1);
		guard.tryConsume(ticket, T0);

		for (int i = 0; i < 1000; i++) {
			assertFalse(guard.tryConsume(ticket, T0));
		}

		assertEquals(1000, guard.refusedReplayed());
	}

	@Test
	public void distinctTicketsAreIndependent() {
		QuicReplayGuard guard = QuicReplayGuard.create(64);

		assertTrue(guard.tryConsume(ticket(1), T0));
		assertTrue(guard.tryConsume(ticket(2), T0));
		assertFalse(guard.tryConsume(ticket(1), T0));
		assertFalse(guard.tryConsume(ticket(2), T0));

		assertEquals(2, guard.granted());
		assertEquals(2, guard.refusedReplayed());
	}

	@Test
	public void aTicketThatWasNeverSealedIsACallerBug() {
		QuicReplayGuard guard = QuicReplayGuard.create(64);
		QuicSessionTicket unsealed = base().withIssuedAt(T0).withLifetime(LIFETIME).withTicketAgeAdd(0)
			.withTransportParameters(QuicSessionTicketTest.remembered()).build();
		assertEquals(0, unsealed.identity().length);

		IllegalArgumentException e =
			assertThrows(IllegalArgumentException.class, () -> guard.tryConsume(unsealed, T0));

		assertTrue(e.getMessage(), e.getMessage().contains("empty identity"));
		assertEquals(0, guard.granted());
	}

	@Test
	public void aRealSealedThenOpenedTicketTakesTheSamePath() {
		QuicTicketKeys keys = QuicTicketKeys.create(new SecureRandom(), ROTATION, LIFETIME, T0);
		QuicReplayGuard guard = QuicReplayGuard.create(64);
		QuicSessionTicket opened = keys.open(keys.seal(sealable(), T0));
		assertNotNull(opened);

		assertTrue(guard.tryConsume(opened, T0));
		assertFalse(guard.tryConsume(opened, T0));
		assertEquals(1, guard.refusedReplayed());
	}

	@Test
	public void twoSealsOfOneSessionAreTwoIndependentSingleUseGrants() {
		// RFC 8446 §8.1: it is the ticket that is single-use, and each NewSessionTicket carries its
		// own blob — so a second ticket issued for the same session is a second, independent grant
		QuicTicketKeys keys = QuicTicketKeys.create(new SecureRandom(), ROTATION, LIFETIME, T0);
		QuicReplayGuard guard = QuicReplayGuard.create(64);
		QuicSessionTicket first = keys.open(keys.seal(sealable(), T0));
		QuicSessionTicket second = keys.open(keys.seal(sealable(), T0));
		assertNotNull(first);
		assertNotNull(second);
		assertNotEquals(HexFormat.of().formatHex(first.identity()), HexFormat.of().formatHex(second.identity()));

		assertTrue(guard.tryConsume(first, T0));
		assertTrue(guard.tryConsume(second, T0));
		assertFalse(guard.tryConsume(first, T0));
		assertFalse(guard.tryConsume(second, T0));
	}

	// ---- T100: fail closed ----

	@Test
	public void aFullRegisterRefusesTheNewPresentationAndEvictsNothing() {
		QuicReplayGuard guard = QuicReplayGuard.create(8);
		for (int i = 1; i <= 8; i++) {
			assertTrue("ticket " + i + " must fit a register of 8", guard.tryConsume(ticket(i), T0));
		}
		assertEquals(8, guard.records());

		assertFalse("the ninth presentation must be refused, not make room", guard.tryConsume(ticket(9), T0));
		assertEquals(1, guard.refusedAtCapacity());
		assertEquals(8, guard.records());

		// nothing was dropped to admit it: every earlier ticket is still known
		for (int i = 1; i <= 8; i++) {
			assertFalse("ticket " + i + " must still be refused after a capacity refusal",
				guard.tryConsume(ticket(i), T0));
		}
		assertEquals(8, guard.refusedReplayed());

		// and the refused one was never admitted either
		assertFalse(guard.tryConsume(ticket(9), T0));
		assertEquals(2, guard.refusedAtCapacity());
		assertEquals(8, guard.granted());
	}

	@Test
	public void aSlotIsReclaimedOnlyWhenItsOwnTicketHasExpired() {
		QuicReplayGuard guard = QuicReplayGuard.create(1);

		assertTrue(guard.tryConsume(ticket(1), T0));
		assertFalse("a live record is never dropped for a newcomer", guard.tryConsume(ticket(2), T0));
		assertFalse(guard.tryConsume(ticket(2), T0 + LIFETIME - 1));
		assertEquals(2, guard.refusedAtCapacity());

		assertTrue("the only reclaim path is the record's own expiry",
			guard.tryConsume(ticket(2, T0 + LIFETIME), T0 + LIFETIME));
		assertEquals(1, guard.records());

		// and the reclaimed ticket is refused by its own expiry, so reclaiming it admitted no replay
		assertFalse(guard.tryConsume(ticket(1), T0 + LIFETIME));
		assertEquals(1, guard.refusedExpired());
	}

	@Test
	public void aTicketPresentedPastItsOwnExpiryIsRefusedEvenIfNeverSeen() {
		QuicReplayGuard guard = QuicReplayGuard.create(64);

		assertFalse(guard.tryConsume(ticket(1), T0 + LIFETIME));
		assertFalse(guard.tryConsume(ticket(1), T0 + 10 * LIFETIME));

		assertEquals(2, guard.refusedExpired());
		assertEquals(0, guard.granted());
		assertEquals(0, guard.records());
	}

	@Test
	public void aRegisterMustHaveRoomForAtLeastOneRecord() {
		assertThrows(IllegalArgumentException.class, () -> QuicReplayGuard.create(0));
		assertThrows(IllegalArgumentException.class, () -> QuicReplayGuard.create(-1));
		assertThrows(IllegalArgumentException.class, () -> QuicReplayGuard.create(Integer.MIN_VALUE));
		assertEquals(1, QuicReplayGuard.create(1).maxRecords());
	}

	@Test
	public void theDefaultBoundIsWhatTheSettingsAdvertise() {
		// QuicConnectionSettings.maxEarlyDataReplayRecords() defaults to 65536; a register of that
		// size must be constructible, since that is what the server will ask for
		QuicReplayGuard guard = QuicReplayGuard.create(65536);
		assertEquals(65536, guard.maxRecords());
		assertTrue(guard.tryConsume(ticket(1), T0));
	}

	// ---- T101: constant-time lookup, not-found included ----

	@Test
	public void everyLookupCostsExactlyTheSameNumberOfComparisons() {
		int window = Math.min(QuicReplayGuard.WINDOW, 8);
		QuicReplayGuard guard = QuicReplayGuard.create(8);

		// (a) miss on an empty register
		long before = guard.comparisons();
		assertTrue(guard.tryConsume(ticket(1), T0));
		assertEquals("a miss on an empty register must cost a full window", window, guard.comparisons() - before);

		// (b) hit — a replay
		before = guard.comparisons();
		assertFalse(guard.tryConsume(ticket(1), T0));
		assertEquals("a hit must cost exactly what a miss costs", window, guard.comparisons() - before);

		// (c) miss with the window partly occupied
		before = guard.comparisons();
		assertTrue(guard.tryConsume(ticket(2), T0));
		assertEquals(window, guard.comparisons() - before);

		// (d) refusal at capacity
		for (int i = 3; i <= 8; i++) assertTrue(guard.tryConsume(ticket(i), T0));
		before = guard.comparisons();
		assertFalse(guard.tryConsume(ticket(9), T0));
		assertEquals("a capacity refusal must cost a full window too", window, guard.comparisons() - before);

		// (e) a window slot holding an expired record
		QuicReplayGuard aged = QuicReplayGuard.create(8);
		assertTrue(aged.tryConsume(ticket(1), T0));
		before = aged.comparisons();
		assertTrue(aged.tryConsume(ticket(2, T0 + LIFETIME), T0 + LIFETIME));
		assertEquals("an expired slot must be compared against a decoy, not skipped",
			window, aged.comparisons() - before);
	}

	@Test
	public void theComparisonWidthIsFixedWhateverTheTicketIdentityLength() {
		QuicReplayGuard guard = QuicReplayGuard.create(64);
		int window = Math.min(QuicReplayGuard.WINDOW, 64);

		long before = guard.comparisons();
		assertTrue(guard.tryConsume(ticketWithIdentity(new byte[]{7}), T0));
		assertEquals(window, guard.comparisons() - before);

		before = guard.comparisons();
		assertTrue(guard.tryConsume(ticketWithIdentity(new byte[4096]), T0));
		assertEquals("the identity is digested to a fixed width before any comparison",
			window, guard.comparisons() - before);
		assertEquals(32, QuicReplayGuard.DIGEST_LENGTH);
	}

	@Test
	public void theLookupUsesTheJdkConstantTimeComparison() throws IOException {
		// the comparison is a separate named method precisely so this is checkable rather than
		// aspirational; mirrors TlsServerResumptionTest#binderVerificationUsesTheJdkConstantTimeComparison
		String body = methodBody(source(), "private boolean constantTimeEquals(");

		assertTrue("the replay-register lookup must compare with MessageDigest.isEqual (FR-071, SI-5): " + body,
			body.contains("MessageDigest.isEqual("));
		assertFalse("Arrays.equals is not constant time: " + body, body.contains("Arrays.equals("));
		assertFalse("a hand-rolled comparison loop is not constant time: " + body, body.contains("for ("));
	}

	@Test
	public void theScanNeverExitsEarlyAndCoversTheNotFoundCase() throws IOException {
		String text = source();
		int start = text.indexOf("for (int probe = 0; probe < window; probe++) {");
		assertTrue("the window scan must stay a single flat loop so this check is possible", start >= 0);
		int end = text.indexOf("\n\t\t}", start);
		assertTrue(end > start);
		String scan = text.substring(start, end);

		assertTrue("the scan must accumulate with |= rather than short-circuit with ||: " + scan,
			scan.contains("|="));
		assertFalse("|| would stop the scan at the first hit: " + scan, scan.contains("||"));
		assertFalse("a return inside the scan makes a hit cheaper than a miss: " + scan, scan.contains("return"));
		assertFalse("a break inside the scan makes a hit cheaper than a miss: " + scan, scan.contains("break"));
		assertFalse("a continue makes an empty slot cheaper than an occupied one: " + scan,
			scan.contains("continue"));
		assertTrue("an empty or expired slot must still be compared, against a fixed-width decoy: " + scan,
			scan.contains("ABSENT"));
	}

	@Test
	public void thePublicSurfaceOffersNoWayToUnmarkARecord() {
		Set<String> methods = new TreeSet<>();
		for (Method method : QuicReplayGuard.class.getDeclaredMethods()) {
			if (!Modifier.isPublic(method.getModifiers()) || method.isSynthetic()) continue;
			methods.add(method.getName());
			assertNotEquals("no public accessor may hand out ticket-identity material (SI-6): " + method,
				byte[].class, method.getReturnType());
		}

		assertEquals(
			new TreeSet<>(Set.of("create", "tryConsume", "maxRecords", "records", "granted",
				"refusedReplayed", "refusedAtCapacity", "refusedExpired", "comparisons", "toString")),
			methods);

		Set<String> fields = new TreeSet<>();
		for (Field field : QuicReplayGuard.class.getFields()) {
			fields.add(field.getName());
			assertTrue(field + " must be a constant", Modifier.isStatic(field.getModifiers()) &&
				Modifier.isFinal(field.getModifiers()));
		}
		assertEquals(new TreeSet<>(Set.of("WINDOW", "DIGEST_LENGTH")), fields);
	}

	@Test
	public void toStringCarriesCountsOnly() {
		QuicReplayGuard guard = QuicReplayGuard.create(64);
		byte[] identity = identity(1);
		guard.tryConsume(ticketWithIdentity(identity), T0);

		String text = guard.toString();

		HexFormat hex = HexFormat.of();
		assertFalse(text, text.contains(hex.formatHex(identity)));
		assertFalse(text, text.contains(hex.formatHex(sha256(identity))));
		assertTrue(text, text.contains("1"));
		assertTrue(text, text.contains("64"));
	}

	// ---- fixtures ----

	private static String source() throws IOException {
		Path path = Path.of("src/main/java/io/activej/quic/tls/QuicReplayGuard.java");
		assertTrue("the source tree moved: " + path.toAbsolutePath(), Files.isRegularFile(path));
		return Files.readString(path, StandardCharsets.UTF_8);
	}

	private static String methodBody(String text, String declaration) {
		int start = text.indexOf(declaration);
		assertTrue(declaration + " must stay a separate named method so this check is possible", start >= 0);
		int end = text.indexOf("\n\t}", start);
		assertTrue(end > start);
		return text.substring(start, end);
	}

	private static byte[] sha256(byte[] bytes) {
		try {
			return MessageDigest.getInstance("SHA-256").digest(bytes);
		} catch (NoSuchAlgorithmException e) {
			throw new AssertionError(e);
		}
	}

	private static QuicSessionTicket.Builder base() {
		return QuicSessionTicket.builder("example.com", "h3", TlsCipherSuite.TLS_AES_128_GCM_SHA256,
			QuicSessionTicketTest.secret(32));
	}

	private static QuicSessionTicket sealable() {
		return base()
			.withIssuedAt(T0)
			.withLifetime(LIFETIME)
			.withTicketAgeAdd(0x0F0F0F0FL)
			.withTransportParameters(QuicSessionTicketTest.remembered())
			.build();
	}

	private static QuicSessionTicket ticket(int n) {
		return ticket(n, T0);
	}

	private static QuicSessionTicket ticket(int n, long issuedAtMillis) {
		return base()
			.withIdentity(identity(n))
			.withIssuedAt(issuedAtMillis)
			.withLifetime(LIFETIME)
			.withTicketAgeAdd(0)
			.withTransportParameters(QuicSessionTicketTest.remembered())
			.build();
	}

	private static QuicSessionTicket ticketWithIdentity(byte[] identity) {
		return base()
			.withIdentity(identity)
			.withIssuedAt(T0)
			.withLifetime(LIFETIME)
			.withTicketAgeAdd(0)
			.withTransportParameters(QuicSessionTicketTest.remembered())
			.build();
	}

	private static byte[] identity(int n) {
		byte[] bytes = new byte[64];
		for (int i = 0; i < bytes.length; i++) {
			bytes[i] = (byte) (n * 131 + i * 17);
		}
		return bytes;
	}
}
