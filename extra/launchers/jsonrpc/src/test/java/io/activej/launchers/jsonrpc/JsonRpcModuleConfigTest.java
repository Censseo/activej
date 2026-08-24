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

import io.activej.config.Config;
import io.activej.dns.DnsClient;
import io.activej.http.HttpClient;
import io.activej.http.HttpHeaders;
import io.activej.http.HttpRequest;
import io.activej.http.HttpServer;
import io.activej.http.HttpUtils;
import io.activej.inject.Injector;
import io.activej.inject.binding.DIException;
import io.activej.inject.annotation.Provides;
import io.activej.inject.annotation.ProvidesIntoSet;
import io.activej.inject.module.AbstractModule;
import io.activej.jsonrpc.service.JsonRpcDispatcher;
import io.activej.launchers.jsonrpc.fixtures.User;
import io.activej.launchers.jsonrpc.fixtures.UserApi;
import io.activej.launchers.jsonrpc.fixtures.UserApiImpl;
import io.activej.promise.Promise;
import io.activej.promise.Promises;
import io.activej.promise.SettablePromise;
import io.activej.reactor.Reactor;
import io.activej.reactor.nio.NioReactor;
import io.activej.test.EventloopThread;
import io.activej.test.rules.ActivePromisesRule;
import io.activej.test.rules.ByteBufRule;
import io.activej.test.rules.EventloopRule;
import org.junit.After;
import org.junit.ClassRule;
import org.junit.Test;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static io.activej.promise.TestUtils.await;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Objects.requireNonNull;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * US1 scenario 2 (FR-020…FR-023): the {@code jsonrpc.*} config keys drive the servlet and the router —
 * exercised through a bare {@link Injector} with {@link JsonRpcModule}, no launcher involved.
 * <p>
 * Each server runs on its own {@link EventloopThread}: a server listening on the <i>test</i> reactor
 * would keep that eventloop alive forever, and {@code TestUtils.await} would never return.
 */
public class JsonRpcModuleConfigTest {
	@ClassRule
	public static final EventloopRule eventloopRule = new EventloopRule();
	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();
	@ClassRule
	public static final ActivePromisesRule activePromisesRule = new ActivePromisesRule();

	private final List<EventloopThread> loops = new ArrayList<>();

	@After
	public void tearDown() throws Exception {
		for (EventloopThread loop : loops) {
			loop.close();
		}
		loops.clear();
	}

	private HttpServer startServer(Config config) throws Exception {
		EventloopThread loop = EventloopThread.create("jsonrpc-module-config");
		loops.add(loop);
		Injector injector = Injector.of(new JsonRpcModule(), new AbstractModule() {
			@Provides
			NioReactor reactor() {
				return loop.eventloop();
			}

			@Provides
			Config config() {
				return config;
			}

			@ProvidesIntoSet
			JsonRpcServiceBinding userApi() {
				return new JsonRpcServiceBinding(UserApi.class, new UserApiImpl());
			}
		});
		HttpServer server = injector.getInstance(HttpServer.class);
		// the server must stop before the loop is released, or the open listen channel keeps the loop alive;
		// close actions run on the loop thread, so server.close() is called directly
		loop.onClose(server::close);
		loop.submit(server::listen);
		return server;
	}

	/** The response is recycled when the exchange completes — capture code and body inside the chain. */
	private record ReadResponse(int code, String body) {}

	private static ReadResponse post(HttpServer server, String document) {
		return post(server, "/", document);
	}

	private static ReadResponse post(HttpServer server, String path, String document) {
		int port = server.getBoundAddresses().get(0).getPort();
		HttpClient httpClient = HttpClient.create(Reactor.getCurrentReactor(),
			DnsClient.create(Reactor.getCurrentReactor(), HttpUtils.inetAddress("8.8.8.8")));
		HttpRequest request = HttpRequest.post("http://127.0.0.1:" + port + path)
			.withHeader(HttpHeaders.CONTENT_TYPE, "application/json")
			.withHeader(HttpHeaders.CONNECTION, "close")
			.withBody(document.getBytes(UTF_8))
			.build();
		return await(httpClient.request(request)
			.then(r -> r.loadBody().map(body -> new ReadResponse(r.getCode(), body.getString(UTF_8)))));
	}

	@Test
	public void listenAddressesBindsWhereConfigured() throws Exception {
		HttpServer server = startServer(Config.create().with("http.listenAddresses", "127.0.0.1:0"));
		assertEquals(List.of(new InetSocketAddress("127.0.0.1", 0)), server.getListenAddresses());
		assertNotEquals(0, server.getBoundAddresses().get(0).getPort());
	}

	@Test
	public void pathRoutesAndNonMatchingPathIsRouter404() throws Exception {
		HttpServer server = startServer(Config.create()
			.with("http.listenAddresses", "127.0.0.1:0")
			.with("jsonrpc.path", "/rpc"));

		ReadResponse ok = post(server, "/rpc", "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"user.get\",\"params\":[42]}");
		assertEquals(200, ok.code());
		assertTrue("unexpected body: " + ok.body(), ok.body().contains("\"result\":{\"id\":42,\"name\":\"user-42\"}"));

		ReadResponse notFound = post(server, "/other", "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"user.get\",\"params\":[42]}");
		assertEquals("a non-matching path is the router's 404", 404, notFound.code());
	}

	@Test
	public void maxBodySizeAnswers413AboveTheBoundAndOverridesTheDefault() throws Exception {
		// 1kb is well below the 1mb ApplicationSettings default — a ~2kb body proves the key won
		HttpServer server = startServer(Config.create()
			.with("http.listenAddresses", "127.0.0.1:0")
			.with("jsonrpc.maxBodySize", "1kb"));

		String bigDocument = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"user.get\",\"params\":[42],\"padding\":\""
			+ "x".repeat(2048) + "\"}";
		ReadResponse tooLarge = post(server, bigDocument);
		assertEquals(413, tooLarge.code());

		ReadResponse ok = post(server, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"user.get\",\"params\":[42]}");
		assertEquals(200, ok.code());
	}

	// ---------------------------------------------------------------------------------------------
	// FR-039 — jsonrpc.maxInFlight, admitted by feature 019 and wired into the dispatcher builder
	// ---------------------------------------------------------------------------------------------

	@Test
	public void maxInFlightIsAdmittedAndReachesTheDispatcherBuilder() {
		// the dispatcher publishes its configured ceiling as a read-only JMX attribute (FR-038), which is
		// exactly the read-back an operator gets — so the wiring is asserted through the shipped surface
		JsonRpcDispatcher configured = dispatcher(Config.create().with("jsonrpc.maxInFlight", "7"), new UserApiImpl());
		assertEquals(7, requireNonNull(configured.getStats()).getMaxInFlight());
	}

	@Test
	public void anAbsentMaxInFlightLeavesTheApplicationSettingsDefaultInForce() {
		JsonRpcDispatcher defaulted = dispatcher(Config.create(), new UserApiImpl());

		assertEquals("the documented default", 1000, JsonRpcDispatcher.MAX_IN_FLIGHT);
		assertEquals(JsonRpcDispatcher.MAX_IN_FLIGHT, requireNonNull(defaulted.getStats()).getMaxInFlight());
	}

	@Test
	public void theConfiguredMaxInFlightIsTheBoundActuallyInForce() {
		// a read-back alone would pass on a key that were read, reported and then dropped. With the ceiling
		// at 1 and an implementation that never answers, the SECOND call is shed with -32005 — an outcome the
		// 1000 default could not produce — and a third succeeds once the slot is freed, so the counter is
		// shown to decrement rather than to ratchet
		BlockingUserApi blocking = new BlockingUserApi();
		JsonRpcDispatcher dispatcher = dispatcher(Config.create().with("jsonrpc.maxInFlight", "1"), blocking);

		Promise<byte[]> held = dispatcher.dispatch(userGet(1));
		// the guard is the harness, never the thing under test: without the bound in force this element
		// would be admitted and never answered, and a bare await would hang the whole suite rather than fail
		String shed = new String(await(Promises.timeout(Duration.ofSeconds(5), dispatcher.dispatch(userGet(2)))), UTF_8);

		assertTrue("the excess element must be shed with -32005: " + shed, shed.contains("\"code\":-32005"));
		assertFalse("the admitted element must still be in flight", held.isComplete());

		blocking.release();
		assertTrue(new String(await(held), UTF_8).contains("\"result\""));
		String afterRelease = new String(await(dispatcher.dispatch(userGet(3))), UTF_8);
		assertTrue("the freed slot must be reusable: " + afterRelease, afterRelease.contains("\"result\""));
	}

	@Test
	public void aMaxInFlightBelowOneIsRefusedAtBuild() {
		// the dispatcher's build-time refusal surfaces wrapped by the DI machinery, exactly like the
		// servlet's emptyResponseCode refusal below
		DIException wrapper = assertThrows(DIException.class,
			() -> dispatcher(Config.create().with("jsonrpc.maxInFlight", "0"), new UserApiImpl()));
		assertTrue("unexpected cause: " + wrapper.getCause(), wrapper.getCause() instanceof IllegalArgumentException);
		assertTrue("the offending key must be named: " + wrapper.getCause().getMessage(),
			wrapper.getCause().getMessage().contains("maxInFlight"));
	}

	@Test
	public void emptyResponseCodeSwitches204To200AndRefusesOthers() throws Exception {
		// default is 204 for an empty dispatcher result (a lone notification)
		HttpServer defaultServer = startServer(Config.create().with("http.listenAddresses", "127.0.0.1:0"));
		ReadResponse noContent = post(defaultServer, "{\"jsonrpc\":\"2.0\",\"method\":\"user.touch\",\"params\":[1]}");
		assertEquals(204, noContent.code());

		HttpServer configured = startServer(Config.create()
			.with("http.listenAddresses", "127.0.0.1:0")
			.with("jsonrpc.emptyResponseCode", "200"));
		ReadResponse ok = post(configured, "{\"jsonrpc\":\"2.0\",\"method\":\"user.touch\",\"params\":[1]}");
		assertEquals(200, ok.code());

		// the servlet's build-time refusal surfaces wrapped by the DI machinery
		DIException wrapper = assertThrows(DIException.class,
			() -> startServer(Config.create().with("jsonrpc.emptyResponseCode", "418")));
		assertTrue("unexpected cause: " + wrapper.getCause(), wrapper.getCause() instanceof IllegalArgumentException);
		assertTrue(wrapper.getCause().getMessage().contains("emptyResponseCode"));
	}

	// ---------------------------------------------------------------------------------------------
	// Harness for the dispatcher-only cases above.
	// ---------------------------------------------------------------------------------------------

	/** The dispatcher alone — no server, no listen channel, so it runs on the test reactor. */
	private JsonRpcDispatcher dispatcher(Config config, UserApi implementation) {
		Injector injector = Injector.of(new JsonRpcModule(), new AbstractModule() {
			@Provides
			NioReactor reactor() {
				return Reactor.getCurrentReactor();
			}

			@Provides
			Config config() {
				return config;
			}

			@Provides
			JsonRpcDispatcher.Inspector inspector() {
				return new JsonRpcDispatcher.JmxInspector();
			}

			@ProvidesIntoSet
			JsonRpcServiceBinding userApi() {
				return new JsonRpcServiceBinding(UserApi.class, implementation);
			}
		});
		return injector.getInstance(JsonRpcDispatcher.class);
	}

	private static byte[] userGet(long id) {
		return ("{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"method\":\"user.get\",\"params\":[" + id + "]}").getBytes(UTF_8);
	}

	/**
	 * {@link UserApi} whose calls stay in flight until {@link #release()} — the only shape against which a
	 * concurrency ceiling is observable at all. Reactor-confined, like the counter it exercises.
	 */
	private static final class BlockingUserApi implements UserApi {
		private final List<SettablePromise<User>> pending = new ArrayList<>();
		private boolean blocking = true;

		@Override
		public Promise<User> getUser(long id) {
			if (!blocking) return Promise.of(new User(id, "user-" + id));
			SettablePromise<User> promise = new SettablePromise<>();
			pending.add(promise);
			return promise;
		}

		@Override
		public void touch(long id) {}

		void release() {
			blocking = false;
			for (SettablePromise<User> promise : pending) {
				promise.set(new User(0, "released"));
			}
			pending.clear();
		}
	}
}
