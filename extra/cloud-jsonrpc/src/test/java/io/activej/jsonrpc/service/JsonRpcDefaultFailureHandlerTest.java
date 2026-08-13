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

package io.activej.jsonrpc.service;

import io.activej.common.time.Stopwatch;
import io.activej.eventloop.Eventloop;
import io.activej.eventloop.inspector.EventloopInspector;
import io.activej.jsonrpc.service.fixtures.FailingApi;
import io.activej.jsonrpc.service.fixtures.FailingApiImpl;
import io.activej.reactor.Reactor;
import org.jetbrains.annotations.Nullable;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static io.activej.common.exception.FatalErrorHandlers.rethrow;
import static io.activej.jsonrpc.service.fixtures.FailingApiImpl.SECRET;
import static io.activej.promise.TestUtils.await;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

/**
 * ERR-6 — the <b>production default</b> for a failing notification is
 * {@code (descriptor, e) -> reactor.logFatalError(e, descriptor)} (see {@link JsonRpcDispatcher}'s
 * no-arg constructor). Every other test touching this path in the module — {@link JsonRpcNotificationFailureTest}
 * included — deliberately substitutes {@code withFailureHandler(...)} to avoid
 * {@link io.activej.test.rules.EventloopRule}'s rethrowing fatal-error handler, which leaves the actual
 * default unexercised by any passing test.
 * <p>
 * This class does not use {@code EventloopRule}. {@code Eventloop.logFatalError} reports straight to its
 * configured {@link EventloopInspector} and never consults the fatal-error-handler chain at all — so there is
 * nothing to dodge, and a dedicated {@link Eventloop} built with a recording inspector observes the real
 * default unmodified.
 */
public class JsonRpcDefaultFailureHandlerTest {
	private record FatalErrorReport(Throwable exception, @Nullable Object context) {}

	private final List<FatalErrorReport> fatalErrors = new ArrayList<>();

	@Before
	public void setUp() {
		Eventloop.builder()
			.withCurrentThread()
			.withFatalErrorHandler(rethrow())
			.withInspector(new RecordingInspector())
			.build();
	}

	/** Hands back a clean, uninstrumented, rethrowing eventloop — the ambient state later test classes expect. */
	@After
	public void tearDown() {
		Eventloop.builder()
			.withCurrentThread()
			.withFatalErrorHandler(rethrow())
			.build();
	}

	@Test
	public void aFailingNotificationWithNoConfiguredHandlerReachesReactorLogFatalError() {
		FailingApiImpl implementation = new FailingApiImpl();
		JsonRpcDispatcher dispatcher = JsonRpcDispatcher.builder(Reactor.getCurrentReactor())
			.withService(FailingApi.class, implementation)
			// deliberately no withFailureHandler(...) — this is the production default
			.build();

		byte[] response = await(dispatcher.dispatch(
			"{\"jsonrpc\":\"2.0\",\"method\":\"fail.notify\",\"params\":{\"value\":\"x\"}}".getBytes(UTF_8)));

		assertEquals("§4.1 forbids answering a notification, default handler or not", 0, response.length);
		assertEquals(List.of("notifyAndFail(x)"), implementation.invocations());
		assertEquals("the default must reach Reactor.logFatalError exactly once", 1, fatalErrors.size());

		FatalErrorReport report = fatalErrors.get(0);
		assertSame(IllegalStateException.class, report.exception().getClass());
		assertEquals("the cause reaches logFatalError intact", SECRET, report.exception().getMessage());
		assertEquals("the descriptor is passed through as the context, as the default handler wires it",
			"fail.notify", ((JsonRpcMethodDescriptor) report.context()).wireName());
	}

	/** Records only {@link #onFatalError} — every other callback is irrelevant to this test. */
	private final class RecordingInspector implements EventloopInspector {
		@Override
		public void onFatalError(Throwable e, @Nullable Object context) {
			fatalErrors.add(new FatalErrorReport(e, context));
		}

		@Override
		public void onUpdateBusinessLogicTime(boolean taskOrKeyPresent, boolean externalTaskPresent, long businessLogicTime) {}

		@Override
		public void onUpdateSelectorSelectTime(long selectorSelectTime) {}

		@Override
		public void onUpdateSelectorSelectTimeout(long selectorSelectTimeout) {}

		@Override
		public void onUpdateSelectedKeyDuration(Stopwatch sw) {}

		@Override
		public void onUpdateSelectedKeysStats(int lastSelectedKeys, int invalidKeys, int acceptKeys, int connectKeys, int readKeys, int writeKeys, long loopTime) {}

		@Override
		public void onUpdateLocalTaskDuration(Runnable runnable, @Nullable Stopwatch sw) {}

		@Override
		public void onUpdateLocalTasksStats(int localTasks, long loopTime) {}

		@Override
		public void onUpdateConcurrentTaskDuration(Runnable runnable, @Nullable Stopwatch sw) {}

		@Override
		public void onUpdateConcurrentTasksStats(int newConcurrentTasks, long loopTime) {}

		@Override
		public void onUpdateScheduledTaskDuration(Runnable runnable, @Nullable Stopwatch sw, boolean background) {}

		@Override
		public void onUpdateScheduledTasksStats(int scheduledTasks, long loopTime, boolean background) {}

		@Override
		public void onScheduledTaskOverdue(long overdue, boolean background) {}

		@Override
		public <T extends EventloopInspector> @Nullable T lookup(Class<T> type) {
			return type.isInstance(this) ? type.cast(this) : null;
		}
	}
}
