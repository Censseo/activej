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
import io.activej.reactor.nio.NioReactor;
import io.activej.test.rules.ActivePromisesRule;
import io.activej.test.rules.ByteBufRule;
import io.activej.test.rules.EventloopRule;
import org.junit.After;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;

import javax.management.MBeanServer;
import javax.management.ObjectName;
import javax.management.openmbean.CompositeData;
import javax.management.openmbean.TabularData;
import java.lang.management.ManagementFactory;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Collectors;

import static io.activej.launchers.jsonrpc.LauncherTestHarness.ReadResponse;
import static io.activej.launchers.jsonrpc.LauncherTestHarness.post;
import static io.activej.launchers.jsonrpc.LauncherTestHarness.stop;
import static io.activej.launchers.jsonrpc.LauncherTestHarness.unregisterDispatcherBeans;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * F3 of the adversarial plan: JMX reads racing the 1s aggregation refresh cycle while a continuous
 * workload is being dispatched. The oracle: reads never throw, values are stable only after a full
 * refresh cycle has settled, and the test never asserts an intermediate value (WI-17).
 * <p>
 * The workload stops first, the test then waits until two consecutive aggregated reads are equal
 * (a full quiet cycle) and only then asserts the aggregate equals the per-worker sum.
 */
public class MultithreadedJsonRpcServerLauncherRefreshRaceTest {
	@ClassRule
	public static final EventloopRule eventloopRule = new EventloopRule();
	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();
	@ClassRule
	public static final ActivePromisesRule activePromisesRule = new ActivePromisesRule();

	@Before
	@After
	public void cleanBeans() throws Exception {
		unregisterDispatcherBeans();
	}

	@Test
	public void readsDuringTheRefreshCycleNeverThrowAndSettleAfterAFullCycle() throws Exception {
		MultithreadedJsonRpcServerLauncher launcher = new MultithreadedJsonRpcServerLauncher() {
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
				return super.config()
					.overrideWith(Config.create().with("http.listenAddresses", "0"))
					.overrideWith(Config.create().with("workers", "2"));
			}

			@Override
			protected void onFatalError(Throwable throwable) {}
		};
		launchAndAwaitStart(launcher);
		int port = launcher.primaryServer.getBoundAddresses().get(0).getPort();
		// the EventloopRule reactor belongs to the test thread — every request is submitted to it
		NioReactor reactor = (NioReactor) Reactor.getCurrentReactor();
		io.activej.http.HttpClient httpClient = io.activej.http.HttpClient.create(reactor,
			io.activej.dns.DnsClient.create(reactor, io.activej.http.HttpUtils.inetAddress("8.8.8.8")));

		// continuous workload on a background thread: each request runs on the rule's loop (the
		// workload thread has no reactor of its own) and completes a future the worker awaits
		AtomicReference<Throwable> workloadFailure = new AtomicReference<>();
		Thread workload = new Thread(() -> {
			try {
				for (int i = 0; i < 60; i++) {
					int id = i;
					java.util.concurrent.CompletableFuture<ReadResponse> done = new java.util.concurrent.CompletableFuture<>();
					io.activej.http.HttpRequest request = io.activej.http.HttpRequest
						.post("http://127.0.0.1:" + port + "/")
						.withHeader(io.activej.http.HttpHeaders.CONTENT_TYPE, "application/json")
						.withHeader(io.activej.http.HttpHeaders.CONNECTION, "close")
						.withBody(("{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"method\":\"user.get\",\"params\":[" + id + "]}").getBytes(java.nio.charset.StandardCharsets.UTF_8))
						.build();
					reactor.execute(() -> httpClient.request(request)
						.then(r -> r.loadBody().map(body -> new ReadResponse(r.getCode(), body.getString(java.nio.charset.StandardCharsets.UTF_8))))
						.whenComplete((response, e) -> {
							if (e != null) done.completeExceptionally(e);
							else done.complete(response);
						}));
					ReadResponse response = done.get(15, TimeUnit.SECONDS);
					if (response.code() != 200) throw new AssertionError("bad code: " + response.code());
				}
			} catch (Throwable t) {
				workloadFailure.set(t);
			}
		}, "jsonrpc-refresh-race-workload");
		workload.start();

		try {
			// reader threads hammer the aggregated bean while the workload runs and the refresh cycles
			AtomicReference<Throwable> readerFailure = new AtomicReference<>();
			Thread[] readers = new Thread[3];
			for (int r = 0; r < readers.length; r++) {
				readers[r] = new Thread(() -> {
					try {
						for (int i = 0; i < 100; i++) {
							readAggregatedAttribute("totalRequests");
							readAggregatedAttribute("methodStats");
							Thread.sleep(10);
						}
					} catch (Throwable t) {
						readerFailure.set(t);
					}
				}, "jsonrpc-refresh-race-reader-" + r);
				readers[r].start();
			}

			// the workload's requests run on the rule's eventloop — the main thread pumps it while
			// the workload and the readers are alive
			io.activej.eventloop.Eventloop ruleEventloop = (io.activej.eventloop.Eventloop) reactor;
			long deadline = System.currentTimeMillis() + 30_000;
			while (workload.isAlive() && System.currentTimeMillis() < deadline) {
				ruleEventloop.run();
				Thread.sleep(2);
			}
			workload.join(10_000);
			assertTrue("the workload must have finished", !workload.isAlive());
			if (workloadFailure.get() != null) throw new AssertionError("workload failed", workloadFailure.get());
			for (Thread reader : readers) {
				reader.join(30000);
			}
			if (readerFailure.get() != null) throw new AssertionError("JMX reads threw during the race", readerFailure.get());

			// the workload is quiet now; the per-worker sum is a stable fact — read it first
			Map<ObjectName, Long> perWorker = pollPerWorkerUntil(60L);
			long workerSum = perWorker.values().stream().mapToLong(Long::longValue).sum();
			assertEquals(60, workerSum);

			// only now assert the aggregate — after two consecutive identical reads (a full quiet cycle)
			ObjectName aggregated = aggregatedBean();
			long aggregatedTotal = awaitStableAggregated(aggregated, "user.get", workerSum);
			assertEquals("after a full refresh cycle the aggregate must equal the per-worker sum",
				workerSum, aggregatedTotal);
		} finally {
			launcher.shutdown();
			io.activej.promise.TestUtils.await(io.activej.promise.Promise.ofCompletionStage(launcher.getCompleteFuture()));
			unregisterDispatcherBeans();
		}
	}

	// ---------------------------------------------------------------------------------------------
	// JMX reading helpers
	// ---------------------------------------------------------------------------------------------

	private static ObjectName aggregatedBean() throws Exception {
		MBeanServer mbs = ManagementFactory.getPlatformMBeanServer();
		return mbs.queryNames(new ObjectName("io.activej.jsonrpc.service:type=JsonRpcDispatcher,*"), null)
			.stream()
			.filter(name -> !name.getKeyPropertyList().containsKey("workerId"))
			.findFirst()
			.orElseThrow(() -> new AssertionError("no aggregated bean"));
	}

	private static void readAggregatedAttribute(String attribute) throws Exception {
		MBeanServer mbs = ManagementFactory.getPlatformMBeanServer();
		mbs.getAttribute(aggregatedBean(), attribute);
	}

	private static Map<ObjectName, Long> pollPerWorkerUntil(long expectedSum) throws Exception {
		MBeanServer mbs = ManagementFactory.getPlatformMBeanServer();
		for (int attempt = 0; attempt < 30; attempt++) {
			Map<ObjectName, Long> totals = mbs.queryNames(new ObjectName("io.activej.jsonrpc.service:type=JsonRpcDispatcher,*"), null)
				.stream()
				.filter(name -> name.getKeyPropertyList().containsKey("workerId"))
				.collect(Collectors.toMap(Function.identity(), name -> {
					try {
						TabularData methodStats = (TabularData) mbs.getAttribute(name, "methodStats");
						CompositeData row = (CompositeData) methodStats.get(new Object[]{"user.get"});
						assertNotNull(row);
						return (long) row.get("successfulRequests_totalCount");
					} catch (Exception e) {
						throw new AssertionError(e);
					}
				}));
			long sum = totals.values().stream().mapToLong(Long::longValue).sum();
			if (sum == expectedSum) return totals;
			Thread.sleep(200);
		}
		throw new AssertionError("per-worker counters did not settle on " + expectedSum);
	}

	private static long awaitStableAggregated(ObjectName aggregated, String wireName, long expected) throws Exception {
		MBeanServer mbs = ManagementFactory.getPlatformMBeanServer();
		long previous = -1;
		for (int attempt = 0; attempt < 30; attempt++) {
			TabularData methodStats = (TabularData) mbs.getAttribute(aggregated, "methodStats");
			CompositeData row = (CompositeData) methodStats.get(new Object[]{wireName});
			assertNotNull(row);
			long current = (long) row.get("successfulRequests_totalCount");
			if (current == previous && current == expected) {
				return current;
			}
			previous = current;
			Thread.sleep(500);
		}
		return previous;
	}

	private static void launchAndAwaitStart(MultithreadedJsonRpcServerLauncher launcher) throws Exception {
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
