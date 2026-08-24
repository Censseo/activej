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

import io.activej.async.callback.AsyncComputation;
import io.activej.async.exception.AsyncCloseException;
import io.activej.async.exception.AsyncTimeoutException;
import io.activej.common.function.RunnableEx;
import io.activej.jsonrpc.JsonRpcException;
import io.activej.jsonrpc.service.fixtures.InMemoryTransport;
import io.activej.jsonrpc.service.fixtures.User;
import io.activej.jsonrpc.service.fixtures.UserApi;
import io.activej.jsonrpc.service.fixtures.UserApiImpl;
import io.activej.jsonrpc.transport.JsonRpcTransport;
import io.activej.promise.Promise;
import io.activej.promise.Promises;
import io.activej.reactor.Reactor;
import io.activej.reactor.schedule.ScheduledRunnable;
import io.activej.test.ExpectedException;
import io.activej.test.rules.ActivePromisesRule;
import io.activej.test.rules.ByteBufRule;
import io.activej.test.rules.EventloopRule;
import org.jetbrains.annotations.Nullable;
import org.junit.After;
import org.junit.ClassRule;
import org.junit.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static io.activej.promise.TestUtils.await;
import static io.activej.promise.TestUtils.awaitException;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.CoreMatchers.instanceOf;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * US2 (FR-010…FR-017, FR-020, FR-022…FR-024): a silent peer can no longer pend a caller forever.
 *
 * <h2>Why the eventloop has to be kept alive by hand</h2>
 * The deadline is scheduled with {@code scheduleBackground} (FR-012) precisely so that a pending call
 * <b>does not</b> keep the eventloop alive — and {@code Eventloop.isAlive()} counts scheduled tasks but
 * <i>not</i> background ones. So {@code await(callPromise)} on a call that only a deadline will ever settle
 * would exit the loop immediately and then block forever on the future.
 * <p>
 * Every timing-driven assertion here therefore goes through {@link #driven(Promise)}, which wraps the call in
 * {@link Promises#timeout} — a <b>foreground</b> scheduled task, which does keep the loop alive, and which is
 * cancelled the moment the call settles. Its own guard is a whole {@link #GUARD order of magnitude} above any
 * deadline under test, so if it is ever the one that fires, the client's deadline did not — and the message
 * says {@code "Promise timeout"} rather than this module's, which makes that failure unmistakable.
 *
 * <h2>Two ways to assert a deadline was disarmed</h2>
 * {@link RecordingReactor} is a delegating {@link Reactor} — the interface is public and {@link
 * io.activej.eventloop.Eventloop} is final, so delegation is the only seam — that captures every
 * {@link ScheduledRunnable} handed to {@code scheduleBackground}. {@link ScheduledRunnable#isActive()} is
 * {@code false} exactly when the task has left the queue, by cancellation or by having run, so a disarm is
 * directly observable rather than inferred. The behavioural half is asserted too, because "cancelled" and
 * "never fires" are different claims: the post-deadline silence tests drive the loop <i>past</i> where the
 * deadline would have been and assert nothing happened.
 */
public final class JsonRpcTimeoutTest {
	@ClassRule
	public static final EventloopRule eventloopRule = new EventloopRule();

	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	@ClassRule
	public static final ActivePromisesRule activePromisesRule = new ActivePromisesRule();

	/** Short enough to keep the suite fast, long enough that the call is genuinely in flight first. */
	private static final Duration DEADLINE = Duration.ofMillis(20);

	/** The harness's own upper bound — never the thing under test. See the class documentation. */
	private static final Duration GUARD = Duration.ofSeconds(5);

	private final List<Exception> failures = new ArrayList<>();
	private final List<JsonRpcClient> clients = new ArrayList<>();

	@After
	public void tearDown() {
		// a client left open would leave an armed background task behind for the next test in this class
		for (JsonRpcClient client : clients) {
			client.closeEx(new ExpectedException("end of test"));
		}
		clients.clear();
	}

	// -------------------------------------------------------------------------------------------
	// Expiry (FR-013, FR-020).
	// -------------------------------------------------------------------------------------------

	@Test
	public void expiryFailsTheCallWithAsyncTimeoutExceptionNamingTheWireNameAndTheDelay() {
		JsonRpcClient client = client(InMemoryTransport.silent(), DEADLINE);
		UserApi api = client.proxy(UserApi.class);

		Promise<User> call = api.getUser(42);
		assertEquals("the call is in flight before the deadline can fire", 1, client.inFlightCount());

		Exception e = awaitException(driven(call));

		assertThat(e, instanceOf(AsyncTimeoutException.class));
		assertTrue("the wire name must be named: " + e.getMessage(), e.getMessage().contains("user.get"));
		assertTrue("the configured delay must be named: " + e.getMessage(), e.getMessage().contains("20 millis"));
	}

	@Test
	public void expiryReturnsTheInFlightCountToZero() {
		JsonRpcClient client = client(InMemoryTransport.silent(), DEADLINE);
		UserApi api = client.proxy(UserApi.class);

		awaitException(driven(api.getUser(1)));

		assertEquals("the entry left the table through the single removal path", 0, client.inFlightCount());
		assertTrue("expiry has no caller-less failure to report", failures.isEmpty());
	}

	@Test
	public void expiryRemovesTheEntryAndTakesTheDeadlineOutOfTheQueue() {
		RecordingReactor reactor = new RecordingReactor();
		JsonRpcClient client = client(reactor, InMemoryTransport.silent(), DEADLINE);
		UserApi api = client.proxy(UserApi.class);

		awaitException(driven(api.getUser(1)));

		assertEquals("exactly one deadline was armed", 1, reactor.deadlines().size());
		assertFalse("the deadline is out of the queue once it has run", reactor.deadlines().get(0).isActive());
		assertEquals(0, client.inFlightCount());
	}

	@Test
	public void everyCallArmsExactlyOneDeadlineBeforeTheDocumentIsSent() {
		RecordingReactor reactor = new RecordingReactor();
		InMemoryTransport transport = InMemoryTransport.silent();
		JsonRpcClient client = client(reactor, transport, DEADLINE);
		UserApi api = client.proxy(UserApi.class);

		api.getUser(1);
		api.getUser(2);

		assertEquals("FR-010: one deadline per call, armed at registration", 2, reactor.deadlines().size());
		assertEquals(2, transport.sentDocuments().size());
	}

	@Test
	public void aNotificationArmsNoDeadline() {
		RecordingReactor reactor = new RecordingReactor();
		JsonRpcClient client = client(reactor, InMemoryTransport.silent(), DEADLINE);
		UserApi api = client.proxy(UserApi.class);

		api.touch(1);

		assertTrue("a notification creates no correlation entry, so there is nothing to expire",
			reactor.deadlines().isEmpty());
		assertEquals(0, client.inFlightCount());
	}

	// -------------------------------------------------------------------------------------------
	// A late answer is silent (FR-015).
	// -------------------------------------------------------------------------------------------

	@Test
	public void anAnswerArrivingAfterExpiryIsIgnoredSilently() {
		InMemoryTransport transport = deferringTransport();
		JsonRpcClient client = client(transport, DEADLINE);
		UserApi api = client.proxy(UserApi.class);

		Promise<User> call = api.getUser(42);
		Exception expiry = awaitException(driven(call));
		assertThat(expiry, instanceOf(AsyncTimeoutException.class));

		// the genuine answer the dispatcher would have produced, correlated with the id the client chose —
		// which is exactly what a hand-written late document could not be
		assertEquals(1, transport.deferredCount());
		transport.answer(0);

		assertTrue("FR-015: no failure handler call", failures.isEmpty());
		assertEquals("FR-015: no entry is created by an answer nobody is waiting for", 0, client.inFlightCount());
		assertSame("the caller's promise keeps its expiry, not the late result", expiry, call.getException());
		assertFalse("a late answer is not a transport failure", transport.isClosed());
	}

	@Test
	public void aDuplicateAnswerAfterExpiryIsIgnoredSilently() {
		InMemoryTransport transport = deferringTransport();
		JsonRpcClient client = client(transport, DEADLINE);
		UserApi api = client.proxy(UserApi.class);

		Promise<User> call = api.getUser(7);
		awaitException(driven(call));

		byte[] request = transport.deferredText().get(0).getBytes(UTF_8);
		transport.answer(0);
		// and again: a duplicate is the same case as the first, since the first emptied no slot either
		transport.deliverFromPeer(answerFor(request));

		assertTrue(failures.isEmpty());
		assertEquals(0, client.inFlightCount());
	}

	// -------------------------------------------------------------------------------------------
	// Disarming (FR-014).
	// -------------------------------------------------------------------------------------------

	@Test
	public void nominalCompletionDisarmsTheDeadline() {
		RecordingReactor reactor = new RecordingReactor();
		JsonRpcClient client = client(reactor, answeringTransport(), DEADLINE);
		UserApi api = client.proxy(UserApi.class);

		User user = await(api.getUser(42));

		assertEquals(new User(42, "user-42"), user);
		assertEquals(1, reactor.deadlines().size());
		assertFalse("FR-014: the deadline is cancelled in the same removal path that settled the promise",
			reactor.deadlines().get(0).isActive());
		assertEquals(0, client.inFlightCount());
	}

	@Test
	public void aCompletedCallStaysCompletedPastTheDeadline() {
		JsonRpcClient client = client(answeringTransport(), DEADLINE);
		UserApi api = client.proxy(UserApi.class);

		Promise<User> call = api.getUser(42);
		User answered = await(call);

		// the behavioural half of the disarm claim: drive the loop past where the deadline would have fired
		await(Promises.delay(DEADLINE.multipliedBy(4)));

		assertSame("no late expiry re-settled a completed promise", answered, call.getResult());
		assertTrue(failures.isEmpty());
		assertEquals(0, client.inFlightCount());
	}

	@Test
	public void aRemoteErrorDisarmsTheDeadline() {
		RecordingReactor reactor = new RecordingReactor();
		JsonRpcClient client = client(reactor, answeringTransport(), DEADLINE);
		UserApi api = client.proxy(UserApi.class);

		// no such method on the dispatcher's contract is impossible through a proxy, so the remote error is
		// produced by answering a call this client's peer cannot satisfy — an unregistered service
		Exception e = awaitException(client.proxy(OtherApi.class).missing(1));

		assertThat(e, instanceOf(JsonRpcException.class));
		assertEquals(1, reactor.deadlines().size());
		assertFalse("a -32601 settles the call, so its deadline must be cancelled too",
			reactor.deadlines().get(0).isActive());
		assertEquals(0, client.inFlightCount());
	}

	@Test
	public void aSendFailureDisarmsTheDeadline() {
		RecordingReactor reactor = new RecordingReactor();
		ExpectedException cause = new ExpectedException("the write failed");
		JsonRpcClient client = client(reactor, new FailingSendTransport(cause), DEADLINE);
		UserApi api = client.proxy(UserApi.class);

		Exception e = awaitException(api.getUser(1));

		assertSame(cause, e);
		assertEquals(1, reactor.deadlines().size());
		assertFalse("FR-014: a send failure exits through the same removal path",
			reactor.deadlines().get(0).isActive());
		assertEquals(0, client.inFlightCount());
	}

	@Test
	public void sustainedLoadAccumulatesNoDeadTimerTasks() {
		RecordingReactor reactor = new RecordingReactor();
		JsonRpcClient client = client(reactor, answeringTransport(), DEADLINE);
		UserApi api = client.proxy(UserApi.class);

		int calls = 100_000;
		for (int i = 0; i < calls; i++) {
			// the in-memory transport answers inside send(), so each call settles before the next is issued
			assertTrue(api.getUser(i).isResult());
		}

		assertEquals(calls, reactor.deadlines().size());
		assertEquals("FR-014: every one of them left the queue", 0, reactor.activeDeadlineCount());
		assertEquals(0, client.inFlightCount());
	}

	// -------------------------------------------------------------------------------------------
	// Close and peer close (FR-016).
	// -------------------------------------------------------------------------------------------

	@Test
	public void localClosePurgesEveryEntryAndDisarmsEveryDeadline() {
		RecordingReactor reactor = new RecordingReactor();
		JsonRpcClient client = client(reactor, InMemoryTransport.silent(), DEADLINE);
		UserApi api = client.proxy(UserApi.class);

		Promise<User> first = api.getUser(1);
		Promise<User> second = api.getUser(2);
		client.close();

		assertThat(first.getException(), instanceOf(AsyncCloseException.class));
		assertThat(second.getException(), instanceOf(AsyncCloseException.class));
		assertEquals(2, reactor.deadlines().size());
		assertEquals("no dangling scheduled task survives a close", 0, reactor.activeDeadlineCount());
		assertEquals(0, client.inFlightCount());
	}

	@Test
	public void peerClosePurgesEveryEntryAndDisarmsEveryDeadline() {
		RecordingReactor reactor = new RecordingReactor();
		InMemoryTransport transport = InMemoryTransport.silent();
		JsonRpcClient client = client(reactor, transport, DEADLINE);
		UserApi api = client.proxy(UserApi.class);

		Promise<User> first = api.getUser(1);
		Promise<User> second = api.getUser(2);
		ExpectedException cause = new ExpectedException("the connection dropped");
		transport.closeFromPeer(cause);

		assertSame(cause, first.getException());
		assertSame(cause, second.getException());
		assertEquals(2, reactor.deadlines().size());
		assertEquals(0, reactor.activeDeadlineCount());
		assertEquals(0, client.inFlightCount());
	}

	@Test
	public void nothingFiresAfterACloseHasPurgedTheTable() {
		JsonRpcClient client = client(InMemoryTransport.silent(), DEADLINE);
		UserApi api = client.proxy(UserApi.class);

		Promise<User> pending = api.getUser(1);
		client.close();
		await(Promises.delay(DEADLINE.multipliedBy(4)));

		assertThat("the close cause stands; no expiry replaced it",
			pending.getException(), instanceOf(AsyncCloseException.class));
		assertTrue(failures.isEmpty());
	}

	@Test
	public void aCallAfterCloseArmsNothing() {
		RecordingReactor reactor = new RecordingReactor();
		JsonRpcClient client = client(reactor, InMemoryTransport.silent(), DEADLINE);
		UserApi api = client.proxy(UserApi.class);

		client.close();
		Promise<User> afterClose = api.getUser(1);

		assertTrue(afterClose.isException());
		assertTrue("FR-016: a call after close hands nothing to the transport and arms nothing",
			reactor.deadlines().isEmpty());
	}

	// -------------------------------------------------------------------------------------------
	// The setting, the builder, and the opt-out (FR-020).
	// -------------------------------------------------------------------------------------------

	@Test
	public void theDefaultIsThirtySeconds() {
		assertEquals(Duration.ofSeconds(30), JsonRpcClient.CALL_TIMEOUT);
	}

	@Test
	public void zeroDurationDisablesTheTimeoutEntirely() {
		RecordingReactor reactor = new RecordingReactor();
		JsonRpcClient client = client(reactor, InMemoryTransport.silent(), Duration.ZERO);
		UserApi api = client.proxy(UserApi.class);

		Promise<User> call = api.getUser(1);

		assertTrue("Duration.ZERO arms no deadline at all", reactor.deadlines().isEmpty());
		await(Promises.delay(DEADLINE.multipliedBy(10)));
		assertFalse("the pre-09 behaviour, reproduced exactly: the call simply pends", call.isComplete());
		assertEquals(1, client.inFlightCount());
	}

	@Test
	public void aNegativeCallTimeoutIsRejectedAtBuild() {
		JsonRpcClient.Builder builder = JsonRpcClient.builder(Reactor.getCurrentReactor(), InMemoryTransport.silent())
			.withCallTimeout(Duration.ofSeconds(-1));

		IllegalArgumentException e = assertThrows(IllegalArgumentException.class, builder::build);

		assertTrue("the rejected setting must be named: " + e.getMessage(), e.getMessage().contains("callTimeout"));
	}

	@Test
	public void withCallTimeoutRefusesNull() {
		assertThrows(NullPointerException.class,
			() -> JsonRpcClient.builder(Reactor.getCurrentReactor(), InMemoryTransport.silent()).withCallTimeout(null));
	}

	// -------------------------------------------------------------------------------------------
	// Per-call early release: the documented Promises.timeout pattern (FR-024).
	// -------------------------------------------------------------------------------------------

	@Test
	public void promisesTimeoutReleasesTheCallerBeforeTheClientsOwnDeadline() {
		Duration clientDeadline = Duration.ofMillis(400);
		JsonRpcClient client = client(InMemoryTransport.silent(), clientDeadline);
		UserApi api = client.proxy(UserApi.class);

		Promise<User> call = api.getUser(1);
		// FR-024: the caller composes the shorter bound; there is no per-call parameter on the proxy
		Exception early = awaitException(Promises.timeout(Duration.ofMillis(20), call));

		assertThat(early, instanceOf(AsyncTimeoutException.class));
		assertEquals("Promise timeout", early.getMessage());
		assertFalse("the underlying call is untouched — ActiveJ promises have no unsubscribe", call.isComplete());
		assertEquals("and its correlation entry lives until the client's own deadline", 1, client.inFlightCount());
	}

	@Test
	public void theEntryIsReclaimedAtTheClientsOwnDeadlineNotAtTheCallersEarlyRelease() {
		JsonRpcClient client = client(InMemoryTransport.silent(), DEADLINE);
		UserApi api = client.proxy(UserApi.class);

		Promise<User> call = api.getUser(1);
		awaitException(Promises.timeout(Duration.ofMillis(1), call));
		assertEquals(1, client.inFlightCount());

		// drive on to the client's own deadline: the table is what the deadline is for
		Exception e = awaitException(driven(call));

		assertThat(e, instanceOf(AsyncTimeoutException.class));
		assertTrue(e.getMessage().contains("user.get"));
		assertEquals(0, client.inFlightCount());
	}

	// -------------------------------------------------------------------------------------------
	// Harness.
	// -------------------------------------------------------------------------------------------

	/** See the class documentation: a foreground guard, an order of magnitude above anything under test. */
	private static <T> Promise<T> driven(Promise<T> promise) {
		return Promises.timeout(GUARD, promise);
	}

	private JsonRpcClient client(JsonRpcTransport transport, Duration callTimeout) {
		return client(Reactor.getCurrentReactor(), transport, callTimeout);
	}

	private JsonRpcClient client(Reactor reactor, JsonRpcTransport transport, Duration callTimeout) {
		JsonRpcClient client = JsonRpcClient.builder(reactor, transport)
			.withCallTimeout(callTimeout)
			.withFailureHandler(failures::add)
			.build();
		clients.add(client);
		return client;
	}

	/** A real dispatcher behind the transport, answering in the calling frame. */
	private static InMemoryTransport answeringTransport() {
		return InMemoryTransport.create(dispatcher()::dispatch);
	}

	/** The same dispatcher, consulted only when the test says so — so an answer can be made <i>late</i>. */
	private static InMemoryTransport deferringTransport() {
		InMemoryTransport transport = InMemoryTransport.create(dispatcher()::dispatch);
		transport.startDeferringAnswers();
		return transport;
	}

	private static JsonRpcDispatcher dispatcher() {
		return JsonRpcDispatcher.builder(Reactor.getCurrentReactor())
			.withService(UserApi.class, new UserApiImpl())
			.build();
	}

	/** The request document a deferred entry holds, answered by a dispatcher that never saw it defer. */
	private static byte[] answerFor(byte[] request) {
		return await(dispatcher().dispatch(request));
	}

	/** A service this client's peer does not implement — the shortest route to a remote {@code -32601}. */
	@JsonRpcService("other")
	public interface OtherApi {
		@JsonRpcMethod("missing")
		Promise<String> missing(@JsonRpcParam("id") long id);
	}

	/** Fails every {@code send} — FR-014's send-failure exit from the removal path. */
	private static final class FailingSendTransport implements JsonRpcTransport {
		private final Exception cause;

		private FailingSendTransport(Exception cause) {this.cause = cause;}

		@Override
		public Promise<Void> send(byte[] document) {
			return Promise.ofException(cause);
		}

		@Override
		public void setListener(Listener listener) {}

		@Override
		public void closeEx(Exception e) {}
	}

	/**
	 * A {@link Reactor} that records every background schedule and delegates everything else.
	 * <p>
	 * {@code Eventloop} is {@code final}, so a delegating implementation of the interface is the only seam —
	 * and it is a faithful one: the captured {@link ScheduledRunnable} is the very object handed to the real
	 * eventloop, so {@link ScheduledRunnable#isActive()} reads the real queue membership.
	 */
	private static final class RecordingReactor implements Reactor {
		private final Reactor delegate = Reactor.getCurrentReactor();
		private final List<ScheduledRunnable> deadlines = new ArrayList<>();

		List<ScheduledRunnable> deadlines() {
			return deadlines;
		}

		int activeDeadlineCount() {
			return (int) deadlines.stream().filter(ScheduledRunnable::isActive).count();
		}

		@Override
		public void scheduleBackground(ScheduledRunnable scheduledRunnable) {
			deadlines.add(scheduledRunnable);
			delegate.scheduleBackground(scheduledRunnable);
		}

		@Override
		public void schedule(ScheduledRunnable scheduledRunnable) {
			delegate.schedule(scheduledRunnable);
		}

		@Override
		public long currentTimeMillis() {
			return delegate.currentTimeMillis();
		}

		@Override
		public boolean inReactorThread() {
			return delegate.inReactorThread();
		}

		@Override
		public void post(Runnable runnable) {
			delegate.post(runnable);
		}

		@Override
		public void postLast(Runnable runnable) {
			delegate.postLast(runnable);
		}

		@Override
		public void postNext(Runnable runnable) {
			delegate.postNext(runnable);
		}

		@Override
		public void startExternalTask() {
			delegate.startExternalTask();
		}

		@Override
		public void completeExternalTask() {
			delegate.completeExternalTask();
		}

		@Override
		public void logFatalError(Throwable e, @Nullable Object context) {
			delegate.logFatalError(e, context);
		}

		@Override
		public CompletableFuture<Void> submit(RunnableEx computation) {
			return delegate.submit(computation);
		}

		@Override
		public <T> CompletableFuture<T> submit(AsyncComputation<? extends T> computation) {
			return delegate.submit(computation);
		}

		@Override
		public void execute(Runnable command) {
			delegate.execute(command);
		}
	}
}
