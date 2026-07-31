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

import io.activej.common.MemSize;
import org.junit.Test;

import java.time.Duration;

import static org.junit.Assert.assertNotEquals;
import static org.junit.Assume.assumeTrue;

/**
 * QA gap 6.7 — T006 was a regression where every {@code ApplicationSettings} key for this class
 * resolved from {@code QuicConnectionSettings.class} rather than {@code QuicConnection.class}, so every
 * documented override silently had no effect. The fix is in; this guards against it recurring.
 * <p>
 * {@code ApplicationSettings} values are {@code public static final} fields resolved once at
 * class-init time (DI-5), so the only way to observe an override is a fresh JVM where the system
 * property is already set when {@link QuicConnectionSettings} first loads. Under the ordinary headline
 * run none of these properties are set, so every test here is skipped ({@code Assume}) rather than
 * failed. To actually exercise it, run this class alone — sharing a fork with any test that already
 * touched {@link QuicConnectionSettings} would defeat the point:
 * <pre>
 * mvn -pl core-quic test -Dtest=QuicApplicationSettingsOverrideTest -Dsurefire.failIfNoSpecifiedTests=false \
 *   -DargLine='-DQuicConnection.maxIdleTimeout="45 seconds" -Dio.activej.quic.connection.QuicConnection.maxDatagramSize=1400b -DQuicConnection.maxAckRanges=99'
 * </pre>
 * Each test below only asserts that the resolved default moved <i>away from</i> its documented value,
 * not what it moved to — that is enough to prove the property reached this class at all, without this
 * test needing to duplicate {@code ApplicationSettings}' own string-parsing rules.
 * <p>
 * Note the quoting: {@link io.activej.common.StringFormatUtils#parseDuration} requires a space between
 * the number and a full-word unit ({@code days|hours|minutes|seconds|millis|nanos}, singular or
 * plural) — a compact suffix like {@code 45s} does not parse. The outer single quotes keep Maven's
 * {@code -DargLine} from splitting on that space; the {@code MemSize} spelling has no such requirement.
 */
public final class QuicApplicationSettingsOverrideTest {

	@Test
	public void maxIdleTimeoutShortFormSpellingReachesSettings() {
		assumeTrue("run with -DQuicConnection.maxIdleTimeout=<duration> to exercise this (qa-test-plan.md §6.7)",
			System.getProperty("QuicConnection.maxIdleTimeout") != null);
		assertNotEquals("the short-form system property spelling did not reach QuicConnectionSettings",
			Duration.ofSeconds(30), QuicConnectionSettings.DEFAULT_MAX_IDLE_TIMEOUT);
	}

	@Test
	public void maxDatagramSizeFullyQualifiedSpellingReachesSettings() {
		assumeTrue(
			"run with -Dio.activej.quic.connection.QuicConnection.maxDatagramSize=<size> to exercise this (qa-test-plan.md §6.7)",
			System.getProperty("io.activej.quic.connection.QuicConnection.maxDatagramSize") != null);
		assertNotEquals("the fully-qualified system property spelling did not reach QuicConnectionSettings",
			MemSize.bytes(1350), QuicConnectionSettings.DEFAULT_MAX_DATAGRAM_SIZE);
	}

	@Test
	public void maxAckRangesShortFormSpellingReachesSettings() {
		assumeTrue("run with -DQuicConnection.maxAckRanges=<count> to exercise this (qa-test-plan.md §6.7)",
			System.getProperty("QuicConnection.maxAckRanges") != null);
		assertNotEquals("the short-form system property spelling did not reach QuicConnectionSettings",
			32, QuicConnectionSettings.DEFAULT_MAX_ACK_RANGES);
	}
}
