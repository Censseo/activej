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

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.activej.bytebuf.ByteBuf;
import io.activej.test.rules.ByteBufRule;
import org.jetbrains.annotations.Nullable;
import org.junit.After;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;
import org.slf4j.LoggerFactory;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.function.LongSupplier;

import static io.activej.quic.tls.ScriptedResumptionClient.*;
import static io.activej.quic.tls.ScriptedResumptionClient.BinderScope.TRUNCATED;
import static io.activej.quic.tls.TlsServerIdentityTest.fixture;
import static org.junit.Assert.*;

/**
 * T107 / T099's engine half — {@link TlsServerEngine} <b>consults</b> {@link QuicReplayGuard} before it
 * accepts early data (spec FR-069, RFC 8446 §8). {@code QuicReplayGuardTest} proves the register
 * itself; nothing there proves anything reaches it.
 * <p>
 * The property under test has two halves and both matter:
 * <ol>
 *   <li>a ticket identity is granted early data <b>once</b>, however many connections present it, and</li>
 *   <li>a refusal costs the <i>early data</i> only — the pre-shared key is still accepted, the
 *       certificate flight is still skipped, and the handshake still completes. A replay degrades a
 *       connection; it never breaks one. This is the same graceful shape an unopenable or expired
 *       ticket already had, with "already used for early data" added to the list of reasons early data
 *       specifically does not happen.</li>
 * </ol>
 * The register is consulted at the <b>acceptance</b> point rather than at PSK selection, which is
 * observable rather than asserted by reading the code: a resumption that offers no early data, and a
 * resumption to a server with early data disabled, both leave the register untouched, so the ticket is
 * still grantable afterwards.
 * <p>
 * One register is shared by every engine here, because that is the only arrangement that catches
 * anything: a replayed flight arrives on a <i>new</i> connection by construction.
 */
public final class TlsServerEarlyDataReplayTest {

	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	private static final long T0 = 1_700_000_000_000L;
	private static final long HOUR = 3_600_000L;
	private static final long TOLERANCE_MILLIS = 10_000L;

	private Logger tlsLogger;
	private @Nullable Level originalLevel;
	private ListAppender<ILoggingEvent> appender;

	@Before
	public void setUp() {
		tlsLogger = (Logger) LoggerFactory.getLogger("io.activej.quic.tls");
		originalLevel = tlsLogger.getLevel();
		tlsLogger.setLevel(Level.DEBUG);
		appender = new ListAppender<>();
		appender.start();
		tlsLogger.addAppender(appender);
	}

	@After
	public void tearDown() {
		tlsLogger.detachAppender(appender);
		tlsLogger.setLevel(originalLevel);
	}

	// ---------------------------------------------------------------- the property

	@Test
	public void aSecondPresentationOfTheSameTicketIsRefusedEarlyDataAndStillCompletesTheHandshake() throws Exception {
		QuicTicketKeys keys = newKeys();
		QuicReplayGuard guard = QuicReplayGuard.create(16);
		QuicSessionTicket ticket = issueTicket(keys);

		Resumption first = resumeWithEarlyData(keys, guard, ticket);
		assertTrue("a ticket presented for the first time must be granted early data", first.earlyDataAccepted());
		assertTrue(first.resumed());
		assertTrue(first.earlyDataEchoed());
		assertTrue(first.zeroRttKeysInstalled());
		assertTrue("a refusal must not cost the handshake either way", first.handshakeCompleted());

		Resumption replay = resumeWithEarlyData(keys, guard, ticket);
		assertFalse("the same ticket identity must never buy early data twice", replay.earlyDataAccepted());
		assertFalse("refusing early data is an omission, never a negative extension", replay.earlyDataEchoed());
		assertFalse("no 0-RTT read key may be installed for a refused flight", replay.zeroRttKeysInstalled());

		assertTrue("a replay must still resume: the register refuses early data, not the PSK", replay.resumed());
		assertTrue("... and must still authenticate without a certificate flight (RFC 8446 §4.4.2)",
			replay.pskAuthenticatedFlight());
		assertTrue("... and must still complete. A replay degrades a connection, it does not break one",
			replay.handshakeCompleted());

		assertEquals(1, guard.granted());
		assertEquals(1, guard.refusedReplayed());
		assertEquals(0, guard.refusedAtCapacity());
		assertEquals(0, guard.refusedExpired());
	}

	/**
	 * The literal RFC 8446 §8 threat: the recorded ClientHello replayed byte for byte, binder included.
	 * The engine cannot tell it from the original — which is the point, and why the answer has to come
	 * from a register rather than from anything in the message.
	 */
	@Test
	public void aByteIdenticalReplayOfTheRecordedClientHelloIsRefusedEarlyData() throws Exception {
		QuicTicketKeys keys = newKeys();
		QuicReplayGuard guard = QuicReplayGuard.create(16);
		QuicSessionTicket ticket = issueTicket(keys);

		ScriptedResumptionClient client = new ScriptedResumptionClient();
		byte[] recorded = client.resumingClientHello(
			earlyDataExtensions(client), ticket, 0, TRUNCATED, 0, false);

		Flight granted = consume(server(keys, guard, true, () -> T0), recorded);
		assertTrue(granted.earlyDataAccepted);
		assertTrue(granted.earlyDataEchoed());

		Flight replayed = consume(server(keys, guard, true, () -> T0), recorded.clone());
		assertFalse(replayed.earlyDataAccepted);
		assertFalse(replayed.earlyDataEchoed());
		assertTrue("the replayed flight must still be answered with a resumed handshake", replayed.resumed);
		assertEquals("... which is EE + Finished, with no certificate", 2, parseAll(replayed.handshake).size());
	}

	/**
	 * The whole of {@code maxEarlyDataReplayRecords} reached through the engine (spec FR-070). A
	 * register with no room refuses the <i>new</i> presentation — it never makes space by forgetting a
	 * record it could then be replayed with.
	 */
	@Test
	public void atCapacityTheEngineRefusesEarlyDataRatherThanAdmittingIt() throws Exception {
		QuicTicketKeys keys = newKeys();
		QuicReplayGuard guard = QuicReplayGuard.create(1);
		QuicSessionTicket first = issueTicket(keys);
		QuicSessionTicket second = issueTicket(keys);
		assertFalse("the two tickets must be distinct identities",
			java.util.Arrays.equals(first.identity(), second.identity()));

		assertTrue(resumeWithEarlyData(keys, guard, first).earlyDataAccepted());

		Resumption refused = resumeWithEarlyData(keys, guard, second);
		assertFalse("a full register must refuse a new grant", refused.earlyDataAccepted());
		assertTrue("... and still complete the handshake", refused.handshakeCompleted());
		assertEquals(1, guard.refusedAtCapacity());

		assertFalse("the record already held must not have been surrendered to make room",
			resumeWithEarlyData(keys, guard, first).earlyDataAccepted());
		assertEquals(1, guard.refusedReplayed());
	}

	/** Every ticket of one handshake is its own single use — they are separate identities. */
	@Test
	public void eachTicketIssuedByOneHandshakeIsIndependentlySingleUse() throws Exception {
		QuicTicketKeys keys = newKeys();
		QuicReplayGuard guard = QuicReplayGuard.create(16);
		List<QuicSessionTicket> tickets = issueTickets(keys);
		assertEquals(2, tickets.size());

		for (QuicSessionTicket ticket : tickets) {
			assertTrue(resumeWithEarlyData(keys, guard, ticket).earlyDataAccepted());
		}
		for (QuicSessionTicket ticket : tickets) {
			assertFalse(resumeWithEarlyData(keys, guard, ticket).earlyDataAccepted());
		}

		assertEquals(2, guard.granted());
		assertEquals(2, guard.refusedReplayed());
	}

	// ---------------------------------------------------------------- where the consultation sits

	@Test
	public void aResumptionThatOffersNoEarlyDataLeavesTheRegisterUntouched() throws Exception {
		QuicTicketKeys keys = newKeys();
		QuicReplayGuard guard = QuicReplayGuard.create(16);
		QuicSessionTicket ticket = issueTicket(keys);

		ScriptedResumptionClient client = new ScriptedResumptionClient();
		Flight flight = consume(server(keys, guard, true, () -> T0),
			client.resumingClientHello(client.defaultExtensions(), ticket, 0, TRUNCATED, 0, false));
		assertTrue(flight.resumed);
		assertFalse(flight.earlyDataAccepted);

		assertEquals("a plain resumption must not spend the ticket's one early-data use", 0, guard.granted());
		assertTrue("... which is exactly what 'spending it' would mean here",
			resumeWithEarlyData(keys, guard, ticket).earlyDataAccepted());
	}

	@Test
	public void aServerWithEarlyDataDisabledNeverTouchesTheRegister() throws Exception {
		QuicTicketKeys keys = newKeys();
		QuicReplayGuard guard = QuicReplayGuard.create(16);
		QuicSessionTicket ticket = issueTicket(keys);

		ScriptedResumptionClient client = new ScriptedResumptionClient();
		Flight flight = consume(server(keys, guard, false, () -> T0),
			client.resumingClientHello(earlyDataExtensions(client), ticket, 0, TRUNCATED, 0, false));
		assertTrue(flight.resumed);
		assertFalse("earlyDataEnabled is off, so there is nothing to grant", flight.earlyDataAccepted);

		assertEquals(0, guard.granted());
		assertEquals(0, guard.refusedReplayed());
		assertTrue(resumeWithEarlyData(keys, guard, ticket).earlyDataAccepted());
	}

	/**
	 * The register must be judged at the <b>engine's</b> instant, not at a clock of its own: the
	 * injected clock here sits well in the past, so a guard handed {@code System.currentTimeMillis()}
	 * would find every ticket long expired and refuse a first presentation.
	 */
	@Test
	public void theRegisterIsJudgedAtTheSameInstantTheRestOfTheResumptionDecisionIs() throws Exception {
		QuicTicketKeys keys = newKeys();
		QuicReplayGuard guard = QuicReplayGuard.create(16);
		QuicSessionTicket ticket = issueTicket(keys);
		assertTrue("the fixture clock must be behind the wall clock for this to test anything",
			T0 + ticket.lifetimeMillis() < System.currentTimeMillis());

		assertTrue(resumeWithEarlyData(keys, guard, ticket).earlyDataAccepted());

		assertEquals(1, guard.granted());
		assertEquals("the register read a clock of its own", 0, guard.refusedExpired());
	}

	// ---------------------------------------------------------------- the no-register configuration

	/**
	 * T157: a configuration without a register used to be legal and to keep the phase-5 behaviour —
	 * <b>no replay protection at all</b>, every presentation granted. It is now refused at
	 * {@code build()}, so the only way to reach this method's subject is with a register in hand.
	 * {@code Http3Server} was never able to produce the old configuration; a direct {@code core-quic}
	 * consumer was.
	 */
	@Test
	public void withoutARegisterTheConfigurationIsRefused() throws Exception {
		QuicTicketKeys keys = newKeys();

		assertNull("a config that admits no early data still needs no register",
			TlsServerConfig.builder(rsaIdentity(), SERVER_PARAMS).build().replayGuard());
		assertThrows(IllegalStateException.class, () -> server(keys, null, true, () -> T0));
	}

	// ---------------------------------------------------------------- SI-6

	@Test
	public void aReplayRefusalNamesTheConditionAndNoTicketMaterial() throws Exception {
		QuicTicketKeys keys = newKeys();
		QuicReplayGuard guard = QuicReplayGuard.create(16);
		QuicSessionTicket ticket = issueTicket(keys);

		resumeWithEarlyData(keys, guard, ticket);
		appender.list.clear();
		assertFalse(resumeWithEarlyData(keys, guard, ticket).earlyDataAccepted());

		assertFalse("nothing was logged; the appender is not wired up", appender.list.isEmpty());
		String logged = String.valueOf(appender.list);
		for (byte[] material : List.of(ticket.identity(), ticket.resumptionSecret())) {
			assertFalse(logged, logged.contains(HexFormat.of().formatHex(material)));
			assertFalse(logged, logged.contains(HexFormat.of().withUpperCase().formatHex(material)));
			assertFalse(logged, logged.contains(Base64.getEncoder().encodeToString(material)));
		}
		assertFalse("a register's contents are not for printing either", logged.contains(guard.toString()));
	}

	// ---------------------------------------------------------------- the seam

	@Test
	public void theRegisterTravelsOnTheServerConfiguration() throws Exception {
		QuicReplayGuard guard = QuicReplayGuard.create(16);

		TlsServerConfig config = TlsServerConfig.builder(rsaIdentity(), SERVER_PARAMS)
			.withReplayGuard(guard)
			.build();

		assertSame(guard, config.replayGuard());
	}

	@Test
	public void theRegisterSetterIsRefusedAfterBuildAndRefusesNull() throws Exception {
		TlsServerConfig.Builder builder = TlsServerConfig.builder(rsaIdentity(), SERVER_PARAMS);
		assertThrows(NullPointerException.class, () -> builder.withReplayGuard(null));
		builder.build();
		assertThrows(IllegalStateException.class, () -> builder.withReplayGuard(QuicReplayGuard.create(16)));
	}

	// ---------------------------------------------------------------- harness

	/** One server flight, plus the two answers the caller reads off it. */
	private record Flight(byte[] initial, byte[] handshake, boolean earlyDataAccepted, boolean resumed,
			boolean zeroRttKeysInstalled) {

		boolean earlyDataEchoed() throws Exception {
			EncryptedExtensionsMessage encryptedExtensions = (EncryptedExtensionsMessage) parseAll(handshake).get(0);
			return find(encryptedExtensions.extensions, EarlyDataExt.class) != null;
		}
	}

	/** A whole resumption attempt: the server flight, and whether the client Finished then completed it. */
	private record Resumption(Flight flight, boolean handshakeCompleted) {

		boolean earlyDataAccepted() {
			return flight.earlyDataAccepted;
		}

		boolean resumed() {
			return flight.resumed;
		}

		boolean zeroRttKeysInstalled() {
			return flight.zeroRttKeysInstalled;
		}

		boolean earlyDataEchoed() throws Exception {
			return flight.earlyDataEchoed();
		}

		boolean pskAuthenticatedFlight() throws Exception {
			List<TlsHandshakeMessage> messages = parseAll(flight.handshake);
			return messages.size() == 2
				&& messages.get(0) instanceof EncryptedExtensionsMessage
				&& messages.get(1) instanceof FinishedMessage;
		}
	}

	private static List<TlsExtension> earlyDataExtensions(ScriptedResumptionClient client) {
		List<TlsExtension> extensions = new ArrayList<>(client.defaultExtensions());
		extensions.add(EarlyDataExt.empty());
		return extensions;
	}

	/** A fresh connection offering {@code ticket} with {@code early_data}, driven to completion. */
	private static Resumption resumeWithEarlyData(QuicTicketKeys keys, @Nullable QuicReplayGuard guard,
			QuicSessionTicket ticket) throws Exception {
		TlsEngine engine = server(keys, guard, true, () -> T0);
		ScriptedResumptionClient client = new ScriptedResumptionClient();
		byte[] clientHello = client.resumingClientHello(
			earlyDataExtensions(client), ticket, 0, TRUNCATED, 0, false);

		Flight flight;
		TlsEngineResult serverFlight = engine.consume(EncryptionLevel.INITIAL, wrap(clientHello));
		try {
			flight = read(serverFlight);
			client.acceptServerFlight(serverFlight, null);
		} finally {
			recycleOutput(serverFlight);
		}

		TlsEngineResult completion = engine.consume(EncryptionLevel.HANDSHAKE, wrap(client.clientFinished()));
		try {
			return new Resumption(flight, completion.handshakeComplete());
		} finally {
			recycleOutput(completion);
		}
	}

	private static Flight consume(TlsEngine engine, byte[] clientHello) throws Exception {
		TlsEngineResult result = engine.consume(EncryptionLevel.INITIAL, wrap(clientHello));
		try {
			return read(result);
		} finally {
			recycleOutput(result);
		}
	}

	private static Flight read(TlsEngineResult result) {
		ByteBuf initial = result.cryptoToSend().get(EncryptionLevel.INITIAL);
		ByteBuf handshake = result.cryptoToSend().get(EncryptionLevel.HANDSHAKE);
		assertNotNull(initial);
		assertNotNull(handshake);
		boolean zeroRtt = result.keysToInstall().stream()
			.anyMatch(installation -> installation.level() == EncryptionLevel.ZERO_RTT);
		// read() leaves the buffers empty; the caller still recycles them
		return new Flight(peek(initial), peek(handshake), result.earlyDataAccepted(), result.resumed(), zeroRtt);
	}

	private static byte[] peek(ByteBuf buf) {
		byte[] bytes = new byte[buf.readRemaining()];
		System.arraycopy(buf.array(), buf.head(), bytes, 0, bytes.length);
		return bytes;
	}

	private static TlsEngine server(QuicTicketKeys keys, @Nullable QuicReplayGuard guard, boolean earlyData,
			LongSupplier clock) throws Exception {
		TlsServerConfig.Builder builder = TlsServerConfig.builder(rsaIdentity(), SERVER_PARAMS)
			.withTicketKeys(keys)
			.withSessionTicketsPerHandshake(2)
			.withTicketAgeTolerance(Duration.ofMillis(TOLERANCE_MILLIS))
			.withEarlyDataEnabled(earlyData)
			.withCurrentTimeMillis(clock);
		if (guard != null) builder.withReplayGuard(guard);
		return QuicTls.serverEngine(builder.build());
	}

	private static QuicTicketKeys newKeys() {
		return QuicTicketKeys.create(new SecureRandom(), 6 * HOUR, HOUR, T0);
	}

	private static QuicSessionTicket issueTicket(QuicTicketKeys keys) throws Exception {
		return issueTickets(keys).get(0);
	}

	/**
	 * A full handshake against a ticket-issuing server, opening every ticket it sealed. Nothing resumes
	 * here, so this server's own register is never consulted — it is present because a server that
	 * accepts early data may not be built without one.
	 */
	private static List<QuicSessionTicket> issueTickets(QuicTicketKeys keys) throws Exception {
		TlsServerIdentity identity = rsaIdentity();
		TlsEngine engine = server(keys, QuicReplayGuard.create(16), true, () -> T0);
		ScriptedResumptionClient client = new ScriptedResumptionClient();

		TlsEngineResult serverFlight = engine.consume(
			EncryptionLevel.INITIAL, wrap(client.clientHello(client.defaultExtensions())));
		try {
			client.acceptServerFlight(serverFlight, identity.leaf());
		} finally {
			recycleOutput(serverFlight);
		}

		TlsEngineResult completion = engine.consume(EncryptionLevel.HANDSHAKE, wrap(client.clientFinished()));
		byte[] oneRtt;
		try {
			ByteBuf buf = completion.cryptoToSend().get(EncryptionLevel.ONE_RTT);
			assertNotNull(buf);
			oneRtt = readBytes(buf);
		} finally {
			recycleOutput(completion);
		}

		List<QuicSessionTicket> tickets = new ArrayList<>();
		for (TlsHandshakeMessage message : parseAll(oneRtt)) {
			QuicSessionTicket ticket = keys.open(((NewSessionTicketMessage) message).ticket());
			assertNotNull(ticket);
			tickets.add(ticket);
		}
		return tickets;
	}

	private static @Nullable TlsServerIdentity cachedIdentity;

	private static TlsServerIdentity rsaIdentity() throws Exception {
		if (cachedIdentity == null) {
			cachedIdentity = TlsServerIdentity.fromPem(fixture("rsa-cert.pem"), fixture("rsa-key.pem"));
		}
		return cachedIdentity;
	}
}
