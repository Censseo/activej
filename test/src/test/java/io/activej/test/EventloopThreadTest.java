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

package io.activej.test;

import io.activej.reactor.Reactor;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public final class EventloopThreadTest {

	@Test
	public void submitRunsOnTheLoopThreadNotTheCaller() {
		try (EventloopThread loop = EventloopThread.create("test-submits-here")) {
			String caller = Thread.currentThread().getName();
			String runner = loop.submit(() -> Thread.currentThread().getName());

			assertEquals("test-submits-here", runner);
			assertNotEquals(caller, runner);
			// and the task really ran inside the reactor, not merely on that thread.
			// The cast keeps the lambda value-compatible only: Reactor.getCurrentReactor() is generic,
			// so without it the body is a statement expression and submit(RunnableEx) is ambiguous.
			Reactor onLoop = loop.submit(() -> (Reactor) Reactor.getCurrentReactor());
			assertSame(loop.eventloop(), onLoop);
			assertTrue(loop.submit(() -> loop.eventloop().inReactorThread()));
		}
	}

	@Test
	public void submitRunnableBlocksUntilTheActionHasRun() {
		try (EventloopThread loop = EventloopThread.create("test-runnable")) {
			AtomicReference<String> ran = new AtomicReference<>();
			loop.submit(() -> ran.set(Thread.currentThread().getName()));

			// no polling: submit(RunnableEx) must not return before the action completed
			assertEquals("test-runnable", ran.get());
		}
	}

	@Test
	public void aFailingComputationSurfacesItsOwnCause() {
		try (EventloopThread loop = EventloopThread.create("test-failure")) {
			IllegalStateException e = assertThrows(IllegalStateException.class,
				() -> loop.submit(() -> { throw new ExpectedException(); }));

			assertTrue(e.getCause() instanceof ExpectedException);
		}
	}

	@Test
	public void closeRunsRegisteredActionsOnTheLoopInReverseOrder() {
		List<String> order = new ArrayList<>();
		EventloopThread loop = EventloopThread.create("test-close-actions");
		loop.onClose(() -> order.add("first:" + Thread.currentThread().getName()));
		loop.onClose(() -> order.add("second:" + Thread.currentThread().getName()));
		loop.close();

		// reverse registration order, and both on the loop thread — not the JUnit thread
		assertEquals(
			List.of("second:test-close-actions", "first:test-close-actions"),
			order);
	}

	@Test
	public void closeIsIdempotentAndReapsTheThread() {
		EventloopThread loop = EventloopThread.create("test-idempotent-close");
		assertTrue(isThreadAlive("test-idempotent-close"));

		loop.close();
		loop.close(); // a second close must be a no-op, not an error

		assertFalse(isThreadAlive("test-idempotent-close"));
	}

	@Test
	public void closeAfterAFailedSetupStillReapsTheThread() {
		EventloopThread loop = EventloopThread.create("test-failed-setup");
		try {
			loop.submit(() -> { throw new ExpectedException(); });
		} catch (IllegalStateException expected) {
			loop.close();
		}

		// the keep-alive must not outlive a setup that never registered a selector key
		assertFalse(isThreadAlive("test-failed-setup"));
	}

	@Test
	public void aFailingCloseActionIsReportedRatherThanSwallowed() {
		EventloopThread loop = EventloopThread.create("test-failing-close");
		loop.onClose(() -> { throw new ExpectedException(); });

		IllegalStateException e = assertThrows(IllegalStateException.class, loop::close);

		assertTrue(e.getMessage().contains("test-failing-close"));
		// the thread is still reaped — a failed close action must not leak the loop
		assertFalse(isThreadAlive("test-failing-close"));
	}

	private static boolean isThreadAlive(String name) {
		Set<Thread> threads = Thread.getAllStackTraces().keySet();
		return threads.stream().anyMatch(t -> t.getName().equals(name) && t.isAlive());
	}
}
