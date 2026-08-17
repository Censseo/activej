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
import io.activej.http.HttpRequest;
import io.activej.http.HttpHeaders;
import io.activej.http.HttpResponse;
import io.activej.http.HttpUtils;
import io.activej.inject.annotation.ProvidesIntoSet;
import io.activej.inject.module.AbstractModule;
import io.activej.inject.module.Module;
import io.activej.inject.binding.DIException;
import io.activej.jsonrpc.service.JsonRpcContractException;
import io.activej.launcher.Launcher;
import io.activej.launchers.jsonrpc.fixtures.BrokenApis;
import io.activej.launchers.jsonrpc.fixtures.Stats;
import io.activej.launchers.jsonrpc.fixtures.StatsApi;
import io.activej.launchers.jsonrpc.fixtures.UserApi;
import io.activej.launchers.jsonrpc.fixtures.UserApiImpl;
import io.activej.promise.Promise;
import io.activej.reactor.Reactor;
import io.activej.test.rules.ActivePromisesRule;
import io.activej.test.rules.ByteBufRule;
import io.activej.test.rules.EventloopRule;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import static io.activej.promise.Promise.ofCompletionStage;
import static io.activej.promise.TestUtils.await;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * US1 scenario 6 (FR-012): several service interfaces share one endpoint; a wire-name collision between
 * two of them fails startup naming the name and <b>both</b> interfaces.
 */
public class JsonRpcServerLauncherMultiServiceTest {
	@ClassRule
	public static final EventloopRule eventloopRule = new EventloopRule();
	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();
	@ClassRule
	public static final ActivePromisesRule activePromisesRule = new ActivePromisesRule();

	@Test
	public void twoInterfacesResolveOnOneEndpoint() throws Exception {
		JsonRpcServerLauncher launcher = new JsonRpcServerLauncher() {
			@Override
			protected Module getBusinessLogicModule() {
				return new AbstractModule() {
					@ProvidesIntoSet
					JsonRpcServiceBinding userApi() {
						return new JsonRpcServiceBinding(UserApi.class, new UserApiImpl());
					}

					@ProvidesIntoSet
					JsonRpcServiceBinding statsApi() {
						return new JsonRpcServiceBinding(StatsApi.class, new StatsApi() {
							@Override
							public Promise<Stats> getStats() {
								return Promise.of(new Stats(7));
							}
						});
					}
				};
			}

			@Override
			Config config() {
				return super.config().overrideWith(Config.create().with("http.listenAddresses", "0"));
			}

			@Override
			protected void onFatalError(Throwable throwable) {}
		};

		launchAndAwaitStart(launcher);

		try {
			int port = launcher.httpServer.getBoundAddresses().get(0).getPort();
			HttpClient httpClient = HttpClient.create(Reactor.getCurrentReactor(),
				DnsClient.create(Reactor.getCurrentReactor(), HttpUtils.inetAddress("8.8.8.8")));

			String userResponse = post(httpClient, port, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"user.get\",\"params\":[42]}");
			assertTrue("unexpected: " + userResponse, userResponse.contains("\"result\":{\"id\":42,\"name\":\"user-42\"}"));

			String statsResponse = post(httpClient, port, "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"stats.get\"}");
			assertTrue("unexpected: " + statsResponse, statsResponse.contains("\"result\":{\"requests\":7}"));
		} finally {
			launcher.shutdown();
			await(ofCompletionStage(launcher.getCompleteFuture()));
		}
	}

	@Test
	public void wireNameCollisionFailsNamingTheNameAndBothInterfaces() {
		JsonRpcServerLauncher launcher = new JsonRpcServerLauncher() {
			@Override
			protected Module getBusinessLogicModule() {
				return new AbstractModule() {
					@ProvidesIntoSet
					JsonRpcServiceBinding userApi() {
						return new JsonRpcServiceBinding(UserApi.class, new UserApiImpl());
					}

					@ProvidesIntoSet
					JsonRpcServiceBinding collidingApi() {
						return new JsonRpcServiceBinding(BrokenApis.CollidingWireName.class,
							new BrokenApis.CollidingWireName() {
								@Override
								public Promise<io.activej.launchers.jsonrpc.fixtures.User> get(long id) {
									return Promise.of(new io.activej.launchers.jsonrpc.fixtures.User(id, "collision"));
								}
							});
					}
				};
			}

			@Override
			protected void onFatalError(Throwable throwable) {}
		};

		// the provider's JsonRpcContractException surfaces wrapped by the DI machinery
		DIException wrapper = assertThrows(DIException.class, () -> launcher.launch(Launcher.NO_ARGS));
		JsonRpcContractException e = (JsonRpcContractException) wrapper.getCause();

		String message = e.getMessage();
		assertTrue("the wire name must be named: " + message, message.contains("user.get"));
		assertTrue("both declaring interfaces must be named: " + message, message.contains("UserApi"));
		assertTrue("both declaring interfaces must be named: " + message, message.contains("CollidingWireName"));
	}

	private static String post(HttpClient httpClient, int port, String document) {
		HttpRequest request = HttpRequest.post("http://127.0.0.1:" + port + "/")
			.withHeader(HttpHeaders.CONTENT_TYPE, "application/json")
			.withBody(document.getBytes(UTF_8))
			.build();
		return await(httpClient.request(request)
			.then(r -> r.loadBody().map(buf -> {
				assertEquals(200, r.getCode());
				return buf.getString(UTF_8);
			})));
	}
	/** Launches on a dedicated thread and fails the test with the launch failure instead of hanging. */
	static void launchAndAwaitStart(JsonRpcServerLauncher launcher) throws Exception {
		AtomicReference<Throwable> failure = new AtomicReference<>();
		new Thread(() -> {
			try {
				launcher.launch(Launcher.NO_ARGS);
			} catch (Throwable t) {
				failure.set(t);
			}
		}).start();
		try {
			launcher.getStartFuture().toCompletableFuture().get(10, TimeUnit.SECONDS);
		} catch (TimeoutException e) {
			Throwable t = failure.get();
			if (t != null) throw new AssertionError("launch failed", t);
			throw e;
		}
	}
}
