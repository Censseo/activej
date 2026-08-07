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

package io.activej.http3.interop;

import io.activej.test.rules.ByteBufRule;
import org.junit.ClassRule;
import org.junit.Test;

import java.net.InetSocketAddress;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Proves the two properties the interop fixture exists for (T010a, FR-010, FR-011):
 * the socket is bound to the <b>loopback</b> address — never the wildcard, so the suite cannot
 * expose a server beyond loopback and needs no container runtime — and teardown is idempotent and
 * runs on every path, including a throw during setup, so {@link ByteBufRule} finds nothing.
 */
public final class Http3ServerReactorFixtureTest {
	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	@Test
	public void bindsLoopbackAndReadsTheKeptPort() {
		Http3ServerReactorFixture fixture = new Http3ServerReactorFixture(InteropTestServlet::create);
		try {
			InetSocketAddress bound = fixture.boundAddress();
			assertTrue("bound port must be > 0 (read off the kept :0 socket): " + bound, bound.getPort() > 0);
			assertTrue("must bind the loopback address, not the wildcard: " + bound,
				bound.getAddress().isLoopbackAddress());
			assertFalse("any-local binding would expose the suite beyond loopback: " + bound,
				bound.getAddress().isAnyLocalAddress());
			assertEquals(fixture.boundAddress().getPort(), fixture.port());

			// The submit bridge and the counter accessors work from the JUnit thread (FR-012).
			assertEquals(0L, fixture.connectionsAccepted());
			assertEquals(0L, fixture.requestsServed());
			fixture.submit(() -> {
				// a no-op is still a reactor-hop proof: it must run without a thread-guard failure
			});
			assertEquals("echo", fixture.submit(() -> "echo"));
		} finally {
			fixture.close();
		}
	}

	@Test
	public void closeIsIdempotent() {
		Http3ServerReactorFixture fixture = new Http3ServerReactorFixture(InteropTestServlet::create);
		fixture.close();
		fixture.close(); // a second close must be a no-op, not an error
	}

	@Test
	public void throwDuringSetupStillTearsDownLeakFree() {
		try {
			new Http3ServerReactorFixture(reactor -> {
				throw new RuntimeException("injected setup failure");
			});
			fail("Expected the injected setup failure to fail construction");
		} catch (IllegalStateException expected) {
			assertTrue(expected.getMessage().contains("Failed to start"));
			// The fixture's own close() ran inside the constructor; the reactor thread was joined.
			// ByteBufRule evaluates after the method and finds nothing.
		}
	}
}
