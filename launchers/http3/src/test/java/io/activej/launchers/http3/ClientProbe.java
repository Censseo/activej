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

package io.activej.launchers.http3;

import io.activej.async.callback.AsyncComputation;
import io.activej.common.function.RunnableEx;
import io.activej.common.function.SupplierEx;
import io.activej.common.initializer.Initializer;
import io.activej.dns.IDnsClient;
import io.activej.eventloop.Eventloop;
import io.activej.http3.Http3Client;
import io.activej.quic.tls.TlsClientConfig;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * The test-side bridge into the two reactors of a launcher test (T036/T037).
 * <p>
 * The {@link Http3Client} runs on its <b>own dedicated {@link Eventloop} thread</b> — the
 * {@code Http3RealSocketInteropTest} shape — not on the launcher's reactor. This is a recorded
 * divergence from the plan's shared-reactor wiring: under a saturated {@code -T1C verify} the
 * launcher's single reactor thread could not complete a QUIC handshake within the client's 10 s
 * bound when it also carried the server endpoint (observed 3×10 s timeouts in
 * task-results/T039.md), while the split-loop interop suite passed in the same run. The client is
 * still created by the test subclass's scanned {@code @Provides} (the launcher's DI graph), with
 * the TLS initializer supplied by the test — RFC 6125 stays live against the dev leaf.
 * <p>
 * The JUnit thread never touches a reactive component (SI-9): everything runs through
 * {@link Eventloop#submit(AsyncComputation)}, which posts to the owning reactor thread and blocks
 * the caller on the resulting future. Blocking is safe because both reactors run on their own
 * threads. {@link #onLauncher(...)} targets the launcher's reactor (servlet-side work);
 * {@link #onClient(...)} targets the client's (requests, bodies, close).
 * <p>
 * The client is deliberately <b>not</b> a service: {@code Http3Client} is never bound as a DI key —
 * it is constructed inside {@code create(...)} on its own eventloop and reachable only through this
 * probe — so the service graph never sees it and no exclusion is needed. It is closed by
 * {@link #closeClient()} before {@code launcher.shutdown()}, which also reaps the client loop's
 * daemon thread.
 */
public final class ClientProbe {
	private static final long CALL_TIMEOUT_SECONDS = 10;
	private static final long JOIN_TIMEOUT_MILLIS = 10_000;
	private static final long BREAK_JOIN_TIMEOUT_MILLIS = 2_000;

	private final Eventloop launcherEventloop;
	private final Eventloop clientEventloop;
	private final Http3Client client;
	private final Thread clientThread;
	private boolean closed;

	private ClientProbe(Eventloop launcherEventloop, Eventloop clientEventloop, Http3Client client, Thread clientThread) {
		this.launcherEventloop = launcherEventloop;
		this.clientEventloop = clientEventloop;
		this.client = client;
		this.clientThread = clientThread;
	}

	/**
	 * Starts the client loop and builds the {@link Http3Client} on it (WI-2: the client must be
	 * constructed on its own reactor thread). A failure of the build — or of the bounded await —
	 * unwinds the already-started loop: {@code keepAlive(true)} is reset and the thread reaped, so
	 * no loop is left parked for the rest of the JVM.
	 */
	public static ClientProbe create(Eventloop launcherEventloop, IDnsClient dnsClient,
		Initializer<TlsClientConfig.Builder> tlsConfig) {
		Eventloop clientEventloop = Eventloop.create();
		clientEventloop.keepAlive(true);
		Thread clientThread = new Thread(clientEventloop, "http3-launcher-test-client");
		clientThread.setDaemon(true);
		clientThread.start();
		try {
			Http3Client client = await(clientEventloop.submit(AsyncComputation.of(() ->
				Http3Client.builder(clientEventloop, dnsClient)
					.withTlsClientConfig(tlsConfig)
					.build())));
			return new ClientProbe(launcherEventloop, clientEventloop, client, clientThread);
		} catch (RuntimeException | Error e) {
			// The build failed or timed out: nothing on the loop holds it open, so releasing the
			// keep-alive lets the loop exit and the (daemon) thread terminate on its own.
			clientEventloop.keepAlive(false);
			try {
				clientThread.join(JOIN_TIMEOUT_MILLIS);
			} catch (InterruptedException interrupted) {
				Thread.currentThread().interrupt();
			}
			throw e;
		}
	}

	/** Runs a computation on the <b>launcher's</b> reactor thread and returns its result. */
	public <T> T onLauncher(SupplierEx<T> computation) {
		return await(launcherEventloop.submit(AsyncComputation.of(computation)));
	}

	/** Runs an action on the <b>launcher's</b> reactor thread and blocks the caller until it ran. */
	public void onLauncher(RunnableEx action) {
		await(launcherEventloop.submit(action));
	}

	/** Runs a computation on the <b>client's</b> reactor thread and returns its result. */
	public <T> T onClient(SupplierEx<T> computation) {
		return await(clientEventloop.submit(AsyncComputation.of(computation)));
	}

	/** Runs an action on the <b>client's</b> reactor thread and blocks the caller until it ran. */
	public void onClient(RunnableEx action) {
		await(clientEventloop.submit(action));
	}

	public Http3Client client() {
		return client;
	}

	/**
	 * Closes the client on its loop and reaps the loop's thread. Idempotent; must run before
	 * {@code launcher.shutdown()} — the launcher's eventloop join cannot complete while the
	 * client's UDP socket key is registered on it (the shared-reactor version of that argument
	 * holds here too, since the client's socket lives on its own selector).
	 */
	public void closeClient() {
		if (closed) return;
		closed = true;
		Exception failure = null;
		try {
			clientEventloop.submit(client::close).get(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			failure = e;
		} catch (ExecutionException | TimeoutException e) {
			failure = e;
		}
		clientEventloop.keepAlive(false);
		try {
			clientThread.join(JOIN_TIMEOUT_MILLIS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			failure = failure == null ? e : failure;
		}
		if (clientThread.isAlive()) {
			clientEventloop.breakEventloop();
			try {
				clientThread.join(BREAK_JOIN_TIMEOUT_MILLIS);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				failure = failure == null ? e : failure;
			}
		}
		if (failure != null) {
			throw new IllegalStateException("Client loop teardown did not complete cleanly", failure);
		}
	}

	private static <T> T await(CompletableFuture<T> future) {
		try {
			return future.get(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Interrupted while waiting for a launcher-test reactor", e);
		} catch (ExecutionException e) {
			throw new IllegalStateException("A launcher-test reactor task failed", e.getCause());
		} catch (TimeoutException e) {
			throw new IllegalStateException("A launcher-test reactor did not answer within " +
				CALL_TIMEOUT_SECONDS + " seconds", e);
		}
	}
}
