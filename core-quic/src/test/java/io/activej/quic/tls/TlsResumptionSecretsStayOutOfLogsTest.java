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
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.read.ListAppender;
import io.activej.test.rules.ByteBufRule;
import org.junit.After;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static io.activej.quic.tls.TlsClientEngineTest.*;
import static org.junit.Assert.*;

/**
 * T066 / FR-050 / SI-6: no ticket, PSK, binder or key material reaches a log line, an exception
 * message or a {@code toString} on any resumption path.
 * <p>
 * Shaped after {@code QuicSecretsStayOutOfLogsTest} rather than {@code TlsSecretsHygieneTest}: a
 * capturing logback appender on {@code io.activej.quic.tls} forced to DEBUG (the suite's
 * {@code logback-test.xml} turns {@code io.activej} off, and the more specific logger wins), so this
 * catches what a value-only test cannot — a debug line that formats a secret it was handed.
 * <p>
 * Every needle is searched in both hex and base64, because a future diagnostic could plausibly
 * render either.
 */
public final class TlsResumptionSecretsStayOutOfLogsTest {

	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	private static final long NOW = 1_700_000_000_000L;
	private static final long TICKET_AGE_ADD = 0xDEADBEEFL;
	private static final byte[] RESUMPTION_SECRET = distinctiveBytes(32, 0x40);
	private static final byte[] IDENTITY = distinctiveBytes(48, 0x60);

	private Logger tlsLogger;
	private Level originalLevel;
	private ListAppender<ILoggingEvent> appender;
	private final Set<String> needles = new HashSet<>();
	private final List<Throwable> thrown = new ArrayList<>();

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

	@Test
	public void aResumedHandshakeWithEarlyDataLeaksNothing() throws Exception {
		TlsServerIdentity identity = rsaIdentity();
		ScriptedTlsServer server = resumingServer(identity);
		server.acceptEarlyData = true;
		TlsEngine client = resumingClient(identity, ticket(), true);

		addNeedle(RESUMPTION_SECRET);
		addNeedle(IDENTITY);
		collectBinderMaterial();

		completeHandshake(client, server);
		addNeedle(server.clientEarlyTraffic);
		addNeedle(server.resumptionMasterSecret);

		assertFalse("nothing was logged; the appender is not wired up", appender.list.isEmpty());
		assertNoNeedleAnywhere();
	}

	@Test
	public void anAcceptedTicketFlightLeaksNeitherItsBlobNorItsDerivedPsk() throws Exception {
		TlsServerIdentity identity = rsaIdentity();
		ScriptedTlsServer server = new ScriptedTlsServer(identity);
		InMemoryQuicSessionCache cache = InMemoryQuicSessionCache.create(256, () -> NOW);
		TlsEngine client = QuicTls.clientEngine(TlsClientConfig.builder("example.test", CLIENT_PARAMS)
			.withTrustedCertificate(identity.leaf())
			.withSessionCache(cache, 443)
			.withCurrentTimeMillis(() -> NOW)
			.build());
		completeHandshake(client, server);

		byte[] sealedBlob = distinctiveBytes(96, 0x20);
		addNeedle(sealedBlob);
		addNeedle(server.resumptionMasterSecret);

		TlsEngineResult result = client.consume(EncryptionLevel.ONE_RTT, wrap(server.newSessionTicket(
			3600, TICKET_AGE_ADD, new byte[] {7}, sealedBlob,
			List.of(EarlyDataExt.ofMaxEarlyDataSize(EarlyDataExt.QUIC_MAX_EARLY_DATA_SIZE)))));
		assertEquals(1, result.issuedTickets().size());
		addNeedle(result.issuedTickets().get(0).resumptionSecret());

		// the whole rendered surface of the stored ticket, not just the log
		QuicSessionTicket stored = cache.take("example.test", 443, ScriptedTlsServer.ALPN_H3);
		assertNotNull(stored);
		assertNoNeedleIn("QuicSessionTicket.toString()", stored.toString());
		assertNoNeedleIn("QuicSessionCache.toString()", cache.toString());
		assertNoNeedleAnywhere();

		// the obfuscation offset is what makes an obfuscated age unlinkable — it may not be logged either
		for (ILoggingEvent event : appender.list) {
			String text = renderedText(event).toLowerCase(Locale.ROOT);
			assertFalse("a log line leaked ticket_age_add: " + text, text.contains("deadbeef"));
			assertFalse("a log line leaked ticket_age_add: " + text, text.contains(Long.toString(TICKET_AGE_ADD)));
		}
	}

	@Test
	public void everyBoundedRejectionPathNamesTheConditionAndNoSecret() throws Exception {
		TlsServerIdentity identity = rsaIdentity();

		byte[] oversized = distinctiveBytes(8 * 1024 + 1, 0x11);
		addNeedle(oversized);
		capture(() -> {
			ScriptedTlsServer server = new ScriptedTlsServer(identity);
			TlsEngine client = ticketAcceptingClient(identity);
			completeHandshake(client, server);
			addNeedle(server.resumptionMasterSecret);
			client.consume(EncryptionLevel.ONE_RTT, wrap(server.newSessionTicket(
				3600, TICKET_AGE_ADD, new byte[] {1}, oversized, quicEarlyData())));
		});

		byte[] badSizeBlob = distinctiveBytes(80, 0x33);
		addNeedle(badSizeBlob);
		capture(() -> {
			ScriptedTlsServer server = new ScriptedTlsServer(identity);
			TlsEngine client = ticketAcceptingClient(identity);
			completeHandshake(client, server);
			client.consume(EncryptionLevel.ONE_RTT, wrap(server.newSessionTicket(
				3600, TICKET_AGE_ADD, new byte[] {1}, badSizeBlob,
				List.of(EarlyDataExt.ofMaxEarlyDataSize(0xFFFFFFFEL)))));
		});

		byte[] overCountBlob = distinctiveBytes(64, 0x55);
		addNeedle(overCountBlob);
		capture(() -> {
			ScriptedTlsServer server = new ScriptedTlsServer(identity);
			TlsEngine client = ticketAcceptingClient(identity);
			completeHandshake(client, server);
			for (int i = 0; i <= 8; i++) {
				client.consume(EncryptionLevel.ONE_RTT, wrap(server.newSessionTicket(
					3600, TICKET_AGE_ADD, new byte[] {(byte) i}, overCountBlob, quicEarlyData())));
			}
		});

		// a PSK the server declines: the offered ticket must not surface anywhere either
		addNeedle(RESUMPTION_SECRET);
		addNeedle(IDENTITY);
		collectBinderMaterial();
		capture(() -> {
			ScriptedTlsServer server = new ScriptedTlsServer(identity); // acceptPsk stays false
			TlsEngine client = resumingClient(identity, ticket(), true);
			completeHandshake(client, server);
		});

		assertEquals("every rejection path was expected to raise", 3, thrown.size());
		for (Throwable failure : thrown) {
			assertTrue("a bounded rejection must be a protocol violation, got " + failure,
				failure instanceof TlsProtocolViolationException);
			assertNotNull(failure.getMessage());
		}
		assertNoNeedleAnywhere();
	}

	// ---- helpers ----

	private void collectBinderMaterial() {
		TlsKeySchedule schedule = TlsKeySchedule.startWithPsk(TlsCipherSuite.TLS_AES_128_GCM_SHA256, RESUMPTION_SECRET);
		byte[] binderKey = schedule.resumptionBinderKey();
		addNeedle(binderKey);
		addNeedle(schedule.pskBinder(binderKey, new byte[32]));
		addNeedle(schedule.finishedKey(binderKey));
	}

	private void assertNoNeedleAnywhere() {
		for (ILoggingEvent event : appender.list) {
			assertNoNeedleIn("log line", renderedText(event));
		}
		for (Throwable failure : thrown) {
			assertNoNeedleIn("exception message", String.valueOf(failure.getMessage()));
			assertNoNeedleIn("exception toString", failure.toString());
		}
	}

	private void assertNoNeedleIn(String what, String text) {
		String lower = text.toLowerCase(Locale.ROOT);
		for (String needle : needles) {
			assertFalse(what + " leaked secret material: " + text, lower.contains(needle));
		}
	}

	private static String renderedText(ILoggingEvent event) {
		IThrowableProxy proxy = event.getThrowableProxy();
		return String.valueOf(event.getFormattedMessage()) + ' ' + (proxy == null ? "" : proxy.getMessage());
	}

	private void addNeedle(byte @org.jetbrains.annotations.Nullable [] secret) {
		if (secret == null || secret.length < 8) return;
		needles.add(HexFormat.of().formatHex(secret).toLowerCase(Locale.ROOT));
		needles.add(Base64.getEncoder().encodeToString(secret).toLowerCase(Locale.ROOT));
	}

	private void capture(ThrowingRunnable runnable) {
		try {
			runnable.run();
		} catch (Exception e) {
			thrown.add(e);
		}
	}

	private static QuicSessionTicket ticket() {
		return QuicSessionTicket.builder("example.test", "h3", TlsCipherSuite.TLS_AES_128_GCM_SHA256, RESUMPTION_SECRET)
			.withIdentity(IDENTITY)
			.withIssuedAt(NOW)
			.withLifetime(3_600_000L)
			.withTicketAgeAdd(TICKET_AGE_ADD)
			.withTransportParameters(ScriptedTlsServer.SERVER_PARAMS)
			.build();
	}

	private static ScriptedTlsServer resumingServer(TlsServerIdentity identity) {
		ScriptedTlsServer server = new ScriptedTlsServer(identity);
		server.acceptPsk = true;
		server.psk = RESUMPTION_SECRET;
		server.pskSuite = TlsCipherSuite.TLS_AES_128_GCM_SHA256;
		return server;
	}

	private static TlsEngine resumingClient(TlsServerIdentity identity, QuicSessionTicket ticket, boolean earlyData) {
		return QuicTls.clientEngine(TlsClientConfig.builder("example.test", CLIENT_PARAMS)
			.withTrustedCertificate(identity.leaf())
			.withSessionTicket(ticket)
			.withEarlyDataEnabled(earlyData)
			.withCurrentTimeMillis(() -> NOW)
			.build());
	}

	private static TlsEngine ticketAcceptingClient(TlsServerIdentity identity) {
		return QuicTls.clientEngine(TlsClientConfig.builder("example.test", CLIENT_PARAMS)
			.withTrustedCertificate(identity.leaf())
			.withCurrentTimeMillis(() -> NOW)
			.build());
	}

	private static List<TlsExtension> quicEarlyData() {
		return List.of(EarlyDataExt.ofMaxEarlyDataSize(EarlyDataExt.QUIC_MAX_EARLY_DATA_SIZE));
	}

	private static void completeHandshake(TlsEngine client, ScriptedTlsServer server) throws Exception {
		server.acceptClientHello(emitClientHello(client));
		recycleOutput(client.consume(EncryptionLevel.INITIAL, wrap(server.serverHelloBytes)));
		TlsEngineResult completion = client.consume(EncryptionLevel.HANDSHAKE, wrap(server.handshakeFlight(false, false)));
		try {
			assertTrue(completion.handshakeComplete());
			server.assertClientFinished(readBytes(completion.cryptoToSend().get(EncryptionLevel.HANDSHAKE)));
		} finally {
			recycleOutput(completion);
		}
	}

	/** Every byte distinct enough that a coincidental substring hit is not credible. */
	private static byte[] distinctiveBytes(int length, int seed) {
		byte[] bytes = new byte[length];
		for (int i = 0; i < length; i++) bytes[i] = (byte) (seed + i * 37);
		return bytes;
	}
}
