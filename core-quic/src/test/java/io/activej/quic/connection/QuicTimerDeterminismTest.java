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

import io.activej.quic.connection.testutil.ManualEventloop;
import io.activej.quic.connection.testutil.QuicWirePair;
import io.activej.test.rules.ActivePromisesRule;
import io.activej.test.rules.ByteBufRule;
import io.activej.test.rules.EventloopRule;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.Assert.*;

/**
 * T057 — SC-011: the connection layer's timers are asserted on a hand-set clock, never by waiting.
 * <p>
 * Two halves, because either alone is insufficient. The first is a property of the <i>suite</i>: no
 * test in this package may sleep, since a sleeping test is both slow and — under a loaded CI box
 * running {@code -T1C} — flaky in the direction of passing when it should fail. The second is a
 * property of the <i>clock</i>: an eventloop timer must be driven by the same clock the wire is, or
 * advancing one moves nothing.
 */
public final class QuicTimerDeterminismTest {
	@ClassRule
	public static final EventloopRule eventloopRule = new EventloopRule();

	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	@Rule
	public final ActivePromisesRule activePromisesRule = new ActivePromisesRule();

	private static final Path TEST_SOURCES = Path.of("src/test/java/io/activej/quic/connection");

	/** The forbidden constructs, each of which makes a test's duration depend on real time. */
	private static final List<String> FORBIDDEN = List.of(
		"Thread.sleep", "TimeUnit.SECONDS.sleep", "TimeUnit.MILLISECONDS.sleep", "System.nanoTime()");

	@Test
	public void noConnectionLayerTestSleepsOrReadsTheSystemClock() throws IOException {
		assertTrue("the source tree moved: " + TEST_SOURCES.toAbsolutePath(), Files.isDirectory(TEST_SOURCES));

		List<String> offenders = new ArrayList<>();
		try (Stream<Path> sources = Files.walk(TEST_SOURCES)) {
			for (Path source : sources.filter(p -> p.toString().endsWith(".java")).toList()) {
				if (source.getFileName().toString().equals("QuicTimerDeterminismTest.java")) {
					// This file names the constructs in order to forbid them.
					continue;
				}
				String text = Files.readString(source, StandardCharsets.UTF_8);
				for (String forbidden : FORBIDDEN) {
					if (text.contains(forbidden)) {
						offenders.add(source.getFileName() + " uses " + forbidden);
					}
				}
			}
		}
		assertEquals("timer assertions must run on a hand-set clock (SC-011)", List.of(), offenders);
	}

	@Test
	public void theEventloopAndTheWireShareOneClock() {
		try (ManualEventloop loop = new ManualEventloop(500_000)) {
			// The whole point of ManualEventloop: reactor.currentTimeMillis() is the value the test set,
			// so a deadline computed from it is a deadline the test can reach by arithmetic.
			assertEquals(500_000, loop.eventloop().currentTimeMillis());

			// Note the tick. Eventloop caches its timestamp per iteration and refreshes it from the time
			// provider at the top of the loop, so setTime alone moves the provider but not what a
			// connection reads — which is why advance() ticks rather than only setting the clock.
			loop.setTime(500_123);
			assertEquals("the cached timestamp should not have moved yet",
				500_000, loop.eventloop().currentTimeMillis());
			loop.tick();
			assertEquals(500_123, loop.eventloop().currentTimeMillis());
		}
	}

	@Test
	public void aTimerFiresOnlyOnceTheHandSetClockReachesIt() {
		try (ManualEventloop loop = new ManualEventloop()) {
			boolean[] fired = {false};
			loop.eventloop().delay(1000, () -> fired[0] = true);

			loop.advance(999);
			assertFalse("the timer fired early", fired[0]);

			loop.advance(2);
			assertTrue("the timer did not fire once its deadline passed", fired[0]);
		}
	}

	@Test
	public void aSuiteRunAtAThirtySecondTimeoutCostsNoMoreThanOneAtTenMilliseconds() throws Exception {
		// SC-011's actual claim: duration does not scale with the configured timeouts. Both of these run
		// a full handshake and then run its idle timeout out — one nominally 3000× longer than the other.
		// If either waited on real time, this test would take half a minute; it takes milliseconds.
		assertTrue(idleOutTakesEffect(Duration.ofMillis(10)));
		assertTrue(idleOutTakesEffect(Duration.ofSeconds(30)));
	}

	private static boolean idleOutTakesEffect(Duration idleTimeout) throws Exception {
		try (ManualEventloop loop = new ManualEventloop();
			 QuicWirePair wire = new QuicWirePair()) {
			wire.handshake(QuicConnectionSettings.builder().withMaxIdleTimeout(idleTimeout).build());
			QuicConnection client = wire.client();
			loop.advance(client.effectiveIdleTimeoutMillis() + 1);
			return client.state() == QuicConnectionState.CLOSED;
		}
	}
}
