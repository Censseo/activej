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

package io.activej.quic.connection;

import io.activej.promise.Promise;
import io.activej.quic.connection.testutil.ManualEventloop;
import io.activej.quic.connection.testutil.QuicEndpointFixture;
import io.activej.quic.connection.testutil.QuicWirePair;
import io.activej.test.rules.ActivePromisesRule;
import io.activej.test.rules.ByteBufRule;
import io.activej.test.rules.EventloopRule;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/**
 * T049 / SC-003 — the feature's core claim: a handshake completes over a path that drops and reorders.
 * <p>
 * Fifty seeds at 20 % drop and 10 % reorder. Each runs on its own {@link ManualEventloop}, so the probe
 * timeouts that carry the handshake through the losses are reached by arithmetic rather than by waiting
 * — the whole suite is milliseconds regardless of the RFC 9002 timeouts involved.
 * <p>
 * A failure reports its seed, because a seeded {@code DatagramNetwork} reproduces byte-identically and a
 * failure without its seed is unreproducible noise.
 */
public final class QuicLossRecoveryTest {
	@ClassRule
	public static final EventloopRule eventloopRule = new EventloopRule();

	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	@Rule
	public final ActivePromisesRule activePromisesRule = new ActivePromisesRule();

	private static final int SEEDS = 50;
	private static final double DROP_RATE = 0.2;
	private static final double REORDER_RATE = 0.1;

	/** One-way delay, so a datagram is never delivered in the same instant it is sent. */
	private static final long HOP_MILLIS = 5;

	/**
	 * How long each seed is given, in fixture time. A handshake needs four flights; at 20 % loss the
	 * tail is carried by probe timeouts, which double each time — a few seconds of fixture time covers
	 * several rounds of that.
	 */
	private static final long BUDGET_MILLIS = 20_000;

	/** The step the clock advances by. Small enough to land inside a probe timeout, not on top of one. */
	private static final long STEP_MILLIS = 25;

	private record Outcome(boolean established, long probesSent, long packetsLost, long probeRetransmits, String detail) {
		/** Either recovery path having fired: a declared loss, or a probe timeout re-sending its data. */
		long retransmitted() {
			return packetsLost + probeRetransmits;
		}
	}

	/**
	 * Runs one seed to completion or to its budget.
	 * <p>
	 * The settings deliberately keep the idle timeout well above the budget: this test is about loss
	 * recovery, and a connection that idled out mid-recovery would report a failure that has nothing to
	 * do with loss.
	 */
	private static Outcome runSeed(long seed) throws Exception {
		try (ManualEventloop loop = new ManualEventloop(1_000_000 + seed * 1_000_000);
			 QuicEndpointFixture fixture = new QuicEndpointFixture(loop, seed)) {
			fixture.network()
				.withDropRate(DROP_RATE)
				.withReorderRate(REORDER_RATE)
				.withDelay(HOP_MILLIS);

			QuicConnectionSettings settings = QuicConnectionSettings.builder()
				.withMaxIdleTimeout(Duration.ofMillis(BUDGET_MILLIS * 4))
				.withHandshakeTimeout(Duration.ofMillis(BUDGET_MILLIS * 4))
				.build();
			QuicEndpoint server = fixture.server(settings);
			QuicEndpoint client = fixture.client(settings);
			Promise<QuicConnection> connecting = client.connectTo(
				QuicEndpointFixture.SERVER_ADDRESS, QuicEndpointFixture.clientEngineFactory());

			for (long elapsed = 0; elapsed < BUDGET_MILLIS && !connecting.isComplete(); elapsed += STEP_MILLIS) {
				fixture.advance(STEP_MILLIS);
			}

			if (connecting.isException()) {
				return new Outcome(false, 0, 0, 0, "failed: " + connecting.getException());
			}
			if (!connecting.isComplete()) {
				return new Outcome(false, 0, 0, 0, "did not complete within " + BUDGET_MILLIS + " ms of fixture time");
			}
			QuicConnection connection = connecting.getResult();
			Outcome outcome = new Outcome(true, connection.probesSent(), connection.packetsLost(),
				connection.probeRetransmits(),
				"established after " + connection.datagramsSent() + " datagrams sent, " +
				connection.probesSent() + " probes, " + connection.packetsLost() + " packets lost, " +
				connection.probeRetransmits() + " probe retransmits");
			// Closed inside the fixture's lifetime so the closing period cannot outlive the assertions.
			connection.closeNow();
			server.close();
			client.close();
			return outcome;
		}
	}

	@Test
	public void theHandshakeCompletesOnALossyReorderingPathAcrossFiftySeeds() throws Exception {
		List<String> failures = new ArrayList<>();
		int recoveredByProbe = 0;
		int recoveredByThreshold = 0;

		for (long seed = 1; seed <= SEEDS; seed++) {
			Outcome outcome = runSeed(seed);
			if (!outcome.established()) {
				failures.add("seed " + seed + ": " + outcome.detail());
				continue;
			}
			if (outcome.probesSent() > 0) recoveredByProbe++;
			if (outcome.retransmitted() > 0) recoveredByThreshold++;
		}

		assertEquals("reproduce with the reported seed — DatagramNetwork is deterministic per seed",
			List.of(), failures);
		// If neither recovery mechanism ever fired, the harness dropped nothing and the test proved
		// nothing. At 20 % this is a near-certainty over 50 seeds, and asserting it is what stops the
		// test from silently degrading into a no-loss handshake test.
		assertTrue("no seed exercised loss recovery at all — is the drop rate wired up?",
			recoveredByProbe + recoveredByThreshold > 0);
	}

	@Test
	public void aSeedThatActuallyRetransmittedStillCompletes() throws Exception {
		// FR-009: a retransmission travels in a *new* packet with a new number — reuse would repeat an
		// AEAD nonce (RFC 9001 §5.3), a cryptographic failure rather than merely a protocol one. That
		// guarantee is structural: PacketNumberSpace has no way to rewind and no way to be handed a
		// number, which PacketNumberSpaceTest asserts directly. What this test adds is that the paths
		// which *do* retransmit are exercised end to end rather than only in isolation.
		Outcome retransmitting = null;
		for (long seed = 1; seed <= SEEDS && retransmitting == null; seed++) {
			Outcome outcome = runSeed(seed);
			if (outcome.established() && outcome.retransmitted() > 0) {
				retransmitting = outcome;
			}
		}
		assertNotNull("no seed retransmitted anything — is the drop rate wired up?", retransmitting);
		assertTrue(retransmitting.established());
	}

	/**
	 * T066 / US5 scenario 7 — bytes in flight never exceed the congestion window across every seed.
	 * <p>
	 * Checked at every step rather than only at the end: the interesting violation is transient, a single
	 * flush that built one datagram too many, and by the time the handshake completes the evidence is
	 * gone.
	 */
	@Test
	public void bytesInFlightNeverExceedTheCongestionWindow() throws Exception {
		List<String> violations = new ArrayList<>();
		for (long seed = 1; seed <= 10; seed++) {
			try (ManualEventloop loop = new ManualEventloop(1_000_000 + seed * 1_000_000);
				 QuicEndpointFixture fixture = new QuicEndpointFixture(loop, seed)) {
				fixture.network().withDropRate(DROP_RATE).withReorderRate(REORDER_RATE).withDelay(HOP_MILLIS);
				QuicConnectionSettings settings = QuicConnectionSettings.builder()
					.withMaxIdleTimeout(Duration.ofMillis(BUDGET_MILLIS * 4))
					.withHandshakeTimeout(Duration.ofMillis(BUDGET_MILLIS * 4))
					.build();
				fixture.server(settings);
				QuicEndpoint client = fixture.client(settings);
				Promise<QuicConnection> connecting = client.connectTo(
					QuicEndpointFixture.SERVER_ADDRESS, QuicEndpointFixture.clientEngineFactory());

				QuicConnection connection = null;
				for (long elapsed = 0; elapsed < BUDGET_MILLIS; elapsed += STEP_MILLIS) {
					fixture.advance(STEP_MILLIS);
					if (connection == null && connecting.isResult()) {
						connection = connecting.getResult();
					}
					if (connection == null) continue;
					NewRenoCongestionController cc = connection.congestion();
					// A probe is exempt by RFC 9002 §7, so the bound checked here is the window plus the one
					// datagram a probe may add — asserting the window alone would fail on correct behaviour.
					long ceiling = cc.congestionWindow() + settings.maxDatagramSize();
					if (cc.bytesInFlight() > ceiling) {
						violations.add("seed " + seed + ": " + cc.bytesInFlight() + " bytes in flight over a " +
							cc.congestionWindow() + "-byte window");
						break;
					}
					if (connecting.isComplete()) break;
				}
				if (connection != null) connection.closeNow();
			}
		}
		assertEquals(List.of(), violations);
	}

	/**
	 * A probe timeout re-sends data; it must not also leak the bytes of what it re-sent.
	 * <p>
	 * {@code requeueOldestUnacknowledged} removes a packet from its space, so nothing will ever
	 * acknowledge that record and nothing else can subtract its bytes. Skipping the subtraction leaves
	 * {@code bytesInFlight} counting a packet that no longer exists, once per probe — after a black-holed
	 * interval the window reads as permanently full, and the connection stays blocked even after the path
	 * comes back. The other three removal paths already do this; this one is the outlier.
	 */
	@Test
	public void probeTimeoutsDoNotAccumulateBytesInFlight() throws Exception {
		try (ManualEventloop loop = new ManualEventloop();
			 QuicWirePair wire = new QuicWirePair()) {
			wire.startClient(QuicConnectionSettings.builder()
				.withMaxIdleTimeout(Duration.ofMinutes(10))
				.withHandshakeTimeout(Duration.ofMinutes(10))
				.build());
			QuicConnection client = wire.client();
			NewRenoCongestionController cc = client.congestion();
			int datagramSize = QuicConnectionSettings.create().maxDatagramSize();

			// The path vanishes after the client's first Initial. That Initial stays in flight and is never
			// acknowledged, which is exactly the state a probe timeout exists for.
			wire.clientWire().drain();
			wire.clientWire().blackhole(true);
			long inFlightBefore = cc.bytesInFlight();
			assertTrue("nothing was in flight to probe for", inFlightBefore > 0);

			// The probe timeout doubles each time (RFC 9002 §6.2.1), so six of them span about a minute of
			// fixture time — reached by arithmetic, not by waiting.
			for (long elapsed = 0; elapsed < 300_000 && client.probesSent() < 6; elapsed += 500) {
				loop.advance(500);
			}
			assertTrue("the client stopped probing after " + client.probesSent(), client.probesSent() >= 6);
			assertEquals("every probe should have re-sent data rather than a bare PING",
				client.probesSent(), client.probeRetransmits());

			// Each probe removes one packet and sends one in its place, so the figure stays flat — one
			// datagram of slack covers the PING a probe may add alongside. Left unsubtracted it climbs by a
			// packet per probe and never comes down, because nothing will ever acknowledge a record that
			// was removed.
			assertTrue("bytes in flight grew to " + cc.bytesInFlight() + " over " + client.probesSent() +
					" probes, from " + inFlightBefore,
				cc.bytesInFlight() <= inFlightBefore + datagramSize);
			client.closeNow();
		}
	}

	@Test
	public void aCleanPathNeedsNoProbesAtAll() throws Exception {
		try (ManualEventloop loop = new ManualEventloop();
			 QuicEndpointFixture fixture = new QuicEndpointFixture(loop, 7)) {
			fixture.network().withDelay(HOP_MILLIS);
			QuicConnectionSettings settings = QuicConnectionSettings.create();
			fixture.server(settings);
			QuicEndpoint client = fixture.client(settings);

			Promise<QuicConnection> connecting = client.connectTo(
				QuicEndpointFixture.SERVER_ADDRESS, QuicEndpointFixture.clientEngineFactory());
			for (int i = 0; i < 40 && !connecting.isComplete(); i++) {
				fixture.advance(STEP_MILLIS);
			}

			assertTrue("the handshake failed on a clean path: " + connecting, connecting.isResult());
			QuicConnection connection = connecting.getResult();
			// The baseline the lossy runs are measured against: no loss, so nothing to recover from.
			assertEquals(0, connection.packetsLost());
			assertEquals(0, connection.probesSent());
			connection.closeNow();
		}
	}
}
