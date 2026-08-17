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
import io.activej.launchers.jsonrpc.fixtures.FlakyApi;
import io.activej.launchers.jsonrpc.fixtures.User;
import io.activej.promise.Promise;
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
 * F2 of the adversarial plan: a runtime failure on one worker. With round-robin distribution and
 * {@code Connection: close} probes, request 13 is served by worker 1 deterministically — its
 * implementation throws, the dispatcher answers {@code -32603} (feature 013's documented mapping),
 * the other worker keeps answering success, the aggregate counts both sides correctly, and no worker
 * dies: the endpoint still serves afterwards.
 */
public class MultithreadedJsonRpcServerLauncherRuntimeFailureTest {
	@ClassRule
	public static final EventloopRule eventloopRule = new EventloopRule();
	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();
	@ClassRule
	public static final ActivePromisesRule activePromisesRule = new ActivePromisesRule();

	private static final int REQUESTS = 20;

	@Before
	@After
	public void cleanBeans() throws Exception {
		unregisterDispatcherBeans();
	}

	@Test
	public void throwingImplementationAnswers32603OnItsWorkerAndTheAggregateCountsBothSides() throws Exception {
		MultithreadedJsonRpcServerLauncher launcher = new MultithreadedJsonRpcServerLauncher() {
			@Override
			protected Module getBusinessLogicModule() {
				return new AbstractModule() {
					@ProvidesIntoSet
					JsonRpcServiceBinding flakyApi() {
						return new JsonRpcServiceBinding(FlakyApi.class, new FlakyApi() {
							@Override
							public Promise<User> get(long id) {
								if (id == FlakyApi.FAILED_ID) {
									throw new RuntimeException("boom on " + id);
								}
								return Promise.of(new User(id, "user-" + id));
							}
						});
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
		try {
			int port = launcher.primaryServer.getBoundAddresses().get(0).getPort();

			int errors = 0;
			int successes = 0;
			for (int i = 0; i < REQUESTS; i++) {
				ReadResponse response = post(Reactor.getCurrentReactor(), port,
					"{\"jsonrpc\":\"2.0\",\"id\":" + i + ",\"method\":\"flaky.get\",\"params\":[" + i + "]}");
				assertEquals("request " + i + " must still be answered over HTTP", 200, response.code());
				if (i == FlakyApi.FAILED_ID) {
					assertTrue("request " + i + " must be a -32603: " + response.body(),
						response.bodyContains("\"code\":-32603"));
					errors++;
				} else {
					assertTrue("request " + i + " must succeed: " + response.body(),
						response.bodyContains("\"result\""));
					successes++;
				}
			}
			assertEquals(1, errors);
			assertEquals(REQUESTS - 1, successes);

			// per-worker: exactly one worker carries the failure, the other is failure-free — each
			// worker reads its own counters (a shared table would smear the failure onto both)
			Map<ObjectName, Long> successful = pollPerWorkerUntil("flaky.get", 19L);
			Map<ObjectName, Long> failed = pollPerWorker("failedRequests_totalCount");
			long successSum = successful.values().stream().mapToLong(Long::longValue).sum();
			long failedSum = failed.values().stream().mapToLong(Long::longValue).sum();
			assertEquals(REQUESTS - 1, successSum);
			assertEquals(1, failedSum);
			assertEquals("exactly one worker must carry the failure",
				1, failed.values().stream().filter(v -> v == 1L).count());
			assertTrue("the other worker must be failure-free", failed.containsValue(0L));
			for (Long served : successful.values()) {
				assertTrue("every worker must have served successes, got: " + served, served > 0);
			}

			// the -32603 bucket: the named code has its own row, pre-populated at build
			Map<ObjectName, Long> internalErrorBucket = pollErrorBucket(-32603, 1L);
			assertEquals(1, internalErrorBucket.values().stream().mapToLong(Long::longValue).sum());

			// aggregated (after a refresh cycle): both sides of the aggregate are correct
			long aggregatedSuccess = pollAggregated("flaky.get", "successfulRequests_totalCount", REQUESTS - 1);
			long aggregatedFailed = pollAggregated("flaky.get", "failedRequests_totalCount", 1);
			assertEquals(REQUESTS - 1, aggregatedSuccess);
			assertEquals(1, aggregatedFailed);

			// no worker died: the endpoint still answers after the failure
			ReadResponse after = post(Reactor.getCurrentReactor(), port,
				"{\"jsonrpc\":\"2.0\",\"id\":99,\"method\":\"flaky.get\",\"params\":[99]}");
			assertEquals(200, after.code());
			assertTrue(after.bodyContains("\"result\""));
		} finally {
			stop(launcher);
			unregisterDispatcherBeans();
		}
	}

	// ---------------------------------------------------------------------------------------------
	// JMX reading helpers
	// ---------------------------------------------------------------------------------------------

	private static Map<ObjectName, Long> pollPerWorkerUntil(String wireName, long expectedSum) throws Exception {
		for (int attempt = 0; attempt < 30; attempt++) {
			Map<ObjectName, Long> totals = pollPerWorker("successfulRequests_totalCount");
			long sum = totals.values().stream().mapToLong(Long::longValue).sum();
			if (sum == expectedSum) return totals;
			Thread.sleep(200);
		}
		throw new AssertionError("per-worker counters did not settle on " + expectedSum);
	}

	private static Map<ObjectName, Long> pollErrorBucket(int code, long expectedSum) throws Exception {
		MBeanServer mbs = ManagementFactory.getPlatformMBeanServer();
		for (int attempt = 0; attempt < 30; attempt++) {
			Map<ObjectName, Long> totals = mbs.queryNames(new ObjectName("io.activej.jsonrpc.service:type=JsonRpcDispatcher,*"), null)
				.stream()
				.filter(name -> name.getKeyPropertyList().containsKey("workerId"))
				.collect(Collectors.toMap(Function.identity(), name -> {
					try {
						TabularData methodStats = (TabularData) mbs.getAttribute(name, "methodStats");
						CompositeData row = (CompositeData) methodStats.get(new Object[]{"flaky.get"});
						assertNotNull(row);
						TabularData errorsByCode = (TabularData) row.get("errorsByCode");
						// the map's JMX row key is the rendered String form of the code; the value column
						// is the EventStats String form ("<totalCount> @ <rate>/second", "" when zero)
						CompositeData bucket = (CompositeData) errorsByCode.get(new Object[]{String.valueOf(code)});
						assertNotNull("the " + code + " bucket must be pre-populated on " + name, bucket);
						String rendered = (String) bucket.get("value");
						return rendered.isEmpty() ? 0 : Long.parseLong(rendered.substring(0, rendered.indexOf(' ')));
					} catch (Exception e) {
						throw new AssertionError(e);
					}
				}));
			long sum = totals.values().stream().mapToLong(Long::longValue).sum();
			if (sum == expectedSum) return totals;
			Thread.sleep(200);
		}
		throw new AssertionError("error bucket " + code + " did not settle on " + expectedSum);
	}

	private static Map<ObjectName, Long> pollPerWorker(String subAttribute) throws Exception {
		MBeanServer mbs = ManagementFactory.getPlatformMBeanServer();
		return mbs.queryNames(new ObjectName("io.activej.jsonrpc.service:type=JsonRpcDispatcher,*"), null)
			.stream()
			.filter(name -> name.getKeyPropertyList().containsKey("workerId"))
			.collect(Collectors.toMap(Function.identity(), name -> {
				try {
					TabularData methodStats = (TabularData) mbs.getAttribute(name, "methodStats");
					CompositeData row = (CompositeData) methodStats.get(new Object[]{"flaky.get"});
					assertNotNull("worker " + name + " must have a flaky.get row", row);
					return (long) row.get(subAttribute);
				} catch (Exception e) {
					throw new AssertionError(e);
				}
			}));
	}

	private static long pollAggregated(String wireName, String subAttribute, long expected) throws Exception {
		MBeanServer mbs = ManagementFactory.getPlatformMBeanServer();
		ObjectName aggregated = mbs.queryNames(new ObjectName("io.activej.jsonrpc.service:type=JsonRpcDispatcher,*"), null)
			.stream()
			.filter(name -> !name.getKeyPropertyList().containsKey("workerId"))
			.findFirst()
			.orElseThrow(() -> new AssertionError("no aggregated bean"));
		long last = -1;
		for (int attempt = 0; attempt < 30; attempt++) {
			TabularData methodStats = (TabularData) mbs.getAttribute(aggregated, "methodStats");
			CompositeData row = (CompositeData) methodStats.get(new Object[]{wireName});
			assertNotNull(row);
			last = (long) row.get(subAttribute);
			if (last == expected) return last;
			Thread.sleep(200);
		}
		return last;
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
