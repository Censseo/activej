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
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

/**
 * T020 — the nine stream-related {@link QuicConnectionSettings} fields (FR-019, FR-024): their
 * documented defaults, their two-tier {@code ApplicationSettings} spelling, and the two cross-field
 * validations that reject at {@code build()}.
 * <p>
 * <b>Why the resolution tests do not simply set a property and read the constant.</b> Every
 * {@code DEFAULT_*} here is a {@code public static final} resolved once at class-initialization time
 * (DI-5), so by the time a {@code @Test} body runs, {@link QuicConnectionSettings} has long since
 * loaded and no {@code System.setProperty} can move it. What <i>is</i> observable in-process is the
 * lookup itself: {@link ApplicationSettings} reads {@link System#getProperties()} live, so calling it
 * with the same {@code (class, key)} pair the field uses proves that both documented spellings —
 * {@code -Dio.activej.quic.connection.QuicConnection.<key>} and the short {@code -DQuicConnection.<key>}
 * — reach this namespace, and that the key strings are the ones the field declares.
 * <p>
 * The remaining half — that each field actually passes that pair — needs a fresh JVM, and follows
 * {@link QuicApplicationSettingsOverrideTest}'s {@code Assume} shape: skipped under the headline run,
 * exercised by
 * <pre>
 * mvn -pl core-quic test -Dtest=QuicStreamSettingsTest -Dsurefire.failIfNoSpecifiedTests=false \
 *   -DargLine='-Dio.activej.quic.connection.QuicConnection.initialMaxData=8mb -DQuicConnection.initialMaxStreamsBidi=7'
 * </pre>
 */
public final class QuicStreamSettingsTest {

	/** Every key added by feature 04, paired with the type its {@code ApplicationSettings} getter uses. */
	private static final List<String> MEM_SIZE_KEYS = List.of(
		"initialMaxData",
		"initialMaxStreamDataBidiLocal",
		"initialMaxStreamDataBidiRemote",
		"initialMaxStreamDataUni",
		"maxOutstandingStreamBytes");
	private static final List<String> LONG_KEYS = List.of(
		"initialMaxStreamsBidi",
		"initialMaxStreamsUni");
	private static final List<String> INT_KEYS = List.of(
		"maxReceiveRangesPerStream",
		"maxPendingStreamOpens");

	private static final String FULLY_QUALIFIED_PREFIX = "io.activej.quic.connection.QuicConnection.";
	private static final String SHORT_PREFIX = "QuicConnection.";

	/**
	 * Whether this JVM was started with any of the nine keys overridden, captured before any test body
	 * has had a chance to set one. The tests that pin the shipped defaults are meaningless in such a JVM
	 * — it is the very run that the {@code Assume}-gated pair below exists for.
	 */
	private static final boolean STARTED_WITH_AN_OVERRIDE =
		probedProperties().stream().anyMatch(property -> System.getProperty(property) != null);

	private final Map<String, String> savedProperties = new HashMap<>();

	@Before
	public void saveProbeProperties() {
		// Restored rather than cleared, so that a run carrying real -DargLine overrides (the Assume-gated
		// tests below) is not silently disarmed by the first test that probes the same key.
		for (String property : probedProperties()) {
			savedProperties.put(property, System.getProperty(property));
		}
	}

	@After
	public void restoreProbeProperties() {
		savedProperties.forEach((property, value) -> {
			if (value == null) {
				System.clearProperty(property);
			} else {
				System.setProperty(property, value);
			}
		});
		savedProperties.clear();
	}

	private static List<String> probedProperties() {
		List<String> properties = new ArrayList<>();
		for (String key : allKeys()) {
			properties.add(FULLY_QUALIFIED_PREFIX + key);
			properties.add(SHORT_PREFIX + key);
		}
		return properties;
	}

	private static List<String> allKeys() {
		return List.of(
			"initialMaxData", "initialMaxStreamDataBidiLocal", "initialMaxStreamDataBidiRemote",
			"initialMaxStreamDataUni", "initialMaxStreamsBidi", "initialMaxStreamsUni",
			"maxOutstandingStreamBytes", "maxReceiveRangesPerStream", "maxPendingStreamOpens");
	}

	// ---- the documented defaults ----

	@Test
	public void theNineDefaultsAreTheDocumentedOnes() {
		assumeNoOverride();
		assertEquals(MemSize.megabytes(1), QuicConnectionSettings.DEFAULT_INITIAL_MAX_DATA);
		assertEquals(MemSize.kilobytes(256), QuicConnectionSettings.DEFAULT_INITIAL_MAX_STREAM_DATA_BIDI_LOCAL);
		assertEquals(MemSize.kilobytes(256), QuicConnectionSettings.DEFAULT_INITIAL_MAX_STREAM_DATA_BIDI_REMOTE);
		assertEquals(MemSize.kilobytes(256), QuicConnectionSettings.DEFAULT_INITIAL_MAX_STREAM_DATA_UNI);
		assertEquals(100, QuicConnectionSettings.DEFAULT_INITIAL_MAX_STREAMS_BIDI);
		assertEquals(3, QuicConnectionSettings.DEFAULT_INITIAL_MAX_STREAMS_UNI);
		assertEquals(MemSize.kilobytes(512), QuicConnectionSettings.DEFAULT_MAX_OUTSTANDING_STREAM_BYTES);
		assertEquals(32, QuicConnectionSettings.DEFAULT_MAX_RECEIVE_RANGES_PER_STREAM);
		assertEquals(128, QuicConnectionSettings.DEFAULT_MAX_PENDING_STREAM_OPENS);
	}

	@Test
	public void aDefaultBuiltSettingsCarriesTheNineDefaults() {
		assumeNoOverride();
		QuicConnectionSettings settings = QuicConnectionSettings.create();

		assertEquals(MemSize.megabytes(1).toLong(), settings.initialMaxData());
		assertEquals(MemSize.kilobytes(256).toLong(), settings.initialMaxStreamDataBidiLocal());
		assertEquals(MemSize.kilobytes(256).toLong(), settings.initialMaxStreamDataBidiRemote());
		assertEquals(MemSize.kilobytes(256).toLong(), settings.initialMaxStreamDataUni());
		assertEquals(100, settings.initialMaxStreamsBidi());
		assertEquals(3, settings.initialMaxStreamsUni());
		assertEquals(MemSize.kilobytes(512).toLong(), settings.maxOutstandingStreamBytes());
		assertEquals(32, settings.maxReceiveRangesPerStream());
		assertEquals(128, settings.maxPendingStreamOpens());
	}

	@Test
	public void theDefaultsSatisfyBothCrossFieldValidations() {
		// SI-2: safe by default means the shipped combination builds, not merely that a check exists.
		QuicConnectionSettings settings = QuicConnectionSettings.create();
		assertTrue(settings.maxOutstandingStreamBytes() < settings.maxSendQueueBytes());
		assertTrue(Math.max(settings.initialMaxStreamDataBidiLocal(),
			Math.max(settings.initialMaxStreamDataBidiRemote(), settings.initialMaxStreamDataUni()))
			<= settings.initialMaxData());
	}

	// ---- both documented system-property spellings reach the QuicConnection namespace ----

	@Test
	public void everyMemSizeKeyResolvesFromTheFullyQualifiedSpelling() {
		for (String key : MEM_SIZE_KEYS) {
			System.setProperty(FULLY_QUALIFIED_PREFIX + key, "7mb");
			assertEquals("-D" + FULLY_QUALIFIED_PREFIX + key + " did not reach the QuicConnection namespace",
				MemSize.megabytes(7),
				ApplicationSettings.getMemSize(QuicConnection.class, key, MemSize.bytes(1)));
			System.clearProperty(FULLY_QUALIFIED_PREFIX + key);
		}
	}

	@Test
	public void everyMemSizeKeyResolvesFromTheShortSpelling() {
		for (String key : MEM_SIZE_KEYS) {
			// The fully-qualified spelling wins, so it must be out of the way for the short one to be
			// observable at all — including in a JVM started with one, as the Assume-gated run below is.
			System.clearProperty(FULLY_QUALIFIED_PREFIX + key);
			System.setProperty(SHORT_PREFIX + key, "7mb");
			assertEquals("-D" + SHORT_PREFIX + key + " did not reach the QuicConnection namespace",
				MemSize.megabytes(7),
				ApplicationSettings.getMemSize(QuicConnection.class, key, MemSize.bytes(1)));
			System.clearProperty(SHORT_PREFIX + key);
		}
	}

	@Test
	public void everyNumericKeyResolvesFromBothSpellings() {
		for (String key : LONG_KEYS) {
			System.setProperty(FULLY_QUALIFIED_PREFIX + key, "4242");
			assertEquals("-D" + FULLY_QUALIFIED_PREFIX + key + " did not reach the QuicConnection namespace",
				Long.valueOf(4242), ApplicationSettings.getLong(QuicConnection.class, key, 1L));
			System.clearProperty(FULLY_QUALIFIED_PREFIX + key);

			// The fully-qualified spelling wins, so it is cleared above before the short one is probed.
			System.setProperty(SHORT_PREFIX + key, "4242");
			assertEquals("-D" + SHORT_PREFIX + key + " did not reach the QuicConnection namespace",
				Long.valueOf(4242), ApplicationSettings.getLong(QuicConnection.class, key, 1L));
			System.clearProperty(SHORT_PREFIX + key);
		}
		for (String key : INT_KEYS) {
			System.setProperty(FULLY_QUALIFIED_PREFIX + key, "4242");
			assertEquals("-D" + FULLY_QUALIFIED_PREFIX + key + " did not reach the QuicConnection namespace",
				Integer.valueOf(4242), ApplicationSettings.getInt(QuicConnection.class, key, 1));
			System.clearProperty(FULLY_QUALIFIED_PREFIX + key);

			System.setProperty(SHORT_PREFIX + key, "4242");
			assertEquals("-D" + SHORT_PREFIX + key + " did not reach the QuicConnection namespace",
				Integer.valueOf(4242), ApplicationSettings.getInt(QuicConnection.class, key, 1));
			System.clearProperty(SHORT_PREFIX + key);
		}
	}

	@Test
	public void theFullyQualifiedSpellingWinsOverTheShortOne() {
		// The documented resolution order (FQCN first, then simple name) is what makes a per-class
		// override possible in a JVM that also carries a blanket short-form one.
		System.setProperty(FULLY_QUALIFIED_PREFIX + "initialMaxData", "9mb");
		System.setProperty(SHORT_PREFIX + "initialMaxData", "2mb");
		assertEquals(MemSize.megabytes(9),
			ApplicationSettings.getMemSize(QuicConnection.class, "initialMaxData", MemSize.bytes(1)));
	}

	// ---- the same, actually observed on the constants, in a JVM where the property was set first ----

	@Test
	public void initialMaxDataFullyQualifiedSpellingReachesSettings() {
		assumeTrue("run with -Dio.activej.quic.connection.QuicConnection.initialMaxData=<size> to exercise this",
			System.getProperty(FULLY_QUALIFIED_PREFIX + "initialMaxData") != null);
		assertNotEquals("the fully-qualified system property spelling did not reach QuicConnectionSettings",
			MemSize.megabytes(1), QuicConnectionSettings.DEFAULT_INITIAL_MAX_DATA);
	}

	@Test
	public void initialMaxStreamsBidiShortFormSpellingReachesSettings() {
		assumeTrue("run with -DQuicConnection.initialMaxStreamsBidi=<count> to exercise this",
			System.getProperty(SHORT_PREFIX + "initialMaxStreamsBidi") != null);
		assertNotEquals("the short-form system property spelling did not reach QuicConnectionSettings",
			100, QuicConnectionSettings.DEFAULT_INITIAL_MAX_STREAMS_BIDI);
	}

	// ---- the builder carries every one of the nine through ----

	@Test
	public void everyWithXxxIsCarriedIntoTheBuiltValue() {
		QuicConnectionSettings settings = QuicConnectionSettings.builder()
			.withMaxSendQueueBytes(MemSize.megabytes(4))
			.withInitialMaxData(MemSize.megabytes(2))
			.withInitialMaxStreamDataBidiLocal(MemSize.kilobytes(100))
			.withInitialMaxStreamDataBidiRemote(MemSize.kilobytes(200))
			.withInitialMaxStreamDataUni(MemSize.kilobytes(300))
			.withInitialMaxStreamsBidi(7)
			.withInitialMaxStreamsUni(11)
			.withMaxOutstandingStreamBytes(MemSize.megabytes(1))
			.withMaxReceiveRangesPerStream(9)
			.withMaxPendingStreamOpens(13)
			.build();

		assertEquals(MemSize.megabytes(2).toLong(), settings.initialMaxData());
		assertEquals(MemSize.kilobytes(100).toLong(), settings.initialMaxStreamDataBidiLocal());
		assertEquals(MemSize.kilobytes(200).toLong(), settings.initialMaxStreamDataBidiRemote());
		assertEquals(MemSize.kilobytes(300).toLong(), settings.initialMaxStreamDataUni());
		assertEquals(7, settings.initialMaxStreamsBidi());
		assertEquals(11, settings.initialMaxStreamsUni());
		assertEquals(MemSize.megabytes(1).toLong(), settings.maxOutstandingStreamBytes());
		assertEquals(9, settings.maxReceiveRangesPerStream());
		assertEquals(13, settings.maxPendingStreamOpens());
	}

	@Test
	public void aWithXxxAfterBuildIsRejected() {
		// DI-4: build() is one-shot, and every new withXxx opens with checkNotBuilt.
		QuicConnectionSettings.Builder builder = QuicConnectionSettings.builder();
		builder.build();
		assertThrows(IllegalStateException.class, () -> builder.withInitialMaxData(MemSize.megabytes(2)));
		assertThrows(IllegalStateException.class,
			() -> builder.withInitialMaxStreamDataBidiLocal(MemSize.kilobytes(1)));
		assertThrows(IllegalStateException.class,
			() -> builder.withInitialMaxStreamDataBidiRemote(MemSize.kilobytes(1)));
		assertThrows(IllegalStateException.class, () -> builder.withInitialMaxStreamDataUni(MemSize.kilobytes(1)));
		assertThrows(IllegalStateException.class, () -> builder.withInitialMaxStreamsBidi(1));
		assertThrows(IllegalStateException.class, () -> builder.withInitialMaxStreamsUni(1));
		assertThrows(IllegalStateException.class, () -> builder.withMaxOutstandingStreamBytes(MemSize.kilobytes(1)));
		assertThrows(IllegalStateException.class, () -> builder.withMaxReceiveRangesPerStream(1));
		assertThrows(IllegalStateException.class, () -> builder.withMaxPendingStreamOpens(1));
	}

	// ---- cross-field validation 1: maxOutstandingStreamBytes < maxSendQueueBytes (FR-019) ----

	@Test
	public void maxOutstandingStreamBytesAtOrAboveTheSendQueueIsRejectedByName() {
		IllegalArgumentException equal = assertThrows(IllegalArgumentException.class, () ->
			QuicConnectionSettings.builder()
				.withMaxSendQueueBytes(MemSize.megabytes(1))
				.withMaxOutstandingStreamBytes(MemSize.megabytes(1))
				.build());
		assertNamesField(equal, "maxOutstandingStreamBytes");
		assertTrue("the message must say what breaks: " + equal.getMessage(),
			equal.getMessage().contains("maxSendQueueBytes"));
		assertTrue("the message must name the failure mode: " + equal.getMessage(),
			equal.getMessage().contains("INTERNAL_ERROR"));

		IllegalArgumentException above = assertThrows(IllegalArgumentException.class, () ->
			QuicConnectionSettings.builder()
				.withMaxSendQueueBytes(MemSize.megabytes(1))
				.withMaxOutstandingStreamBytes(MemSize.megabytes(2))
				.build());
		assertNamesField(above, "maxOutstandingStreamBytes");
	}

	@Test
	public void loweringTheSendQueueAloneIsRejected() {
		// The pair is validated, not each field in isolation: a 4 kB send queue is legal on its own and
		// illegal beside the default 512 kB per-stream allowance.
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () ->
			QuicConnectionSettings.builder()
				.withMaxSendQueueBytes(MemSize.kilobytes(4))
				.build());
		assertNamesField(e, "maxOutstandingStreamBytes");
	}

	@Test
	public void justBelowTheSendQueueIsAccepted() {
		QuicConnectionSettings settings = QuicConnectionSettings.builder()
			.withMaxSendQueueBytes(MemSize.megabytes(1))
			.withMaxOutstandingStreamBytes(MemSize.bytes(MemSize.megabytes(1).toLong() - 1))
			.build();
		assertEquals(MemSize.megabytes(1).toLong() - 1, settings.maxOutstandingStreamBytes());
	}

	// ---- cross-field validation 2: initialMaxStreamData* <= initialMaxData (FR-024) ----

	@Test
	public void aBidiLocalStreamWindowAboveTheConnectionWindowIsRejectedByName() {
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () ->
			QuicConnectionSettings.builder()
				.withInitialMaxData(MemSize.kilobytes(64))
				.withInitialMaxStreamDataBidiLocal(MemSize.kilobytes(128))
				.withInitialMaxStreamDataBidiRemote(MemSize.kilobytes(1))
				.withInitialMaxStreamDataUni(MemSize.kilobytes(1))
				.build());
		assertNamesField(e, "initialMaxStreamDataBidiLocal");
		assertTrue("the message must say what it may not exceed: " + e.getMessage(),
			e.getMessage().contains("initialMaxData"));
	}

	@Test
	public void aBidiRemoteStreamWindowAboveTheConnectionWindowIsRejectedByName() {
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () ->
			QuicConnectionSettings.builder()
				.withInitialMaxData(MemSize.kilobytes(64))
				.withInitialMaxStreamDataBidiLocal(MemSize.kilobytes(1))
				.withInitialMaxStreamDataBidiRemote(MemSize.kilobytes(128))
				.withInitialMaxStreamDataUni(MemSize.kilobytes(1))
				.build());
		assertNamesField(e, "initialMaxStreamDataBidiRemote");
	}

	@Test
	public void aUniStreamWindowAboveTheConnectionWindowIsRejectedByName() {
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () ->
			QuicConnectionSettings.builder()
				.withInitialMaxData(MemSize.kilobytes(64))
				.withInitialMaxStreamDataBidiLocal(MemSize.kilobytes(1))
				.withInitialMaxStreamDataBidiRemote(MemSize.kilobytes(1))
				.withInitialMaxStreamDataUni(MemSize.kilobytes(128))
				.build());
		assertNamesField(e, "initialMaxStreamDataUni");
	}

	@Test
	public void loweringTheConnectionWindowAloneIsRejected() {
		// Same asymmetry as above: the defaults are 256 kB per stream, so a 64 kB connection window is
		// only illegal in combination.
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () ->
			QuicConnectionSettings.builder()
				.withInitialMaxData(MemSize.kilobytes(64))
				.build());
		assertTrue("the message must name one of the initialMaxStreamData* fields: " + e.getMessage(),
			e.getMessage().startsWith("initialMaxStreamData"));
	}

	@Test
	public void equalStreamAndConnectionWindowsAreAccepted() {
		QuicConnectionSettings settings = QuicConnectionSettings.builder()
			.withInitialMaxData(MemSize.kilobytes(256))
			.withInitialMaxStreamDataBidiLocal(MemSize.kilobytes(256))
			.withInitialMaxStreamDataBidiRemote(MemSize.kilobytes(256))
			.withInitialMaxStreamDataUni(MemSize.kilobytes(256))
			.build();
		assertEquals(MemSize.kilobytes(256).toLong(), settings.initialMaxData());
	}

	// ---- the local-only counts ----

	@Test
	public void nonPositiveLocalCountsAreRejectedByName() {
		IllegalArgumentException ranges = assertThrows(IllegalArgumentException.class, () ->
			QuicConnectionSettings.builder().withMaxReceiveRangesPerStream(0).build());
		assertNamesField(ranges, "maxReceiveRangesPerStream");

		IllegalArgumentException opens = assertThrows(IllegalArgumentException.class, () ->
			QuicConnectionSettings.builder().withMaxPendingStreamOpens(-1).build());
		assertNamesField(opens, "maxPendingStreamOpens");
	}

	@Test
	public void streamCountsAboveTheRfc2To60CapAreRejectedByName() {
		IllegalArgumentException bidi = assertThrows(IllegalArgumentException.class, () ->
			QuicConnectionSettings.builder().withInitialMaxStreamsBidi((1L << 60) + 1).build());
		assertNamesField(bidi, "initialMaxStreamsBidi");

		IllegalArgumentException uni = assertThrows(IllegalArgumentException.class, () ->
			QuicConnectionSettings.builder().withInitialMaxStreamsUni((1L << 60) + 1).build());
		assertNamesField(uni, "initialMaxStreamsUni");
	}

	@Test
	public void negativeStreamCountsAreRejectedByName() {
		IllegalArgumentException bidi = assertThrows(IllegalArgumentException.class, () ->
			QuicConnectionSettings.builder().withInitialMaxStreamsBidi(-1).build());
		assertNamesField(bidi, "initialMaxStreamsBidi");

		IllegalArgumentException uni = assertThrows(IllegalArgumentException.class, () ->
			QuicConnectionSettings.builder().withInitialMaxStreamsUni(-1).build());
		assertNamesField(uni, "initialMaxStreamsUni");
	}

	@Test
	public void theRfc2To60CapItselfIsAccepted() {
		QuicConnectionSettings settings = QuicConnectionSettings.builder()
			.withInitialMaxStreamsBidi(1L << 60)
			.withInitialMaxStreamsUni(1L << 60)
			.build();
		assertEquals(1L << 60, settings.initialMaxStreamsBidi());
		assertEquals(1L << 60, settings.initialMaxStreamsUni());
	}

	@Test
	public void toStringCarriesTheNineNewFields() {
		String text = QuicConnectionSettings.create().toString();
		for (String key : allKeys()) {
			assertTrue("toString must mention " + key + ": " + text, text.contains(key + "="));
		}
	}

	private static void assumeNoOverride() {
		assumeTrue("this JVM carries a QuicConnection.* override, so the shipped defaults do not apply",
			!STARTED_WITH_AN_OVERRIDE);
	}

	private static void assertNamesField(IllegalArgumentException e, String field) {
		assertTrue("the message must name the offending field '" + field + "': " + e.getMessage(),
			e.getMessage().startsWith(field + " "));
	}
}
