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

package io.activej.launchers.jsonrpc;

import io.activej.async.exception.AsyncTimeoutException;
import io.activej.config.Config;
import io.activej.http.HttpResponse;
import io.activej.http.IHttpClient;
import io.activej.inject.Injector;
import io.activej.inject.annotation.Provides;
import io.activej.inject.binding.DIException;
import io.activej.inject.module.AbstractModule;
import io.activej.jsonrpc.service.JsonRpcClient;
import io.activej.launchers.jsonrpc.fixtures.User;
import io.activej.launchers.jsonrpc.fixtures.UserApi;
import io.activej.promise.Promise;
import io.activej.promise.Promises;
import io.activej.promise.SettablePromise;
import io.activej.reactor.Reactor;
import io.activej.reactor.nio.NioReactor;
import io.activej.test.ExpectedException;
import io.activej.test.rules.ActivePromisesRule;
import io.activej.test.rules.ByteBufRule;
import io.activej.test.rules.EventloopRule;
import org.junit.After;
import org.junit.ClassRule;
import org.junit.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static io.activej.promise.TestUtils.await;
import static io.activej.promise.TestUtils.awaitException;
import static org.hamcrest.CoreMatchers.instanceOf;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * US2 (FR-021, contracts/settings-and-errors.md §4): {@code jsonrpc.callTimeout} is a <b>real key</b> of the
 * client module — feature 014 reserved it as a fail-closed non-key precisely so that feature 09 could admit
 * it, and this is that admission.
 *
 * <h2>Why the assertion is behavioural</h2>
 * {@link JsonRpcClient} publishes no read-back of its configured timeout, and adding one purely so a test can
 * read it would be API for the test's benefit. So the wiring is asserted by the only thing that matters: a
 * call over a peer that never answers expires <b>after the configured delay</b>, and the
 * {@link AsyncTimeoutException} names that delay back. A key that were read and dropped could not produce it.
 * <p>
 * The peer is an {@link IHttpClient} that accepts every request and answers none — the client module's own
 * {@code JsonRpcHttpClientTransport} sits on top of it unmodified, so what is exercised is the module's real
 * provider chain and not a hand-built client.
 *
 * <h2>The eventloop has to be kept alive by hand</h2>
 * The deadline is a <b>background</b> scheduled task and background tasks do not keep an {@code Eventloop}
 * alive, so {@code await(call)} would exit the loop and then block forever. Every timing assertion here goes
 * through {@link Promises#timeout}, whose own task is a foreground one, with a guard an order of magnitude
 * above anything under test.
 */
public class JsonRpcClientModuleConfigTest {
	@ClassRule
	public static final EventloopRule eventloopRule = new EventloopRule();
	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();
	@ClassRule
	public static final ActivePromisesRule activePromisesRule = new ActivePromisesRule();

	/** The harness's upper bound — never the thing under test. */
	private static final Duration GUARD = Duration.ofSeconds(5);

	private final List<JsonRpcClient> clients = new ArrayList<>();

	@After
	public void tearDown() {
		// a client left open keeps an armed background task around for the next test in this class
		for (JsonRpcClient client : clients) {
			client.closeEx(new ExpectedException("end of test"));
		}
		clients.clear();
	}

	@Test
	public void callTimeoutIsAdmittedAndReachesTheClientBuilder() {
		JsonRpcClient client = client(Config.create().with("jsonrpc.callTimeout", "20 millis"));
		UserApi api = client.proxy(UserApi.class);

		Exception e = awaitException(Promises.timeout(GUARD, api.getUser(1)));

		assertThat(e, instanceOf(AsyncTimeoutException.class));
		assertTrue("the wire name must be named: " + e.getMessage(), e.getMessage().contains("user.get"));
		assertTrue("the CONFIGURED delay must be named — a dropped key could not produce it: " + e.getMessage(),
			e.getMessage().contains("20 millis"));
		assertEquals(0, client.inFlightCount());
	}

	@Test
	public void anAbsentKeyLeavesTheApplicationSettingsDefaultInForce() {
		JsonRpcClient client = client(Config.create());
		UserApi api = client.proxy(UserApi.class);

		Promise<User> call = api.getUser(1);
		await(Promises.delay(Duration.ofMillis(80)));

		assertEquals("the default is 30 seconds", Duration.ofSeconds(30), JsonRpcClient.CALL_TIMEOUT);
		assertFalse("so nothing expires in an 80 ms window", call.isComplete());
		assertEquals(1, client.inFlightCount());
	}

	@Test
	public void zeroIsAcceptedAsTheDocumentedOptOut() {
		// the opt-out has to survive the whole chain: the config converter, the provider and the builder's
		// own validation — a naive "must be positive" check anywhere would take it out
		JsonRpcClient client = client(Config.create().with("jsonrpc.callTimeout", "0 seconds"));
		UserApi api = client.proxy(UserApi.class);

		Promise<User> call = api.getUser(1);
		await(Promises.delay(Duration.ofMillis(80)));

		assertFalse(call.isComplete());
		assertEquals(1, client.inFlightCount());
	}

	@Test
	public void aNegativeCallTimeoutFailsWiringNamingTheSetting() {
		DIException wrapper = assertThrows(DIException.class,
			() -> client(Config.create().with("jsonrpc.callTimeout", "-1 seconds")));

		assertThat(wrapper.getCause(), instanceOf(IllegalArgumentException.class));
		assertTrue("unexpected message: " + wrapper.getCause().getMessage(),
			wrapper.getCause().getMessage().contains("callTimeout"));
	}

	@Test
	public void theDurationFormatTrapFailsLoudlyRatherThanSilently() {
		// the module's standing Duration trap: whitespace is REQUIRED and only long-form units parse, so
		// "20ms" — which MemSize's lenient "10mb" makes look plausible — is not a duration at all
		assertThrows(Exception.class, () -> client(Config.create().with("jsonrpc.callTimeout", "20ms")));
	}

	// -------------------------------------------------------------------------------------------
	// Harness.
	// -------------------------------------------------------------------------------------------

	private JsonRpcClient client(Config config) {
		Injector injector = Injector.of(new JsonRpcClientModule(), new AbstractModule() {
			@Provides
			NioReactor reactor() {
				return Reactor.getCurrentReactor();
			}

			@Provides
			Config config() {
				return config;
			}

			@Provides
			IHttpClient httpClient() {
				return new SilentHttpClient();
			}
		});
		JsonRpcClient client = injector.getInstance(JsonRpcClient.class);
		clients.add(client);
		return client;
	}

	/**
	 * Accepts every request and answers none — the HTTP shape of "the server never responds", and the only
	 * peer against which a client-side deadline is observable at all. It opens no socket, so the test reactor
	 * never acquires a selector key that would outlive an {@code await}.
	 */
	private static final class SilentHttpClient implements IHttpClient {
		@Override
		public Promise<HttpResponse> request(io.activej.http.HttpRequest request) {
			return new SettablePromise<>();
		}
	}
}
