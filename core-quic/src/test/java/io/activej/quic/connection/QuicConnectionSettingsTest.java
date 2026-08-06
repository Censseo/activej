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

import io.activej.common.ApplicationSettings;
import io.activej.common.MemSize;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

/**
 * T012 — the ten {@link QuicConnectionSettings} fields feature 006 adds (FR-089): the eight
 * session-resumption bounds of RFC 8446 §4.6.1 and the two RFC 9221 §3 unreliable-datagram bounds. Their
 * shipped defaults, both documented {@code ApplicationSettings} spellings, the builder pass-through, and
 * the {@code doBuild()} refusals — including the cross-field one that no single field can express.
 * <p>
 * <b>The namespace is {@code QuicConnection}, not {@code QuicConnectionSettings}</b> (FR-088a). That is
 * not cosmetic: it is the module's one recorded settings regression — every key here once resolved from
 * {@code QuicConnectionSettings.class}, so every documented override silently did nothing, and nothing
 * failed. {@link #everyNewKeyIsOwnedByQuicConnectionNotQuicConnectionSettings()} is the test that catches
 * it recurring.
 * <p>
 * <b>Why the resolution tests do not simply set a property and read the constant.</b> Every
 * {@code DEFAULT_*} is a {@code public static final} resolved once at class-initialization time (DI-5), so
 * by the time a {@code @Test} body runs this class has long since loaded and no {@code System.setProperty}
 * can move it. What <i>is</i> observable in-process is the lookup: {@link ApplicationSettings} reads
 * {@link System#getProperties()} live on every call, so invoking it with the same {@code (class, key)} pair
 * a field declares proves both spellings reach that namespace and that the raw value parses as the type the
 * field expects. The remaining half — that the field actually passes that pair — is covered from the other
 * side by the source scan, and end to end only by a fresh JVM, which is the {@code Assume} shape
 * {@link QuicApplicationSettingsOverrideTest} established and the last two tests here follow:
 * <pre>
 * mvn -pl core-quic -am test -Dtest=QuicConnectionSettingsTest -Dsurefire.failIfNoSpecifiedTests=false \
 *   -DargLine='-Dio.activej.quic.connection.QuicConnection.sessionTicketLifetime="2 hours" -DQuicConnection.maxOutboundDatagrams=9'
 * </pre>
 * Note the quoting: {@link io.activej.common.StringFormatUtils#parseDuration} requires a space before a
 * full-word unit ({@code days|hours|minutes|seconds|millis|nanos}), so {@code 2h} does not parse and the
 * outer single quotes keep Maven from splitting {@code -DargLine} on that space. {@link MemSize} has no
 * such requirement — {@code 8kb}.
 * <p>
 * No {@code ByteBufRule} or {@code EventloopRule}: nothing here allocates a buffer or touches a reactor.
 * Mutating system properties is safe because Surefire is configured with {@code systemPropertyVariables}
 * only and no {@code parallel} setting, so test classes run sequentially inside a fork and {@code -T1C}
 * parallelises whole modules, each in its own JVM. If a {@code parallel} configuration is ever added, the
 * property-setting tests here must move to the {@code Assume}-gated shape.
 *
 * @see <a href="https://www.rfc-editor.org/rfc/rfc8446#section-4.6.1">RFC 8446 §4.6.1 — New Session Ticket</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9221#section-3">RFC 9221 §3 — max_datagram_frame_size</a>
 */
public final class QuicConnectionSettingsTest {
	private static final String FULLY_QUALIFIED_PREFIX = "io.activej.quic.connection.QuicConnection.";
	private static final String SHORT_PREFIX = "QuicConnection.";

	/** The ten keys feature 006 adds, in declaration order. */
	private static final List<String> NEW_KEYS = List.of(
		"sessionTicketLifetime",
		"sessionTicketKeyRotation",
		"sessionTicketsPerHandshake",
		"maxSessionTickets",
		"maxEarlyDataReplayRecords",
		"ticketAgeTolerance",
		"maxSessionTicketSize",
		"maxSessionTicketsPerConnection",
		"maxDatagramFrameSize",
		"maxOutboundDatagrams");

	private static final Path SETTINGS_SOURCE =
		Path.of("src/main/java/io/activej/quic/connection/QuicConnectionSettings.java");

	/**
	 * Whether this JVM was started with any of the ten keys overridden, captured at class-initialization
	 * time — before any test body has had the chance to set one. The tests that pin the shipped defaults
	 * are meaningless in such a JVM, and such a JVM is exactly what the two {@code Assume}-gated tests at
	 * the bottom exist for.
	 */
	private static final boolean STARTED_WITH_AN_OVERRIDE = NEW_KEYS.stream()
		.anyMatch(key -> System.getProperty(FULLY_QUALIFIED_PREFIX + key) != null
			|| System.getProperty(SHORT_PREFIX + key) != null);

	// ---- the shipped defaults ----

	@Test
	public void defaults() {
		assumeNoOverride();

		assertEquals(Duration.ofHours(1), QuicConnectionSettings.DEFAULT_SESSION_TICKET_LIFETIME);
		assertEquals(Duration.ofHours(6), QuicConnectionSettings.DEFAULT_SESSION_TICKET_KEY_ROTATION);
		assertEquals(2, QuicConnectionSettings.DEFAULT_SESSION_TICKETS_PER_HANDSHAKE);
		assertEquals(256, QuicConnectionSettings.DEFAULT_MAX_SESSION_TICKETS);
		assertEquals(65536, QuicConnectionSettings.DEFAULT_MAX_EARLY_DATA_REPLAY_RECORDS);
		assertEquals(Duration.ofSeconds(10), QuicConnectionSettings.DEFAULT_TICKET_AGE_TOLERANCE);
		assertEquals(MemSize.kilobytes(8), QuicConnectionSettings.DEFAULT_MAX_SESSION_TICKET_SIZE);
		assertEquals(8, QuicConnectionSettings.DEFAULT_MAX_SESSION_TICKETS_PER_CONNECTION);
		// 0 is the RFC 9221 §3 encoding of "DATAGRAM not supported", which is the default (SC-011).
		assertEquals(MemSize.ZERO, QuicConnectionSettings.DEFAULT_MAX_DATAGRAM_FRAME_SIZE);
		assertEquals(64, QuicConnectionSettings.DEFAULT_MAX_OUTBOUND_DATAGRAMS);
	}

	@Test
	public void aDefaultBuiltSettingsCarriesTheTenDefaults() {
		assumeNoOverride();
		QuicConnectionSettings settings = QuicConnectionSettings.create();

		assertEquals(Duration.ofHours(1).toMillis(), settings.sessionTicketLifetimeMillis());
		assertEquals(Duration.ofHours(6).toMillis(), settings.sessionTicketKeyRotationMillis());
		assertEquals(2, settings.sessionTicketsPerHandshake());
		assertEquals(256, settings.maxSessionTickets());
		assertEquals(65536, settings.maxEarlyDataReplayRecords());
		assertEquals(Duration.ofSeconds(10).toMillis(), settings.ticketAgeToleranceMillis());
		assertEquals(MemSize.kilobytes(8).toLong(), settings.maxSessionTicketSize());
		assertEquals(8, settings.maxSessionTicketsPerConnection());
		assertEquals(0, settings.maxDatagramFrameSize());
		assertEquals(64, settings.maxOutboundDatagrams());
	}

	@Test
	public void theDefaultsSatisfyTheCrossFieldTicketKeyRule() {
		// SI-2: safe by default means the shipped combination builds, not merely that a check exists.
		QuicConnectionSettings settings = QuicConnectionSettings.create();
		assertTrue("2 x sessionTicketKeyRotation must cover sessionTicketLifetime",
			2 * settings.sessionTicketKeyRotationMillis() >= settings.sessionTicketLifetimeMillis());
	}

	// ---- both documented -D spellings reach the QuicConnection namespace ----

	@Test
	public void everyNewKeyResolvesFromBothSpellings() {
		// StringFormatUtils.parseDuration wants a space before a full-word unit: "2 hours", never "2h".
		resolvesFromBothSpellings("sessionTicketLifetime", "2 hours", Duration.ofHours(2),
			() -> ApplicationSettings.getDuration(QuicConnection.class, "sessionTicketLifetime", Duration.ZERO));
		resolvesFromBothSpellings("sessionTicketKeyRotation", "90 minutes", Duration.ofMinutes(90),
			() -> ApplicationSettings.getDuration(QuicConnection.class, "sessionTicketKeyRotation", Duration.ZERO));
		resolvesFromBothSpellings("sessionTicketsPerHandshake", "5", 5,
			() -> ApplicationSettings.getInt(QuicConnection.class, "sessionTicketsPerHandshake", -1));
		resolvesFromBothSpellings("maxSessionTickets", "512", 512,
			() -> ApplicationSettings.getInt(QuicConnection.class, "maxSessionTickets", -1));
		resolvesFromBothSpellings("maxEarlyDataReplayRecords", "1024", 1024,
			() -> ApplicationSettings.getInt(QuicConnection.class, "maxEarlyDataReplayRecords", -1));
		resolvesFromBothSpellings("ticketAgeTolerance", "3 seconds", Duration.ofSeconds(3),
			() -> ApplicationSettings.getDuration(QuicConnection.class, "ticketAgeTolerance", Duration.ZERO));
		resolvesFromBothSpellings("maxSessionTicketSize", "16kb", MemSize.kilobytes(16),
			() -> ApplicationSettings.getMemSize(QuicConnection.class, "maxSessionTicketSize", MemSize.bytes(1)));
		resolvesFromBothSpellings("maxSessionTicketsPerConnection", "3", 3,
			() -> ApplicationSettings.getInt(QuicConnection.class, "maxSessionTicketsPerConnection", -1));
		resolvesFromBothSpellings("maxDatagramFrameSize", "1200b", MemSize.bytes(1200),
			() -> ApplicationSettings.getMemSize(QuicConnection.class, "maxDatagramFrameSize", MemSize.bytes(1)));
		resolvesFromBothSpellings("maxOutboundDatagrams", "9", 9,
			() -> ApplicationSettings.getInt(QuicConnection.class, "maxOutboundDatagrams", -1));
	}

	/**
	 * The documented resolution order — fully-qualified first, then the simple name — is what lets one
	 * class be overridden inside a JVM that also carries a blanket short-form override.
	 */
	@Test
	public void theFullyQualifiedSpellingWinsOverTheShortOne() {
		String fullyQualified = FULLY_QUALIFIED_PREFIX + "maxOutboundDatagrams";
		String shortForm = SHORT_PREFIX + "maxOutboundDatagrams";
		String savedFullyQualified = System.getProperty(fullyQualified);
		String savedShortForm = System.getProperty(shortForm);
		try {
			System.setProperty(fullyQualified, "9");
			System.setProperty(shortForm, "5");
			assertEquals(Integer.valueOf(9),
				ApplicationSettings.getInt(QuicConnection.class, "maxOutboundDatagrams", -1));
		} finally {
			restore(fullyQualified, savedFullyQualified);
			restore(shortForm, savedShortForm);
		}
	}

	/**
	 * The regression this class exists for, read off the source rather than inferred from a lookup: each of
	 * the ten keys is declared exactly once, through {@code ApplicationSettings}, and against
	 * <b>{@code QuicConnection.class}</b>. A key owned by {@code QuicConnectionSettings.class} resolves
	 * from a namespace nothing documents, and — being a {@code public static final} — does so in silence:
	 * every documented override has no effect and no test fails. The lookup-based test above cannot see it,
	 * because it names the owner itself; this one reads what the field names.
	 */
	@Test
	public void everyNewKeyIsOwnedByQuicConnectionNotQuicConnectionSettings() throws IOException {
		assertTrue("the source tree moved: " + SETTINGS_SOURCE.toAbsolutePath(),
			Files.isRegularFile(SETTINGS_SOURCE));
		List<String> lines = Files.readAllLines(SETTINGS_SOURCE);

		for (String key : NEW_KEYS) {
			List<String> declarations = lines.stream()
				.map(String::strip)
				.filter(line -> line.contains('"' + key + '"'))
				.toList();
			assertEquals("expected exactly one ApplicationSettings declaration of \"" + key + "\"",
				1, declarations.size());
			String declaration = declarations.get(0);
			assertTrue("\"" + key + "\" must be declared through ApplicationSettings: " + declaration,
				declaration.contains("ApplicationSettings.get"));
			assertTrue(
				"FR-088a: \"" + key + "\" must be owned by QuicConnection.class, not QuicConnectionSettings.class: " +
				declaration,
				declaration.contains("QuicConnection.class"));
		}
	}

	// ---- the builder carries every one of the ten through ----

	@Test
	public void builderOverridesEveryNewField() {
		QuicConnectionSettings settings = QuicConnectionSettings.builder()
			.withSessionTicketLifetime(Duration.ofMinutes(30))
			.withSessionTicketKeyRotation(Duration.ofMinutes(20))
			.withSessionTicketsPerHandshake(1)
			.withMaxSessionTickets(7)
			.withMaxEarlyDataReplayRecords(11)
			.withTicketAgeTolerance(Duration.ofSeconds(3))
			.withMaxSessionTicketSize(MemSize.kilobytes(4))
			.withMaxSessionTicketsPerConnection(5)
			.withMaxDatagramFrameSize(MemSize.bytes(1200))
			.withMaxOutboundDatagrams(9)
			.build();

		assertEquals(Duration.ofMinutes(30).toMillis(), settings.sessionTicketLifetimeMillis());
		assertEquals(Duration.ofMinutes(20).toMillis(), settings.sessionTicketKeyRotationMillis());
		assertEquals(1, settings.sessionTicketsPerHandshake());
		assertEquals(7, settings.maxSessionTickets());
		assertEquals(11, settings.maxEarlyDataReplayRecords());
		assertEquals(Duration.ofSeconds(3).toMillis(), settings.ticketAgeToleranceMillis());
		assertEquals(MemSize.kilobytes(4).toLong(), settings.maxSessionTicketSize());
		assertEquals(5, settings.maxSessionTicketsPerConnection());
		assertEquals(1200, settings.maxDatagramFrameSize());
		assertEquals(9, settings.maxOutboundDatagrams());
	}

	@Test
	public void aWithXxxAfterBuildIsRejected() {
		// DI-4: build() is one-shot, and every new withXxx opens with checkNotBuilt.
		QuicConnectionSettings.Builder builder = QuicConnectionSettings.builder();
		builder.build();

		assertThrows(IllegalStateException.class, () -> builder.withSessionTicketLifetime(Duration.ofHours(2)));
		assertThrows(IllegalStateException.class, () -> builder.withSessionTicketKeyRotation(Duration.ofHours(2)));
		assertThrows(IllegalStateException.class, () -> builder.withSessionTicketsPerHandshake(1));
		assertThrows(IllegalStateException.class, () -> builder.withMaxSessionTickets(1));
		assertThrows(IllegalStateException.class, () -> builder.withMaxEarlyDataReplayRecords(1));
		assertThrows(IllegalStateException.class, () -> builder.withTicketAgeTolerance(Duration.ofSeconds(1)));
		assertThrows(IllegalStateException.class, () -> builder.withMaxSessionTicketSize(MemSize.kilobytes(1)));
		assertThrows(IllegalStateException.class, () -> builder.withMaxSessionTicketsPerConnection(1));
		assertThrows(IllegalStateException.class, () -> builder.withMaxDatagramFrameSize(MemSize.bytes(1200)));
		assertThrows(IllegalStateException.class, () -> builder.withMaxOutboundDatagrams(1));
	}

	// ---- what doBuild() refuses ----

	@Test
	public void invalidConfigurationsAreRefusedAtBuild() {
		assertNamesField(refused(b -> b.withSessionTicketLifetime(Duration.ZERO)), "sessionTicketLifetime");
		assertNamesField(refused(b -> b.withSessionTicketLifetime(Duration.ofSeconds(-1))), "sessionTicketLifetime");
		assertNamesField(refused(b -> b.withSessionTicketKeyRotation(Duration.ZERO)), "sessionTicketKeyRotation");
		assertNamesField(refused(b -> b.withSessionTicketKeyRotation(Duration.ofSeconds(-1))),
			"sessionTicketKeyRotation");
		assertNamesField(refused(b -> b.withTicketAgeTolerance(Duration.ofSeconds(-1))), "ticketAgeTolerance");
		assertNamesField(refused(b -> b.withSessionTicketsPerHandshake(-1)), "sessionTicketsPerHandshake");
		assertNamesField(refused(b -> b.withMaxSessionTickets(-1)), "maxSessionTickets");
		assertNamesField(refused(b -> b.withMaxSessionTicketsPerConnection(-1)), "maxSessionTicketsPerConnection");
		// The early-data register fails closed, so an empty one refuses every attempt rather than admitting
		// a replay — which makes 0 a denial of service on the resumption path, not a way to switch it off.
		assertNamesField(refused(b -> b.withMaxEarlyDataReplayRecords(0)), "maxEarlyDataReplayRecords");
		// A peer-declared ticket length is checked against this before anything is allocated for it (SI-4),
		// and that check is against an int, so a bound above Integer.MAX_VALUE could never be reached.
		assertNamesField(refused(b -> b.withMaxSessionTicketSize(MemSize.ZERO)), "maxSessionTicketSize");
		assertNamesField(refused(b -> b.withMaxSessionTicketSize(MemSize.bytes(Integer.MAX_VALUE + 1L))),
			"maxSessionTicketSize");
		assertNamesField(
			refused(b -> b.withMaxDatagramFrameSize(
				MemSize.bytes(QuicConnectionSettings.MAX_MAX_DATAGRAM_SIZE + 1L))),
			"maxDatagramFrameSize");
		assertNamesField(refused(b -> b.withMaxOutboundDatagrams(-1)), "maxOutboundDatagrams");
	}

	@Test
	public void theZeroesThatAreNotMisconfigurationsAreAccepted() {
		QuicConnectionSettings settings = QuicConnectionSettings.builder()
			.withSessionTicketsPerHandshake(0)          // issue no ticket
			.withMaxSessionTickets(0)                   // cache none
			.withMaxSessionTicketsPerConnection(0)      // accept no post-handshake ticket
			.withTicketAgeTolerance(Duration.ZERO)      // allow no clock skew
			.withMaxDatagramFrameSize(MemSize.ZERO)     // DATAGRAM not supported (RFC 9221 §3)
			.withMaxOutboundDatagrams(0)                // queue none
			.build();

		assertEquals(0, settings.sessionTicketsPerHandshake());
		assertEquals(0, settings.maxSessionTickets());
		assertEquals(0, settings.maxSessionTicketsPerConnection());
		assertEquals(0, settings.ticketAgeToleranceMillis());
		assertEquals(0, settings.maxDatagramFrameSize());
		assertEquals(0, settings.maxOutboundDatagrams());
	}

	@Test
	public void theBoundsThemselvesAreNotOffByOne() {
		QuicConnectionSettings settings = QuicConnectionSettings.builder()
			.withMaxEarlyDataReplayRecords(1)
			.withMaxSessionTicketSize(MemSize.bytes(Integer.MAX_VALUE))
			.withMaxDatagramFrameSize(MemSize.bytes(QuicConnectionSettings.MAX_MAX_DATAGRAM_SIZE))
			.build();

		assertEquals(1, settings.maxEarlyDataReplayRecords());
		assertEquals(Integer.MAX_VALUE, settings.maxSessionTicketSize());
		assertEquals(QuicConnectionSettings.MAX_MAX_DATAGRAM_SIZE, settings.maxDatagramFrameSize());
	}

	/**
	 * The cross-field refusal: two ticket-sealing keys are retained across one rotation, so a ticket whose
	 * lifetime outlives both can no longer be opened by the endpoint that issued it — every such
	 * resumption silently degrades to a full handshake. Refused at {@code build()} rather than warned
	 * about, like the other configurations in this class that cannot work.
	 */
	@Test
	public void aTicketOutlivingTheTwoRetainedKeysIsRefused() {
		IllegalArgumentException e = refused(b -> b
			.withSessionTicketLifetime(Duration.ofHours(10))
			.withSessionTicketKeyRotation(Duration.ofHours(1)));
		assertNamesField(e, "sessionTicketKeyRotation");
		assertTrue("the message must say what it is measured against: " + e.getMessage(),
			e.getMessage().contains("sessionTicketLifetime"));

		// Neither field is illegal on its own — it is the pair that is, in either direction.
		assertNamesField(refused(b -> b.withSessionTicketLifetime(Duration.ofHours(13))),
			"sessionTicketKeyRotation");
	}

	@Test
	public void exactlyHalfTheLifetimeIsAcceptedAsTheRotation() {
		QuicConnectionSettings settings = QuicConnectionSettings.builder()
			.withSessionTicketLifetime(Duration.ofHours(2))
			.withSessionTicketKeyRotation(Duration.ofHours(1))
			.build();

		assertEquals(Duration.ofHours(1).toMillis(), settings.sessionTicketKeyRotationMillis());
		assertEquals(Duration.ofHours(2).toMillis(), settings.sessionTicketLifetimeMillis());
	}

	@Test
	public void toStringCarriesTheTenNewFields() {
		String text = QuicConnectionSettings.create().toString();
		for (String key : NEW_KEYS) {
			assertTrue("toString must mention " + key + ": " + text, text.contains(key + "="));
		}
	}

	// ---- the same, actually observed on the constants, in a JVM where the property was set first ----

	@Test
	public void sessionTicketLifetimeFullyQualifiedSpellingReachesTheConstant() {
		assumeTrue(
			"run with -Dio.activej.quic.connection.QuicConnection.sessionTicketLifetime='<n> hours' to exercise this",
			System.getProperty(FULLY_QUALIFIED_PREFIX + "sessionTicketLifetime") != null);
		assertNotEquals("the fully-qualified system property spelling did not reach QuicConnectionSettings",
			Duration.ofHours(1), QuicConnectionSettings.DEFAULT_SESSION_TICKET_LIFETIME);
	}

	@Test
	public void maxOutboundDatagramsShortFormSpellingReachesTheConstant() {
		assumeTrue("run with -DQuicConnection.maxOutboundDatagrams=<count> to exercise this",
			System.getProperty(SHORT_PREFIX + "maxOutboundDatagrams") != null);
		assertNotEquals("the short-form system property spelling did not reach QuicConnectionSettings",
			64, QuicConnectionSettings.DEFAULT_MAX_OUTBOUND_DATAGRAMS);
	}

	// ---- helpers ----

	/**
	 * Sets {@code key} under each documented spelling in turn and asserts the lookup sees it. Every path
	 * restores what it found — a leaked system property poisons every later test in the fork, and a run
	 * carrying real {@code -DargLine} overrides must not be disarmed by a test that probed the same key.
	 * <p>
	 * The fully-qualified spelling wins over the short one, so each is probed with the other cleared.
	 */
	private static <T> void resolvesFromBothSpellings(String key, String raw, T expected, Supplier<T> read) {
		String fullyQualified = FULLY_QUALIFIED_PREFIX + key;
		String shortForm = SHORT_PREFIX + key;
		String savedFullyQualified = System.getProperty(fullyQualified);
		String savedShortForm = System.getProperty(shortForm);
		try {
			System.clearProperty(shortForm);
			System.setProperty(fullyQualified, raw);
			assertEquals("-D" + fullyQualified + "=" + raw + " did not reach the QuicConnection namespace",
				expected, read.get());

			System.clearProperty(fullyQualified);
			System.setProperty(shortForm, raw);
			assertEquals("-D" + shortForm + "=" + raw + " did not reach the QuicConnection namespace",
				expected, read.get());
		} finally {
			restore(fullyQualified, savedFullyQualified);
			restore(shortForm, savedShortForm);
		}
	}

	private static void restore(String property, String value) {
		if (value == null) {
			System.clearProperty(property);
		} else {
			System.setProperty(property, value);
		}
	}

	/** Applies {@code configuration} to a fresh builder and asserts that {@code build()} refuses it. */
	private static IllegalArgumentException refused(Consumer<QuicConnectionSettings.Builder> configuration) {
		QuicConnectionSettings.Builder builder = QuicConnectionSettings.builder();
		configuration.accept(builder);
		return assertThrows(IllegalArgumentException.class, builder::build);
	}

	private static void assertNamesField(IllegalArgumentException e, String field) {
		assertTrue("the message must name the offending field '" + field + "': " + e.getMessage(),
			e.getMessage().startsWith(field + " "));
	}

	private static void assumeNoOverride() {
		assumeTrue("this JVM carries a QuicConnection.* override, so the shipped defaults do not apply",
			!STARTED_WITH_AN_OVERRIDE);
	}
}
