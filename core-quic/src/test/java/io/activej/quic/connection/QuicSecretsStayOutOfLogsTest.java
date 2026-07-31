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

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.read.ListAppender;
import io.activej.quic.connection.testutil.ManualEventloop;
import io.activej.quic.connection.testutil.QuicWirePair;
import io.activej.quic.crypto.QuicKeys;
import io.activej.quic.tls.EncryptionLevel;
import io.activej.test.rules.ByteBufRule;
import io.activej.test.rules.EventloopRule;
import org.junit.After;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.HexFormat;
import java.util.Set;

import static org.junit.Assert.*;

/**
 * QA gap 6.6 (T076, SI-6) — no test previously proved secrets stay out of logs; the property held only
 * by inspection. This drives a full handshake at DEBUG with a capturing appender on the whole
 * {@code io.activej.quic} subtree, and asserts that no captured line's formatted message or exception
 * text contains the hex encoding of any key, IV or header-protection key actually installed on either
 * side, at any encryption level, at any point before it would have been discarded.
 * <p>
 * Keys are sampled after every single datagram delivery, not just at the end: {@code Initial} and
 * {@code Handshake} level keys are discarded mid-handshake (RFC 9001 §4.9), so a snapshot taken only
 * after the exchange settles would silently miss exactly the material this test exists to catch.
 */
public final class QuicSecretsStayOutOfLogsTest {
	@ClassRule
	public static final EventloopRule eventloopRule = new EventloopRule();

	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	private ManualEventloop loop;
	private QuicWirePair wire;
	private Logger quicLogger;
	private Level originalLevel;
	private ListAppender<ILoggingEvent> appender;

	@Before
	public void setUp() {
		loop = new ManualEventloop();
		wire = new QuicWirePair();

		quicLogger = (Logger) LoggerFactory.getLogger("io.activej.quic");
		originalLevel = quicLogger.getLevel();
		// test/logback-test.xml sets io.activej to "off"; this override on the more specific logger wins
		// for the whole io.activej.quic.* subtree (logback resolves effective level by nearest ancestor).
		quicLogger.setLevel(Level.DEBUG);
		appender = new ListAppender<>();
		appender.start();
		quicLogger.addAppender(appender);
	}

	@After
	public void tearDown() {
		quicLogger.detachAppender(appender);
		quicLogger.setLevel(originalLevel);
		wire.close();
		loop.close();
	}

	private static void collectKeyMaterial(QuicConnection connection, Set<String> hexNeedles) {
		for (EncryptionLevel level : EncryptionLevel.values()) {
			QuicKeys keys = connection.sendKeysForTesting(level);
			if (keys == null) continue;
			addIfLongEnoughToBeUnambiguous(hexNeedles, keys.aeadKeyBytes());
			addIfLongEnoughToBeUnambiguous(hexNeedles, keys.iv());
			addIfLongEnoughToBeUnambiguous(hexNeedles, keys.headerProtectionKey());
		}
	}

	/** Short byte strings (a handful of bytes) risk coincidental substring hits; every real key/iv/hp key is well above this. */
	private static void addIfLongEnoughToBeUnambiguous(Set<String> hexNeedles, byte[] bytes) {
		if (bytes.length < 8) return;
		hexNeedles.add(HexFormat.of().formatHex(bytes));
	}

	@Test
	public void noHandshakeLogLineContainsAnyInstalledKeyIvOrHeaderProtectionKey() throws Exception {
		Set<String> hexNeedles = new HashSet<>();

		wire.startClient(QuicConnectionSettings.create());
		collectKeyMaterial(wire.client(), hexNeedles);

		wire.acceptServer(QuicConnectionSettings.create());
		collectKeyMaterial(wire.client(), hexNeedles);
		collectKeyMaterial(wire.server(), hexNeedles);

		// Mirrors QuicWirePair.pump()'s own loop, but samples key material after every single delivery so
		// that a level discarded mid-exchange is still caught before it disappears (see class Javadoc).
		for (int round = 0; round < 32; round++) {
			boolean progress = false;
			if (wire.deliverToServer()) progress = true;
			if (wire.deliverToClient()) progress = true;
			collectKeyMaterial(wire.client(), hexNeedles);
			collectKeyMaterial(wire.server(), hexNeedles);
			if (!progress) break;
		}

		assertEquals(QuicConnectionState.ESTABLISHED, wire.client().state());
		assertEquals(QuicConnectionState.ESTABLISHED, wire.server().state());
		assertFalse("the handshake never installed any keys to check against", hexNeedles.isEmpty());
		assertFalse("logging never happened; the appender is not wired up", appender.list.isEmpty());

		for (ILoggingEvent event : appender.list) {
			String text = String.valueOf(event.getFormattedMessage()) + ' ' + throwableTextOf(event);
			String lower = text.toLowerCase();
			for (String needle : hexNeedles) {
				assertFalse("log line leaked key material: " + text, lower.contains(needle));
			}
		}
	}

	private static String throwableTextOf(ILoggingEvent event) {
		IThrowableProxy proxy = event.getThrowableProxy();
		return proxy == null ? "" : String.valueOf(proxy.getMessage());
	}

	@Test
	public void quicConnectionToStringNeverPrintsKeyMaterial() throws Exception {
		wire.handshake(QuicConnectionSettings.create());
		Set<String> hexNeedles = new HashSet<>();
		collectKeyMaterial(wire.client(), hexNeedles);
		collectKeyMaterial(wire.server(), hexNeedles);
		assertFalse(hexNeedles.isEmpty());

		String clientString = wire.client().toString();
		String serverString = wire.server().toString();
		String lowerClient = clientString.toLowerCase();
		String lowerServer = serverString.toLowerCase();
		for (String needle : hexNeedles) {
			assertFalse("QuicConnection.toString() leaked key material: " + clientString, lowerClient.contains(needle));
			assertFalse("QuicConnection.toString() leaked key material: " + serverString, lowerServer.contains(needle));
		}
	}

	@Test
	public void levelKeysToStringNeverPrintsKeyMaterial() throws Exception {
		wire.handshake(QuicConnectionSettings.create());
		QuicKeys oneRttKeys = wire.client().sendKeysForTesting(EncryptionLevel.ONE_RTT);
		assertNotNull("the handshake never installed 1-RTT keys", oneRttKeys);

		LevelKeys levelKeys = new LevelKeys(EncryptionLevel.ONE_RTT);
		levelKeys.install(oneRttKeys, oneRttKeys);
		String printed = levelKeys.toString().toLowerCase();

		Set<String> hexNeedles = new HashSet<>();
		addIfLongEnoughToBeUnambiguous(hexNeedles, oneRttKeys.aeadKeyBytes());
		addIfLongEnoughToBeUnambiguous(hexNeedles, oneRttKeys.iv());
		addIfLongEnoughToBeUnambiguous(hexNeedles, oneRttKeys.headerProtectionKey());
		assertFalse(hexNeedles.isEmpty());
		for (String needle : hexNeedles) {
			assertFalse("LevelKeys.toString() leaked key material: " + printed, printed.contains(needle));
		}
		// It is expected to name the suite — that is documented as fine (SI-6 forbids key material only).
		assertTrue(printed.contains("suite="));
	}
}
