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
import java.security.SecureRandom;
import java.time.Duration;

import static io.activej.quic.tls.ScriptedTlsServer.SERVER_PARAMS;
import static io.activej.quic.tls.TlsServerIdentityTest.fixture;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * T157 (FR-069, RFC 8446 §8) — {@code earlyDataEnabled} without a {@link QuicReplayGuard} is refused at
 * {@code build()}, not discovered at the first replay.
 * <p>
 * {@code TlsServerEngine.acceptEarlyData} short-circuits a null register and then installs the
 * RFC 9001 §4.1.4 0-RTT read keys regardless, so the pair used to produce a working, wire-conformant
 * server with <b>no replay protection at all</b> — and nothing in the compiler, the builder or the
 * tests said so. {@code Http3Server} is unaffected either way: it sets both switches in one breath.
 * <p>
 * Every other combination still builds, which is the half that makes this a guard rather than a
 * restriction: no early data is the default and needs no register, and a register on its own is
 * harmless.
 */
public final class TlsServerUnguardedEarlyDataTest {

	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	private static final long T0 = 1_700_000_000_000L;
	private static final long HOUR = 3_600_000L;

	@Test
	public void earlyDataWithoutAReplayGuardIsRefused() {
		TlsServerConfig.Builder builder = TlsServerConfig.builder(identity(), SERVER_PARAMS)
			.withTicketKeys(keys())
			.withEarlyDataEnabled(true);

		IllegalStateException e = assertThrows(IllegalStateException.class, builder::build);
		assertTrue("the message must name both settings, so the fix is greppable from one log line: " +
				e.getMessage(),
			e.getMessage().contains("earlyDataEnabled") && e.getMessage().contains("replayGuard"));
		assertTrue("and must say what it is refusing rather than only that it refused: " + e.getMessage(),
			e.getMessage().contains("RFC 8446 §8"));
	}

	@Test
	public void earlyDataWithoutAReplayGuardIsRefusedWithNoTicketKeysEither() {
		// the same pair with nothing else configured: it is early data that needs the register, not
		// resumption, so a config that could never resume must not slip through
		TlsServerConfig.Builder builder = TlsServerConfig.builder(identity(), SERVER_PARAMS)
			.withEarlyDataEnabled(true);

		assertThrows(IllegalStateException.class, builder::build);
	}

	@Test
	public void earlyDataWithAReplayGuardBuilds() {
		QuicReplayGuard guard = QuicReplayGuard.create(16);

		TlsServerConfig config = TlsServerConfig.builder(identity(), SERVER_PARAMS)
			.withTicketKeys(keys())
			.withEarlyDataEnabled(true)
			.withReplayGuard(guard)
			.build();

		assertTrue(config.earlyDataEnabled());
		assertSame(guard, config.replayGuard());
	}

	@Test
	public void theOrderTheTwoAreSetInDoesNotMatter() {
		QuicReplayGuard guard = QuicReplayGuard.create(16);

		TlsServerConfig config = TlsServerConfig.builder(identity(), SERVER_PARAMS)
			.withReplayGuard(guard)
			.withEarlyDataEnabled(true)
			.build();

		assertTrue(config.earlyDataEnabled());
		assertSame(guard, config.replayGuard());
	}

	@Test
	public void earlyDataTurnedBackOffNeedsNoRegister() {
		TlsServerConfig config = TlsServerConfig.builder(identity(), SERVER_PARAMS)
			.withEarlyDataEnabled(true)
			.withEarlyDataEnabled(false)
			.build();

		assertFalse(config.earlyDataEnabled());
		assertNull(config.replayGuard());
	}

	@Test
	public void thePhase1DefaultIsUntouched() {
		// SC-011: a server built the way phase 1 built one still builds, with neither switch set
		TlsServerConfig config = TlsServerConfig.builder(identity(), SERVER_PARAMS).build();

		assertFalse(config.earlyDataEnabled());
		assertNull(config.replayGuard());
		assertNull(config.ticketKeys());
	}

	@Test
	public void aResumingServerWithoutEarlyDataStillNeedsNoRegister() {
		QuicTicketKeys keys = keys();

		TlsServerConfig config = TlsServerConfig.builder(identity(), SERVER_PARAMS)
			.withTicketKeys(keys)
			.withSessionTicketLifetime(Duration.ofMinutes(10))
			.build();

		assertSame(keys, config.ticketKeys());
		assertFalse(config.earlyDataEnabled());
		assertNull(config.replayGuard());
		assertEquals(600_000L, config.sessionTicketLifetimeMillis());
	}

	@Test
	public void aRegisterWithoutEarlyDataIsStillLegal() {
		QuicReplayGuard guard = QuicReplayGuard.create(16);

		TlsServerConfig config = TlsServerConfig.builder(identity(), SERVER_PARAMS)
			.withReplayGuard(guard)
			.build();

		assertFalse(config.earlyDataEnabled());
		assertSame(guard, config.replayGuard());
	}

	// ---------------------------------------------------------------- fixtures

	private static QuicTicketKeys keys() {
		return QuicTicketKeys.create(new SecureRandom(), 6 * HOUR, HOUR, T0);
	}

	private static TlsServerIdentity identity() {
		try {
			return TlsServerIdentity.fromPem(fixture("ecdsa-cert.pem"), fixture("ecdsa-key.pem"));
		} catch (IOException e) {
			throw new AssertionError("The ECDSA fixture identity must load", e);
		}
	}
}
