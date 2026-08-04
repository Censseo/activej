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

package io.activej.http3.testutil;

import io.activej.eventloop.Eventloop;
import io.activej.reactor.Reactor;
import io.activej.test.time.TestCurrentTimeProvider;
import io.activej.test.time.TestCurrentTimeProvider.SettableCurrentTimeProvider;
import org.jetbrains.annotations.Nullable;

import static io.activej.common.exception.FatalErrorHandlers.rethrow;

/**
 * An {@link Eventloop} whose clock the test drives by hand, installed as this thread's current reactor
 * for the duration of the test.
 * <p>
 * <b>Why this exists.</b> {@code EventloopRule} builds its eventloop on the system clock, so a QUIC
 * connection's {@code reactor.delay(...)} deadlines would live on real time while
 * {@link StubDatagramNetwork}'s delivery lives on the fixture clock. The two cannot be stepped
 * together, which makes every timer-driven behaviour — loss detection, probe timeouts, the idle
 * timeout — untestable without sleeping. Building the eventloop on the <i>same</i> clock as the wire
 * is what makes an HTTP/3 exchange deterministic.
 * <p>
 * <b>Time never moves on its own</b>, and neither does the loop: {@link #tick()} runs exactly one
 * eventloop iteration, so a timer fires only once its deadline is at or behind the hand-set clock. A
 * test that forgets to advance will see nothing happen rather than see it happen slowly.
 * <p>
 * <b>Always close it</b> in {@code @After}: {@link #close()} restores the previous current reactor,
 * without which a frozen clock would leak into every later test class on this thread — which shows up
 * as an unrelated test hanging, the single most confusing failure this fixture can cause.
 * <p>
 * A port of {@code io.activej.quic.connection.testutil.ManualEventloop}, which is test-scope in
 * {@code core-quic} and therefore not visible here (research Decision 12). Built entirely from
 * public API, so there is nothing to keep in sync but the behaviour.
 */
public final class ManualEventloop implements AutoCloseable {
	/**
	 * How many iterations {@link #tickUntilQuiet()} runs. A cascade of timer → send → post → timer
	 * settles in two or three; the rest are cheap no-ops (a {@code selectNow} and four empty queue
	 * drains), and a fixed count is used because the eventloop exposes no "did anything happen" signal
	 * — its loop counter advances whether or not the iteration did work.
	 */
	private static final int ITERATIONS_PER_QUIET = 8;

	private final SettableCurrentTimeProvider clock;
	private final Eventloop eventloop;
	private final @Nullable Reactor previous;

	private long now;

	public ManualEventloop() {
		this(1_000_000);
	}

	public ManualEventloop(long startMillis) {
		this.previous = Reactor.getCurrentReactorOrNull();
		this.now = startMillis;
		this.clock = settableClock(startMillis);
		// withCurrentThread is what installs it as the thread's reactor; rethrow() matches EventloopRule,
		// so a fatal error inside a timer fails the test rather than being logged and lost.
		this.eventloop = Eventloop.builder()
			.withCurrentThread()
			.withTimeProvider(clock)
			.withFatalErrorHandler(rethrow())
			.build();
	}

	/**
	 * A clock the test drives by hand.
	 * <p>
	 * Note the API: {@code TestCurrentTimeProvider.settable} takes a {@code CurrentTimeProvider}, not a
	 * timestamp, and the mutator is {@code setTimeProvider} — there is no {@code setTime(long)}. That is
	 * why {@link #setTime} exists, so no test has to know.
	 */
	public static SettableCurrentTimeProvider settableClock(long startMillis) {
		return TestCurrentTimeProvider.settable(TestCurrentTimeProvider.ofConstant(startMillis));
	}

	public Eventloop eventloop() {
		return eventloop;
	}

	public SettableCurrentTimeProvider clock() {
		return clock;
	}

	public long currentTimeMillis() {
		return now;
	}

	/**
	 * Moves the clock to an absolute time without running anything.
	 * <p>
	 * On its own this changes nothing a connection can see: {@link Eventloop} caches its timestamp per
	 * iteration and refreshes it from the time provider at the top of the loop, so
	 * {@code reactor.currentTimeMillis()} still returns the previous value until {@link #tick()}. Use
	 * {@link #advance} unless you specifically want that gap.
	 */
	public void setTime(long millis) {
		if (millis < now) throw new IllegalArgumentException("Time must not go backwards: " + millis + " < " + now);
		now = millis;
		clock.setTimeProvider(TestCurrentTimeProvider.ofConstant(millis));
	}

	/**
	 * Runs exactly one eventloop iteration, firing every scheduled and background task whose deadline
	 * is at or behind the current hand-set time.
	 * <p>
	 * The posted {@code breakEventloop} is what bounds it: with a local task queued the select timeout
	 * is 0, so the iteration cannot block, and breaking after it stops {@code run()} from looping on a
	 * future deadline that a frozen clock would never reach — which would spin in <i>real</i> time
	 * forever.
	 */
	public void tick() {
		eventloop.post(eventloop::breakEventloop);
		eventloop.run();
	}

	/** Ticks enough times that a timer which schedules further work runs to a standstill. */
	public void tickUntilQuiet() {
		for (int i = 0; i < ITERATIONS_PER_QUIET; i++) {
			tick();
		}
	}

	/** Moves the clock forward, then lets everything that came due run. */
	public void advance(long deltaMillis) {
		setTime(now + deltaMillis);
		tickUntilQuiet();
	}

	@Override
	public void close() {
		if (previous != null) {
			Reactor.setCurrentReactor(previous);
		}
	}
}
