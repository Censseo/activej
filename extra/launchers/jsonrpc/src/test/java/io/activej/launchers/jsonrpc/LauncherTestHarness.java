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

import io.activej.dns.DnsClient;
import io.activej.http.HttpClient;
import io.activej.http.HttpHeaders;
import io.activej.http.HttpRequest;
import io.activej.http.HttpResponse;
import io.activej.launcher.Launcher;
import io.activej.promise.Promise;
import io.activej.reactor.Reactor;
import io.activej.reactor.nio.NioReactor;
import io.activej.test.rules.ByteBufRule;

import javax.management.MBeanServer;
import javax.management.ObjectName;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.net.Socket;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import static io.activej.http.HttpUtils.inetAddress;
import static io.activej.promise.Promise.ofCompletionStage;
import static io.activej.promise.TestUtils.await;
import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * Shared scaffolding for the adversarial launcher tests: launching a launcher on its own thread and
 * awaiting its start future, stopping it cleanly, one-request-per-connection POST probes (each probe is
 * a fresh connection, so a {@code PrimaryServer} distributes them round-robin deterministically), and
 * cleanup of the dispatcher beans the tests' {@code JmxModule}s leave on the platform MBeanServer.
 * <p>
 * {@link ByteBufRule} is deliberately not declared here — each test class declares its own rules, so a
 * leak is attributed to the class that caused it.
 */
final class LauncherTestHarness {
	private LauncherTestHarness() {}

	/** Launches on a dedicated thread and fails the test with the launch failure instead of hanging. */
	static void launch(Launcher launcher) throws Exception {
		AtomicReference<Throwable> failure = new AtomicReference<>();
		new Thread(() -> {
			try {
				launcher.launch(Launcher.NO_ARGS);
			} catch (Throwable t) {
				failure.set(t);
			}
		}, "jsonrpc-launcher-test").start();
		try {
			launcher.getStartFuture().toCompletableFuture().get(10, TimeUnit.SECONDS);
		} catch (TimeoutException e) {
			Throwable t = failure.get();
			if (t != null) throw new AssertionError("launch failed", t);
			throw e;
		}
	}

	/** Stops the launcher and awaits the completed lifecycle — a failed stop is a failed test. */
	static void stop(Launcher launcher) throws Exception {
		launcher.shutdown();
		await(ofCompletionStage(launcher.getCompleteFuture()));
	}

	/** One POST, one fresh connection; returns the status code and the body read inside the exchange. */
	static ReadResponse post(NioReactor reactor, int port, String path, String document) {
		HttpClient httpClient = HttpClient.create(reactor,
			DnsClient.create(reactor, inetAddress("8.8.8.8")));
		HttpRequest request = HttpRequest.post("http://127.0.0.1:" + port + path)
			.withHeader(HttpHeaders.CONTENT_TYPE, "application/json")
			.withHeader(HttpHeaders.CONNECTION, "close")
			.withBody(document.getBytes(UTF_8))
			.build();
		// the body is read inside the exchange — the response is recycled by the connection once the
		// promise chain settles; the code and body are captured before that happens
		return await(httpClient.request(request)
			.then(response -> response.loadBody()
				.map(body -> new ReadResponse(response.getCode(), body.getString(UTF_8)))));
	}

	static ReadResponse post(NioReactor reactor, int port, String document) {
		return post(reactor, port, "/", document);
	}

	record ReadResponse(int code, String body) {
		boolean bodyContains(String needle) {
			return body.contains(needle);
		}
	}

	/** Drops every dispatcher bean a previous test's {@code JmxModule} may have left on the platform server. */
	static void unregisterDispatcherBeans() throws Exception {
		MBeanServer mbs = ManagementFactory.getPlatformMBeanServer();
		for (ObjectName name : mbs.queryNames(new ObjectName("io.activej.jsonrpc.service:type=JsonRpcDispatcher,*"), null)) {
			mbs.unregisterMBean(name);
		}
	}

	static List<ObjectName> dispatcherBeans() throws Exception {
		MBeanServer mbs = ManagementFactory.getPlatformMBeanServer();
		Set<ObjectName> names = mbs.queryNames(new ObjectName("io.activej.jsonrpc.service:type=JsonRpcDispatcher,*"), null);
		return names.stream().sorted(Comparator.comparing(ObjectName::toString)).toList();
	}

	static boolean causeChainHas(Throwable t, Class<? extends Throwable> type) {
		for (Throwable current = t; current != null; current = current.getCause()) {
			if (type.isInstance(current)) return true;
		}
		return false;
	}

	/** Asserts that nothing is listening on {@code port} — a fresh connect is refused. */
	static void assertNothingListens(int port) {
		try {
			new Socket("127.0.0.1", port).close();
			throw new AssertionError("a socket is still listening on " + port);
		} catch (IOException expected) {
			// connect refused — the port is free
		}
	}

	static void assertListens(int port) throws IOException {
		try (Socket socket = new Socket("127.0.0.1", port)) {
			// connected
		}
	}
}
