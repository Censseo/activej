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
import io.activej.http.HttpUtils;
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
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;

import javax.management.MBeanServer;
import javax.management.ObjectName;
import javax.management.openmbean.CompositeData;
import javax.management.openmbean.TabularData;
import java.lang.management.ManagementFactory;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static io.activej.promise.TestUtils.await;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * US4 scenario 2 (SC-008, FR-038): with N workers having served a known total, the <b>aggregated</b>
 * per-method attribute equals the sum over workers. What this asserts is that worker <i>instances</i>
 * are what gets reduced — the reducer-over-map mechanism itself is already proven by {@code boot-jmx}'s
 * own suite.
 */
public class MultithreadedJsonRpcServerLauncherAggregationTest {
	@ClassRule
	public static final EventloopRule eventloopRule = new EventloopRule();
	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();
	@ClassRule
	public static final ActivePromisesRule activePromisesRule = new ActivePromisesRule();

	private static final int WORKERS = 2;
	private static final int REQUESTS = 10;

	@Before
	public void setUp() throws Exception {
		// the platform server is where JmxModule registers; drop beans left by earlier tests
		MBeanServer mbs = ManagementFactory.getPlatformMBeanServer();
		for (ObjectName name : mbs.queryNames(new ObjectName("io.activej.jsonrpc.service:type=JsonRpcDispatcher,*"), null)) {
			mbs.unregisterMBean(name);
		}
	}

	@Test
	public void aggregatedCountsEqualTheSumOverWorkers() throws Exception {
		AtomicReference<Throwable> failure = new AtomicReference<>();
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
				// NOTE: WORKERS resolves to the launcher's inherited constant inside the anonymous subclass
				return super.config()
					.overrideWith(Config.create()
						.with("http.listenAddresses", "0")
						.with("workers", "" + MultithreadedJsonRpcServerLauncherAggregationTest.WORKERS));
			}

			@Override
			protected void onFatalError(Throwable throwable) {
				failure.set(throwable);
			}
		};

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
			if (failure.get() != null) throw new AssertionError("launch failed", failure.get());
			throw e;
		}

		try {
			assertNotEquals(0, launcher.primaryServer.getBoundAddresses().get(0).getPort());

			// N requests over the shared accept socket — the PrimaryServer distributes them across workers
			HttpClient httpClient = HttpClient.create(Reactor.getCurrentReactor(),
				DnsClient.create(Reactor.getCurrentReactor(), HttpUtils.inetAddress("8.8.8.8")));
			int port = launcher.primaryServer.getBoundAddresses().get(0).getPort();
			for (int i = 0; i < REQUESTS; i++) {
				HttpRequest request = HttpRequest.post("http://127.0.0.1:" + port + "/")
					.withHeader(HttpHeaders.CONTENT_TYPE, "application/json")
					.withBody(("{\"jsonrpc\":\"2.0\",\"id\":" + i + ",\"method\":\"user.get\",\"params\":[" + i + "]}").getBytes(UTF_8))
					.build();
				Integer code = await(httpClient.request(request)
					.then(r -> r.loadBody().map($ -> r.getCode())));
				assertEquals(200, (int) code);
			}

			MBeanServer mbs = ManagementFactory.getPlatformMBeanServer();
			Set<ObjectName> names = mbs.queryNames(new ObjectName("io.activej.jsonrpc.service:type=JsonRpcDispatcher,*"), null);

			// the aggregated bean: no workerId in its name
			ObjectName aggregated = names.stream()
				.filter(name -> !name.getKeyPropertyList().containsKey("workerId"))
				.findFirst()
				.orElseThrow(() -> new AssertionError("no aggregated MBean found among: " + names));
			// per-worker beans: one per worker
			List<ObjectName> workerBeans = names.stream()
				.filter(name -> name.getKeyPropertyList().containsKey("workerId"))
				.toList();
			assertEquals(WORKERS, workerBeans.size());

			// the per-worker sum — and the T049 confirmation: one statistics table PER worker, no field
			// shared across threads (a shared table would make every worker read the same total)
			long perWorkerTotal = 0;
			for (ObjectName worker : workerBeans) {
				TabularData methodStats = (TabularData) mbs.getAttribute(worker, "methodStats");
				CompositeData userGet = (CompositeData) methodStats.get(new Object[]{"user.get"});
				assertNotNull("worker " + worker + " must have a user.get row", userGet);
				long workerTotal = (long) userGet.get("successfulRequests_totalCount");
				assertTrue("each worker must have served at least one request, but " + worker + " served "
					+ workerTotal + " and " + REQUESTS + " were dispatched", workerTotal >= 1);
				perWorkerTotal += workerTotal;
			}
			assertEquals("the workers must have served the whole load", REQUESTS, perWorkerTotal);

			// the JMX refresh cycle (1s) moves each worker's raw counts into the fields the aggregation
			// copies — poll until the aggregated bean has settled on the sum
			long aggregatedTotal = 0;
			for (int attempt = 0; attempt < 10; attempt++) {
				TabularData aggregatedStats = (TabularData) mbs.getAttribute(aggregated, "methodStats");
				CompositeData aggregatedRow = (CompositeData) aggregatedStats.get(new Object[]{"user.get"});
				assertNotNull(aggregatedRow);
				aggregatedTotal = (long) aggregatedRow.get("successfulRequests_totalCount");
				if (aggregatedTotal == perWorkerTotal) break;
				Thread.sleep(500);
			}

			// FR-038: the aggregated attribute equals the sum over workers
			assertEquals("the aggregated count must equal the sum over workers",
				perWorkerTotal, aggregatedTotal);

			// registeredMethods is identical on every worker, so the aggregated read must be that single
			// value — a sum reducer would report workers × methods (findings C7)
			int perWorkerRegistered = (int) mbs.getAttribute(workerBeans.get(0), "registeredMethods");
			assertEquals("registeredMethods is identical on every worker",
				perWorkerRegistered, mbs.getAttribute(workerBeans.get(1), "registeredMethods"));
			assertEquals("the aggregated registeredMethods must be the single per-worker count, not the sum",
				perWorkerRegistered, mbs.getAttribute(aggregated, "registeredMethods"));
		} finally {
			launcher.shutdown();
			await(io.activej.promise.Promise.ofCompletionStage(launcher.getCompleteFuture()));
		}
	}
}
