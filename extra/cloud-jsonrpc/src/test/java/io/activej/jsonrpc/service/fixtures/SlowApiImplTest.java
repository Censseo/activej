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

package io.activej.jsonrpc.service.fixtures;

import io.activej.jsonrpc.service.JsonRpcDispatcher;
import io.activej.promise.Promise;
import io.activej.reactor.Reactor;
import io.activej.test.ExpectedException;
import io.activej.test.rules.EventloopRule;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;

import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Pins {@link SlowApiImpl}'s own behaviour, for the same reason {@link InMemoryTransportTest} pins the
 * transport double's: a concurrency bound is asserted <i>through</i> this fixture, so an invocation that
 * completed when the test believed it was still pending would move the number under assertion and blame the
 * dispatcher for it.
 */
public class SlowApiImplTest {
	@ClassRule
	public static final EventloopRule eventloopRule = new EventloopRule();

	private SlowApiImpl api;

	@Before
	public void setUp() {
		api = new SlowApiImpl();
	}

	@Test
	public void theInterfaceIsAValidContract() {
		// a fixture that cannot be registered is discovered here, not in the suite that needed it
		JsonRpcDispatcher dispatcher = JsonRpcDispatcher.builder(Reactor.getCurrentReactor())
			.withService(SlowApi.class, api)
			.build();

		assertEquals(Set.of("slow.call", "slow.notify"), dispatcher.wireNames());
	}

	@Test
	public void anInvocationIsStillPendingWhenTheMethodReturns() {
		Promise<String> promise = api.call("a");

		assertFalse("the whole point: the handler returned and the invocation did not complete",
			promise.isComplete());
		assertEquals(1, api.pendingCount());
		assertEquals(List.of("a"), api.pendingTags());
		assertEquals(List.of("slow.call"), api.pendingWireNames());
	}

	@Test
	public void aReleasedCallCompletesWithItsOwnTag() {
		Promise<String> promise = api.call("a");

		api.releaseAll();

		assertTrue(promise.isResult());
		assertEquals("a", promise.getResult());
		assertEquals(0, api.pendingCount());
	}

	@Test
	public void aNotificationPendsAndCompletesTheSameWay() {
		Promise<Void> promise = api.notifySlowly("n");

		assertFalse(promise.isComplete());
		assertEquals(List.of("slow.notify"), api.pendingWireNames());

		api.releaseAll();

		assertTrue(promise.isResult());
	}

	@Test
	public void releaseNextIsOldestFirst() {
		Promise<String> first = api.call("a");
		Promise<String> second = api.call("b");

		api.releaseNext();

		assertTrue(first.isResult());
		assertFalse(second.isComplete());
		assertEquals(List.of("b"), api.pendingTags());
	}

	@Test
	public void aPendingInvocationCanBeReleasedByIndex() {
		Promise<String> first = api.call("a");
		Promise<String> second = api.call("b");

		api.release(1);

		assertFalse(first.isComplete());
		assertTrue(second.isResult());
		assertEquals("b", second.getResult());
	}

	@Test
	public void failureIsTheOtherCompletionPath() {
		// an in-flight counter must decrement here exactly as it does on success
		ExpectedException cause = new ExpectedException("boom");
		Promise<String> promise = api.call("a");
		Promise<Void> notification = api.notifySlowly("n");

		api.failAll(cause);

		assertSame(cause, promise.getException());
		assertSame(cause, notification.getException());
		assertEquals(0, api.pendingCount());
	}

	@Test
	public void releasingNothingFails() {
		try {
			api.releaseNext();
			fail("a fixture must not silently ignore a release nobody can satisfy");
		} catch (IndexOutOfBoundsException e) {
			// expected
		}
	}

	@Test
	public void everyInvocationIsRecordedIncludingCompletedOnes() {
		api.call("a");
		api.notifySlowly("n");
		api.releaseAll();
		api.call("b");

		assertEquals(List.of("call(a)", "notify(n)", "call(b)"), api.invocations());
		assertEquals(List.of("b"), api.pendingTags());
	}
}
