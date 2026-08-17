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
import io.activej.inject.annotation.ProvidesIntoSet;
import io.activej.inject.module.AbstractModule;
import io.activej.inject.module.Module;
import io.activej.launcher.Launcher;
import io.activej.launchers.jsonrpc.fixtures.UserApi;
import io.activej.launchers.jsonrpc.fixtures.UserApiImpl;
import io.activej.reactor.Reactor;
import io.activej.test.rules.ActivePromisesRule;
import io.activej.test.rules.ByteBufRule;
import io.activej.test.rules.EventloopRule;
import io.activej.test.TestUtils;
import org.junit.ClassRule;
import org.junit.Test;

import java.net.InetAddress;
import java.net.Inet6Address;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import static io.activej.launchers.jsonrpc.LauncherTestHarness.ReadResponse;
import static io.activej.launchers.jsonrpc.LauncherTestHarness.assertNothingListens;
import static io.activej.launchers.jsonrpc.LauncherTestHarness.causeChainHas;
import static io.activej.launchers.jsonrpc.LauncherTestHarness.post;
import static io.activej.launchers.jsonrpc.LauncherTestHarness.stop;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * D4 of the adversarial plan: hostile {@code http.listenAddresses} values.
 * <ul>
 *     <li>an occupied port → {@code BindException}, the graph rolls back, {@code launch()} rejects,
 *     the port is released afterwards;</li>
 *     <li>IPv6 loopback binds and serves;</li>
 *     <li>a nonexistent host fails startup (documented failure);</li>
 *     <li>the default host ({@code localhost}) is never exposed outside loopback — a wildcard bind
 *     requires the explicit {@code 0.0.0.0}.</li>
 * </ul>
 */
public class JsonRpcServerLauncherNetworkTest {
	@ClassRule
	public static final EventloopRule eventloopRule = new EventloopRule();
	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();
	@ClassRule
	public static final ActivePromisesRule activePromisesRule = new ActivePromisesRule();

	private static JsonRpcServerLauncher singleLauncher(String listenAddresses) {
		return new JsonRpcServerLauncher() {
			@Override
			protected Module getBusinessLogicModule() {
				return new AbstractModule() {
					@ProvidesIntoSet
					JsonRpcServiceBinding userApi() {
						return new JsonRpcServiceBinding(UserApi.class, new UserApiImpl());
					}
				};
			}

			@Override
			Config config() {
				return super.config().overrideWith(Config.create().with("http.listenAddresses", listenAddresses));
			}

			@Override
			protected void onFatalError(Throwable throwable) {}
		};
	}

	@Test
	public void occupiedPortFailsStartupWithBindErrorAndReleasesThePort() throws Exception {
		int port = TestUtils.getFreePort();
		try (ServerSocket blocker = new ServerSocket(port, 50, InetAddress.getLoopbackAddress())) {
			JsonRpcServerLauncher launcher = singleLauncher("127.0.0.1:" + port);
			Exception e = assertThrows(Exception.class, () -> launcher.launch(Launcher.NO_ARGS));
			assertTrue("the failure must carry the BindException: " + e,
				causeChainHas(e, java.net.BindException.class));
		}

		// the rollback released everything: the port is immediately reusable
		try (ServerSocket rebind = new ServerSocket(port, 50, InetAddress.getLoopbackAddress())) {
			// bound
		}
	}

	@Test
	public void ipv6LoopbackBindsAndServes() throws Exception {
		JsonRpcServerLauncher launcher = singleLauncher("[::1]:0");
		launchAndAwaitStart(launcher);
		try {
			InetSocketAddress bound = launcher.httpServer.getBoundAddresses().get(0);
			assertTrue("expected an IPv6 loopback bind, got: " + bound, bound.getAddress() instanceof Inet6Address);
			assertTrue("expected a loopback address, got: " + bound, bound.getAddress().isLoopbackAddress());
			assertNotEquals(0, bound.getPort());
			// the HttpClient URL needs the bracketed form for IPv6
			int port = bound.getPort();
			io.activej.dns.DnsClient dnsClient = io.activej.dns.DnsClient.create(Reactor.getCurrentReactor(),
				io.activej.http.HttpUtils.inetAddress("8.8.8.8"));
			io.activej.http.HttpClient httpClient = io.activej.http.HttpClient.create(Reactor.getCurrentReactor(), dnsClient);
			io.activej.http.HttpRequest request = io.activej.http.HttpRequest.post("http://[::1]:" + port + "/")
				.withHeader(io.activej.http.HttpHeaders.CONTENT_TYPE, "application/json")
				.withHeader(io.activej.http.HttpHeaders.CONNECTION, "close")
				.withBody("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"user.get\",\"params\":[42]}".getBytes(StandardCharsets.UTF_8))
				.build();
			String body = io.activej.promise.TestUtils.await(httpClient.request(request)
				.then(r -> r.loadBody().map(buf -> buf.getString(StandardCharsets.UTF_8))));
			assertTrue("unexpected body: " + body, body.contains("\"result\""));
		} finally {
			stop(launcher);
		}
	}

	@Test
	public void nonexistentHostFailsStartup() {
		JsonRpcServerLauncher launcher = singleLauncher("no.such.host.invalid:8080");
		Exception e = assertThrows(Exception.class, () -> launcher.launch(Launcher.NO_ARGS));
		// the resolver failure surfaces as the documented startup failure — never a bind
		assertTrue("the failure must name the resolution problem: " + e,
			causeChainHas(e, java.net.UnknownHostException.class) ||
				causeChainHas(e, io.activej.common.exception.MalformedDataException.class));
		assertTrue("no server may exist after a failed resolution", launcher.httpServer == null);
	}

	@Test
	public void defaultLocalhostIsNeverExposedOutsideLoopback() throws Exception {
		JsonRpcServerLauncher launcher = singleLauncher("localhost:0");
		launchAndAwaitStart(launcher);
		try {
			InetSocketAddress bound = launcher.httpServer.getBoundAddresses().get(0);
			assertTrue("the default host must bind only loopback, got: " + bound,
				bound.getAddress().isLoopbackAddress());
			// serving through the loopback works
			ReadResponse ok = post(Reactor.getCurrentReactor(), bound.getPort(),
				"{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"user.get\",\"params\":[42]}");
			assertEquals(200, ok.code());
		} finally {
			stop(launcher);
		}
	}

	@Test
	public void wildcardBindIsExplicitAndExposesAllInterfaces() throws Exception {
		JsonRpcServerLauncher launcher = singleLauncher("0.0.0.0:0");
		launchAndAwaitStart(launcher);
		try {
			InetSocketAddress bound = launcher.httpServer.getBoundAddresses().get(0);
			assertTrue("0.0.0.0 must bind the wildcard, got: " + bound,
				bound.getAddress().isAnyLocalAddress());
		} finally {
			stop(launcher);
		}
	}

	@Test
	public void afterARejectedBindNothingListensOnTheConfiguredPort() throws Exception {
		int port = TestUtils.getFreePort();
		try (ServerSocket blocker = new ServerSocket(port, 50, InetAddress.getLoopbackAddress())) {
			JsonRpcServerLauncher launcher = singleLauncher("127.0.0.1:" + port);
			assertThrows(Exception.class, () -> launcher.launch(Launcher.NO_ARGS));
		}
		// once the blocker is gone and the launcher has rolled back, the port is completely silent
		assertNothingListens(port);
	}

	private static void launchAndAwaitStart(JsonRpcServerLauncher launcher) throws Exception {
		java.util.concurrent.atomic.AtomicReference<Throwable> failure = new java.util.concurrent.atomic.AtomicReference<>();
		new Thread(() -> {
			try {
				launcher.launch(Launcher.NO_ARGS);
			} catch (Throwable t) {
				failure.set(t);
			}
		}).start();
		try {
			launcher.getStartFuture().toCompletableFuture().get(10, java.util.concurrent.TimeUnit.SECONDS);
		} catch (java.util.concurrent.TimeoutException e) {
			Throwable t = failure.get();
			if (t != null) throw new AssertionError("launch failed", t);
			throw e;
		}
	}
}
