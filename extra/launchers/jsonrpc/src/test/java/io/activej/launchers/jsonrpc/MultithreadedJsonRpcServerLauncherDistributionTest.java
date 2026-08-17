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
import org.junit.After;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;

import javax.management.MBeanServer;
import javax.management.ObjectName;
import javax.management.openmbean.CompositeData;
import javax.management.openmbean.TabularData;
import java.lang.management.ManagementFactory;
import java.util.List;
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
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * F1 + F6 of the adversarial plan: distribution across 1/2/8 workers (the degenerate single-worker
 * case must still register an aggregated bean equal to the worker's own), and per-worker statistics
 * tables — one request lands on exactly one worker, each worker reads its own counters, the sum
 * equals the total.
 * <p>
 * Every probe uses {@code Connection: close}, so each request is a fresh connection and the
 * {@code PrimaryServer} round-robin distributes them deterministically. Counts are read by <b>polling
 * until the 1s JMX refresh has moved the raw {@code lastCount} values into {@code totalCount}</b> —
 * never by fixed sleeps, never at an intermediate value (WI-17).
 */
public class MultithreadedJsonRpcServerLauncherDistributionTest {
	@ClassRule
	public static final EventloopRule eventloopRule = new EventloopRule();
	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();
	@ClassRule
	public static final ActivePromisesRule activePromisesRule = new ActivePromisesRule();

	private static final int REQUESTS = 16;

	@Before
	@After
	public void cleanBeans() throws Exception {
		unregisterDispatcherBeans();
	}

	private static MultithreadedJsonRpcServerLauncher launcher(int workers) {
		return new MultithreadedJsonRpcServerLauncher() {
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
					.overrideWith(Config.create().with("workers", "" + workers));
			}

			@Override
			protected void onFatalError(Throwable throwable) {}
		};
	}

	@Test
	public void aggregatedEqualsTheSumForOneTwoAndEightWorkers() throws Exception {
		for (int workers : List.of(1, 2, 8)) {
			MultithreadedJsonRpcServerLauncher launcher = launcher(workers);
			launchAndAwaitStart(launcher);
			try {
				int port = launcher.primaryServer.getBoundAddresses().get(0).getPort();
				for (int i = 0; i < REQUESTS; i++) {
					ReadResponse response = post(Reactor.getCurrentReactor(), port,
						"{\"jsonrpc\":\"2.0\",\"id\":" + i + ",\"method\":\"user.get\",\"params\":[" + i + "]}");
					assertEquals(200, response.code());
				}

				Map<ObjectName, Long> perWorker = pollPerWorkerUntil(workers, REQUESTS);
				long workerSum = perWorker.values().stream().mapToLong(Long::longValue).sum();
				assertEquals("workers=" + workers + ": the workers must have served the whole load",
					REQUESTS, workerSum);

				ObjectName aggregated = aggregatedBean();
				assertNotNull("workers=" + workers + ": the aggregated bean must exist", aggregated);
				// poll until the 1s refresh cycle has settled the aggregated row on the sum
				long aggregatedTotal = pollAggregated(aggregated, "user.get", workerSum);
				assertEquals("workers=" + workers + ": the aggregated attribute must equal the sum over workers",
					workerSum, aggregatedTotal);
			} finally {
				stop(launcher);
				unregisterDispatcherBeans();
			}
		}
	}

	@Test
	public void singleWorkerAggregatedBeanEqualsTheWorkersOwnCounters() throws Exception {
		MultithreadedJsonRpcServerLauncher launcher = launcher(1);
		launchAndAwaitStart(launcher);
		try {
			int port = launcher.primaryServer.getBoundAddresses().get(0).getPort();
			for (int i = 0; i < REQUESTS; i++) {
				ReadResponse response = post(Reactor.getCurrentReactor(), port,
					"{\"jsonrpc\":\"2.0\",\"id\":" + i + ",\"method\":\"user.get\",\"params\":[" + i + "]}");
				assertEquals(200, response.code());
			}

			Map<ObjectName, Long> perWorker = pollPerWorkerUntil(1, REQUESTS);
			assertEquals("workers=1 must register exactly one worker bean", 1, perWorker.size());
			long workerTotal = perWorker.values().iterator().next();

			ObjectName aggregated = aggregatedBean();
			long aggregatedTotal = pollAggregated(aggregated, "user.get", workerTotal);
			assertEquals("workers=1: the aggregated bean must equal the single worker's counters",
				workerTotal, aggregatedTotal);
		} finally {
			stop(launcher);
			unregisterDispatcherBeans();
		}
	}

	@Test
	public void perWorkerTablesReadTheirOwnCountersAndSumEqualsTheTotal() throws Exception {
		MultithreadedJsonRpcServerLauncher launcher = launcher(2);
		launchAndAwaitStart(launcher);
		try {
			int port = launcher.primaryServer.getBoundAddresses().get(0).getPort();

			// ONE request: with round-robin distribution exactly one worker serves it — a shared
			// statistics table would make both workers read the same count
			ReadResponse first = post(Reactor.getCurrentReactor(), port,
				"{\"jsonrpc\":\"2.0\",\"id\":0,\"method\":\"user.get\",\"params\":[0]}");
			assertEquals(200, first.code());
			Map<ObjectName, Long> afterOne = pollPerWorkerUntil(2, 1L);
			long sumAfterOne = afterOne.values().stream().mapToLong(Long::longValue).sum();
			assertEquals(1, sumAfterOne);
			assertEquals("exactly one worker must have served the single request",
				1, afterOne.values().stream().filter(v -> v == 1L).count());

			// nine more: the uneven load stays per-worker — each worker reads its own counters
			for (int i = 1; i < 10; i++) {
				ReadResponse response = post(Reactor.getCurrentReactor(), port,
					"{\"jsonrpc\":\"2.0\",\"id\":" + i + ",\"method\":\"user.get\",\"params\":[" + i + "]}");
				assertEquals(200, response.code());
			}
			Map<ObjectName, Long> afterTen = pollPerWorkerUntil(2, 10L);
			long sum = afterTen.values().stream().mapToLong(Long::longValue).sum();
			assertEquals("the per-worker sum must equal the total load", 10L, sum);
		} finally {
			stop(launcher);
			unregisterDispatcherBeans();
		}
	}

	// ---------------------------------------------------------------------------------------------
	// JMX reading helpers — polling, never fixed sleeps
	// ---------------------------------------------------------------------------------------------

	private static ObjectName aggregatedBean() throws Exception {
		MBeanServer mbs = ManagementFactory.getPlatformMBeanServer();
		return mbs.queryNames(new ObjectName("io.activej.jsonrpc.service:type=JsonRpcDispatcher,*"), null)
			.stream()
			.filter(name -> !name.getKeyPropertyList().containsKey("workerId"))
			.findFirst()
			.orElse(null);
	}

	private static Map<ObjectName, Long> perWorkerTotals(String wireName) throws Exception {
		MBeanServer mbs = ManagementFactory.getPlatformMBeanServer();
		return mbs.queryNames(new ObjectName("io.activej.jsonrpc.service:type=JsonRpcDispatcher,*"), null)
			.stream()
			.filter(name -> name.getKeyPropertyList().containsKey("workerId"))
			.collect(Collectors.toMap(Function.identity(), name -> {
				try {
					return rowTotal(mbs, name, wireName);
				} catch (Exception e) {
					throw new AssertionError(e);
				}
			}));
	}

	private static Map<ObjectName, Long> pollPerWorkerUntil(int workers, long expectedSum) throws Exception {
		Map<ObjectName, Long> last = Map.of();
		for (int attempt = 0; attempt < 30; attempt++) {
			last = perWorkerTotals("user.get");
			long sum = last.values().stream().mapToLong(Long::longValue).sum();
			if (last.size() == workers && sum == expectedSum) return last;
			Thread.sleep(200);
		}
		throw new AssertionError("per-worker counters did not settle on sum " + expectedSum + ": " + last);
	}

	private static long pollAggregated(ObjectName aggregated, String wireName, long expected) throws Exception {
		MBeanServer mbs = ManagementFactory.getPlatformMBeanServer();
		long last = -1;
		for (int attempt = 0; attempt < 30; attempt++) {
			TabularData methodStats = (TabularData) mbs.getAttribute(aggregated, "methodStats");
			CompositeData row = (CompositeData) methodStats.get(new Object[]{wireName});
			assertNotNull("the aggregated bean must have a row for " + wireName, row);
			last = (long) row.get("successfulRequests_totalCount");
			if (last == expected) return last;
			Thread.sleep(200);
		}
		return last;
	}

	private static long rowTotal(MBeanServer mbs, ObjectName bean, String wireName) throws Exception {
		TabularData methodStats = (TabularData) mbs.getAttribute(bean, "methodStats");
		CompositeData row = (CompositeData) methodStats.get(new Object[]{wireName});
		assertNotNull("worker " + bean + " must have a row for " + wireName, row);
		return (long) row.get("successfulRequests_totalCount");
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
