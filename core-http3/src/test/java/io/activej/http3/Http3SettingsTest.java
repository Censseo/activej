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

package io.activej.http3;

import io.activej.common.ApplicationSettings;
import io.activej.common.MemSize;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * Defaults asserted against data-model.md §5.1. {@code ApplicationSettings} resolution order
 * (namespaced then short form) is exercised once here; every other module already covers the
 * resolution mechanism itself ({@code ApplicationSettings} is a {@code util-common} concern).
 */
public class Http3SettingsTest {
	private static final String FULLY_QUALIFIED_PREFIX = "io.activej.http3.Http3Settings.";
	private static final String SHORT_PREFIX = "Http3Settings.";

	/** The eight keys feature 006 adds (FR-089), in declaration order. */
	private static final List<String> NEW_KEYS = List.of(
		"qpackMaxTableCapacity",
		"qpackBlockedStreams",
		"qpackNeverIndexedFields",
		"qpackMaxInstructionSize",
		"qpackBlockedStreamTimeout",
		"zeroRttEnabled",
		"datagramsEnabled",
		"maxInboundDatagramsPerStream");

	private static final Path SETTINGS_SOURCE = Path.of("src/main/java/io/activej/http3/Http3Settings.java");

	@Test
	public void defaults() {
		Http3Settings settings = Http3Settings.create();

		assertEquals(MemSize.kilobytes(64).toLong(), settings.maxFieldSectionSize());
		assertEquals(MemSize.megabytes(100).toLong(), settings.maxBodySize());
		assertEquals(MemSize.kilobytes(16).toLong(), settings.maxControlFrameSize());
		assertEquals(100, settings.maxConcurrentRequestStreams());
		assertEquals(3, settings.maxUniStreams());
		assertEquals(256, settings.maxConnections());
		assertEquals(100, settings.maxQueuedRequests());
		assertEquals(8, settings.maxInterimResponses());
		assertEquals(Duration.ofSeconds(60).toMillis(), settings.requestTimeoutMillis());

		// T008 / FR-089. Note qpackBlockedStreams: 16 is the *configured* value, while 0 is what goes on
		// the wire while the table is disabled — see qpackDynamicTableIsDisabledByDefault below.
		assertEquals(0, settings.qpackMaxTableCapacity());
		assertEquals(16, settings.qpackBlockedStreams());
		assertEquals(Set.of("authorization", "proxy-authorization", "set-cookie"),
			settings.qpackNeverIndexedFields());
		assertEquals(MemSize.kilobytes(16).toLong(), settings.qpackMaxInstructionSize());
		assertEquals(Duration.ofSeconds(10).toMillis(), settings.qpackBlockedStreamTimeoutMillis());
		assertFalse(settings.zeroRttEnabled());
		assertFalse(settings.datagramsEnabled());
		assertEquals(32, settings.maxInboundDatagramsPerStream());
	}

	@Test
	public void builderOverridesEveryTunableField() {
		Http3Settings settings = Http3Settings.builder()
			.withMaxFieldSectionSize(MemSize.kilobytes(32))
			.withMaxBodySize(MemSize.megabytes(10))
			.withMaxControlFrameSize(MemSize.kilobytes(8))
			.withMaxConcurrentRequestStreams(50)
			.withMaxConnections(64)
			.withMaxQueuedRequests(10)
			.withMaxInterimResponses(2)
			.withRequestTimeout(Duration.ofSeconds(5))
			.withQpackMaxTableCapacity(MemSize.kilobytes(4))
			.withQpackBlockedStreams(8)
			.withQpackNeverIndexedFields(Set.of("Authorization", "Cookie"))
			.withQpackMaxInstructionSize(MemSize.kilobytes(4))
			.withQpackBlockedStreamTimeout(Duration.ofSeconds(3))
			.withZeroRttEnabled(true)
			.withDatagramsEnabled(true)
			.withMaxInboundDatagramsPerStream(4)
			.build();

		assertEquals(MemSize.kilobytes(32).toLong(), settings.maxFieldSectionSize());
		assertEquals(MemSize.megabytes(10).toLong(), settings.maxBodySize());
		assertEquals(MemSize.kilobytes(8).toLong(), settings.maxControlFrameSize());
		assertEquals(50, settings.maxConcurrentRequestStreams());
		assertEquals(64, settings.maxConnections());
		assertEquals(10, settings.maxQueuedRequests());
		assertEquals(2, settings.maxInterimResponses());
		assertEquals(Duration.ofSeconds(5).toMillis(), settings.requestTimeoutMillis());
		assertEquals(MemSize.kilobytes(4).toInt(), settings.qpackMaxTableCapacity());
		assertEquals(8, settings.qpackBlockedStreams());
		// Lowercased on the way in, because a field name on the wire is lowercase (RFC 9114 §4.1.1).
		assertEquals(Set.of("authorization", "cookie"), settings.qpackNeverIndexedFields());
		assertEquals(MemSize.kilobytes(4).toLong(), settings.qpackMaxInstructionSize());
		assertEquals(Duration.ofSeconds(3).toMillis(), settings.qpackBlockedStreamTimeoutMillis());
		assertTrue(settings.zeroRttEnabled());
		assertTrue(settings.datagramsEnabled());
		assertEquals(4, settings.maxInboundDatagramsPerStream());
		// maxUniStreams is fixed at 3 (FR-017): not a builder field, no withMaxUniStreams(...) exists.
		assertEquals(3, settings.maxUniStreams());
	}

	/**
	 * T115, extended by T118 to the third of the three: each of these becomes a frame reader's declared-
	 * length bound, and {@code Http3FrameReader} allocates a validated declared length as an {@code int} —
	 * so a bound above 2^31-1 would let a length through that wraps negative on the way to the allocator
	 * instead of being refused as excessive load. On the control stream the symptom is quieter still than
	 * on a request stream: no length ever passes the check, and the control stream stalls without a word.
	 * The configuration is refused at {@code build()} rather than the wire length at read time.
	 */
	@Test
	public void aBoundAboveIntegerMaxValueIsRefused() {
		MemSize tooLarge = MemSize.bytes(Integer.MAX_VALUE + 1L);

		assertThrows(IllegalArgumentException.class,
			() -> Http3Settings.builder().withMaxFieldSectionSize(tooLarge).build());
		assertThrows(IllegalArgumentException.class,
			() -> Http3Settings.builder().withMaxBodySize(tooLarge).build());
		assertThrows(IllegalArgumentException.class,
			() -> Http3Settings.builder().withMaxControlFrameSize(tooLarge).build());
	}

	/** The bound itself is not off by one: exactly {@link Integer#MAX_VALUE} is still a legal ceiling. */
	@Test
	public void theLargestBoundThatStillAllocatesIsAccepted() {
		Http3Settings settings = Http3Settings.builder()
			.withMaxFieldSectionSize(MemSize.bytes(Integer.MAX_VALUE))
			.withMaxBodySize(MemSize.bytes(Integer.MAX_VALUE))
			.withMaxControlFrameSize(MemSize.bytes(Integer.MAX_VALUE))
			.build();

		assertEquals(Integer.MAX_VALUE, settings.maxFieldSectionSize());
		assertEquals(Integer.MAX_VALUE, settings.maxBodySize());
		assertEquals(Integer.MAX_VALUE, settings.maxControlFrameSize());
	}

	/** T121: the bound on how many interim responses a server may make a client read past (SI-3). */
	@Test
	public void aNegativeInterimResponseBoundIsRefused() {
		assertThrows(IllegalArgumentException.class,
			() -> Http3Settings.builder().withMaxInterimResponses(-1).build());

		// 0 is not a misconfiguration: it means "accept no informational response at all".
		assertEquals(0, Http3Settings.builder().withMaxInterimResponses(0).build().maxInterimResponses());
	}

	/**
	 * T007 + T008: the QPACK dynamic table is off by default, which is what keeps phase-1 behaviour
	 * byte-for-byte (SC-011).
	 * <p>
	 * {@code qpackBlockedStreams} is 16 <b>as configured</b> — the value that applies once the table is
	 * enabled — while the value put on the wire stays 0, because with a capacity of 0 no field line can
	 * ever block (RFC 9204 §2.1.2). The two are asserted in different places on purpose:
	 * {@code Http3ConnectionSetupTest} and {@code Http3DefaultsUnchangedTest} own the advertised value,
	 * this test owns the configured one, and both are correct simultaneously.
	 */
	@Test
	public void qpackDynamicTableIsDisabledByDefault() {
		Http3Settings settings = Http3Settings.create();
		assertEquals(0, settings.qpackMaxTableCapacity());
		assertEquals(16, settings.qpackBlockedStreams());
	}

	/** T008: the new bounds are refused at {@code build()}, where the configuration can still be fixed. */
	@Test
	public void theNewQpackAndDatagramBoundsAreValidated() {
		assertThrows(IllegalArgumentException.class,
			() -> Http3Settings.builder().withQpackMaxTableCapacity(MemSize.bytes(Integer.MAX_VALUE + 1L)).build());
		assertThrows(IllegalArgumentException.class,
			() -> Http3Settings.builder().withQpackBlockedStreams(-1).build());
		assertThrows(IllegalArgumentException.class,
			() -> Http3Settings.builder().withQpackMaxInstructionSize(MemSize.ZERO).build());
		assertThrows(IllegalArgumentException.class,
			() -> Http3Settings.builder().withQpackMaxInstructionSize(MemSize.bytes(Integer.MAX_VALUE + 1L)).build());
		assertThrows(IllegalArgumentException.class,
			() -> Http3Settings.builder().withQpackBlockedStreamTimeout(Duration.ofSeconds(-1)).build());
		assertThrows(IllegalArgumentException.class,
			() -> Http3Settings.builder().withMaxInboundDatagramsPerStream(-1).build());
		assertThrows(IllegalArgumentException.class,
			() -> Http3Settings.builder().withQpackNeverIndexedFields(Set.of(" ")).build());

		// None of these is a misconfiguration: 0 capacity disables the table, 0 blocked streams never
		// blocks, a 0 timeout disables it, 0 datagrams accepts none, and an empty set never-indexes nothing.
		Http3Settings settings = Http3Settings.builder()
			.withQpackMaxTableCapacity(MemSize.ZERO)
			.withQpackBlockedStreams(0)
			.withQpackBlockedStreamTimeout(Duration.ZERO)
			.withMaxInboundDatagramsPerStream(0)
			.withQpackNeverIndexedFields(Set.of())
			.build();
		assertEquals(0, settings.qpackMaxTableCapacity());
		assertEquals(0, settings.qpackBlockedStreams());
		assertEquals(0, settings.qpackBlockedStreamTimeoutMillis());
		assertEquals(0, settings.maxInboundDatagramsPerStream());
		assertEquals(Set.of(), settings.qpackNeverIndexedFields());
	}

	// ---- T012: both documented -D spellings reach the Http3Settings namespace ----

	/**
	 * T012, layer 1. Every {@code DEFAULT_*} here is a {@code public static final} resolved once at
	 * class-initialization time (DI-5), so by the time a {@code @Test} body runs {@link Http3Settings} has
	 * long since loaded and no {@code System.setProperty} can move it. What <b>is</b> observable in-process
	 * is the lookup itself: {@link ApplicationSettings} reads {@link System#getProperties()} live on every
	 * call, so invoking it with the same {@code (class, key)} pair the field declares proves that both
	 * documented spellings — {@code -Dio.activej.http3.Http3Settings.<key>} and the short
	 * {@code -DHttp3Settings.<key>} — reach this namespace, and that the key strings parse as the type the
	 * field expects.
	 * <p>
	 * <b>The honest boundary</b>: this proves the key name, the owner class and the parse. It does
	 * <i>not</i> prove that the constant passes that pair — {@link #everyNewKeyIsOwnedByHttp3Settings()}
	 * covers the owner from the other side, by reading the source, and only a fresh JVM with the property
	 * already set would prove the field end to end (the {@code Assume} shape of
	 * {@code QuicApplicationSettingsOverrideTest} in {@code core-quic}).
	 * <p>
	 * Note that {@code qpackMaxTableCapacity} is an {@code int} key, not a {@link MemSize} one, even though
	 * its builder setter takes a {@code MemSize}: {@code -DHttp3Settings.qpackMaxTableCapacity=4kb} does not
	 * parse, {@code =4096} does.
	 */
	@Test
	public void everyNewKeyResolvesFromBothSpellings() {
		resolvesFromBothSpellings("qpackMaxTableCapacity", "4096", 4096,
			() -> ApplicationSettings.getInt(Http3Settings.class, "qpackMaxTableCapacity", -1));
		resolvesFromBothSpellings("qpackBlockedStreams", "42", 42,
			() -> ApplicationSettings.getInt(Http3Settings.class, "qpackBlockedStreams", -1));
		resolvesFromBothSpellings("qpackNeverIndexedFields", "cookie,authorization", "cookie,authorization",
			() -> ApplicationSettings.getString(Http3Settings.class, "qpackNeverIndexedFields", "<unset>"));
		resolvesFromBothSpellings("qpackMaxInstructionSize", "32kb", MemSize.kilobytes(32),
			() -> ApplicationSettings.getMemSize(Http3Settings.class, "qpackMaxInstructionSize", MemSize.bytes(1)));
		// StringFormatUtils.parseDuration wants a space before a full-word unit: "45 seconds", never "45s".
		resolvesFromBothSpellings("qpackBlockedStreamTimeout", "45 seconds", Duration.ofSeconds(45),
			() -> ApplicationSettings.getDuration(Http3Settings.class, "qpackBlockedStreamTimeout", Duration.ZERO));
		resolvesFromBothSpellings("zeroRttEnabled", "true", Boolean.TRUE,
			() -> ApplicationSettings.getBoolean(Http3Settings.class, "zeroRttEnabled", false));
		resolvesFromBothSpellings("datagramsEnabled", "true", Boolean.TRUE,
			() -> ApplicationSettings.getBoolean(Http3Settings.class, "datagramsEnabled", false));
		resolvesFromBothSpellings("maxInboundDatagramsPerStream", "7", 7,
			() -> ApplicationSettings.getInt(Http3Settings.class, "maxInboundDatagramsPerStream", -1));
	}

	/**
	 * The documented resolution order — fully-qualified first, then the simple name — is what lets one
	 * class be overridden inside a JVM that also carries a blanket short-form override.
	 */
	@Test
	public void theFullyQualifiedSpellingWinsOverTheShortOne() {
		String fullyQualified = FULLY_QUALIFIED_PREFIX + "qpackBlockedStreams";
		String shortForm = SHORT_PREFIX + "qpackBlockedStreams";
		String savedFullyQualified = System.getProperty(fullyQualified);
		String savedShortForm = System.getProperty(shortForm);
		try {
			System.setProperty(fullyQualified, "9");
			System.setProperty(shortForm, "5");
			assertEquals(Integer.valueOf(9),
				ApplicationSettings.getInt(Http3Settings.class, "qpackBlockedStreams", -1));
		} finally {
			restore(fullyQualified, savedFullyQualified);
			restore(shortForm, savedShortForm);
		}
	}

	/**
	 * {@code ApplicationSettings.parseBoolean} reads an <b>empty</b> value as {@code true}, so a bare
	 * {@code -DHttp3Settings.datagramsEnabled} with no {@code =value} at all enables datagrams. That is the
	 * one spelling that reads like a no-op and is not, and both of these flags default to off precisely
	 * because turning them on has consequences (replayable early data; an allocated datagram queue).
	 */
	@Test
	public void anEmptyPropertyValueTurnsTheTwoFlagsOn() {
		resolvesFromBothSpellings("zeroRttEnabled", "", Boolean.TRUE,
			() -> ApplicationSettings.getBoolean(Http3Settings.class, "zeroRttEnabled", false));
		resolvesFromBothSpellings("datagramsEnabled", "", Boolean.TRUE,
			() -> ApplicationSettings.getBoolean(Http3Settings.class, "datagramsEnabled", false));

		// ... and an explicit "false" is honoured, so neither flag is simply always-on once named.
		resolvesFromBothSpellings("zeroRttEnabled", "false", Boolean.FALSE,
			() -> ApplicationSettings.getBoolean(Http3Settings.class, "zeroRttEnabled", true));
		resolvesFromBothSpellings("datagramsEnabled", "false", Boolean.FALSE,
			() -> ApplicationSettings.getBoolean(Http3Settings.class, "datagramsEnabled", true));
	}

	/**
	 * {@code qpackNeverIndexedFields} is configured as one comma-separated string and used as a set. The
	 * split happens at class-initialization time, so what a test can observe is its product: trimmed,
	 * lowercased with {@link java.util.Locale#ROOT} (a field name on the wire is lowercase, RFC 9114
	 * §4.1.1) and immutable, both from the property and from the builder.
	 */
	@Test
	public void theNeverIndexedFieldListIsLowercasedAndImmutable() {
		assertEquals(Set.of("authorization", "proxy-authorization", "set-cookie"),
			Http3Settings.DEFAULT_QPACK_NEVER_INDEXED_FIELDS);
		assertThrows(UnsupportedOperationException.class,
			() -> Http3Settings.DEFAULT_QPACK_NEVER_INDEXED_FIELDS.add("cookie"));

		// A builder-supplied set takes the same normalization path, and the caller's set stays its own.
		Set<String> configured = Http3Settings.builder()
			.withQpackNeverIndexedFields(Set.of("Authorization", "COOKIE"))
			.build()
			.qpackNeverIndexedFields();
		assertEquals(Set.of("authorization", "cookie"), configured);
		assertThrows(UnsupportedOperationException.class, () -> configured.add("set-cookie"));
	}

	/**
	 * The other half of the owner question, read off the source rather than inferred from a lookup: each of
	 * the eight keys must be declared exactly once, and against {@code Http3Settings.class}. A key wired to
	 * some other owner would resolve from a namespace nobody documents, and — being a
	 * {@code public static final} — would do so silently, with every documented override having no effect.
	 * {@code core-quic} carries the scar that makes this worth asserting (its keys are owned by
	 * {@code QuicConnection}, not {@code QuicConnectionSettings}, and once were not).
	 */
	@Test
	public void everyNewKeyIsOwnedByHttp3Settings() throws IOException {
		assertTrue("the source tree moved: " + SETTINGS_SOURCE.toAbsolutePath(), Files.isRegularFile(SETTINGS_SOURCE));
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
			assertTrue("\"" + key + "\" must be owned by Http3Settings.class: " + declaration,
				declaration.contains("Http3Settings.class"));
		}
	}

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
			assertEquals("-D" + fullyQualified + "=" + raw + " did not reach the Http3Settings namespace",
				expected, read.get());

			System.clearProperty(fullyQualified);
			System.setProperty(shortForm, raw);
			assertEquals("-D" + shortForm + "=" + raw + " did not reach the Http3Settings namespace",
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
}
