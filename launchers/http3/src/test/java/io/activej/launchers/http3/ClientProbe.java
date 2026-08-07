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
import io.activej.test.EventloopThread;

/**
 * The test-side bridge into the two reactors of a launcher test (T036/T037).
 * <p>
 * The {@link Http3Client} runs on its <b>own dedicated {@link Eventloop} thread</b> (an
 * {@link EventloopThread}) — the {@code Http3RealSocketInteropTest} shape — not on the launcher's
 * reactor. This is a recorded divergence from the plan's shared-reactor wiring: under a saturated
 * {@code -T1C verify} the launcher's single reactor thread could not complete a QUIC handshake
 * within the client's 10 s bound when it also carried the server endpoint (observed 3×10 s timeouts
 * in task-results/T039.md), while the split-loop interop suite passed in the same run. The client is
 * still created by the test subclass's scanned {@code @Provides} (the launcher's DI graph), with
 * the TLS initializer supplied by the test — RFC 6125 stays live against the dev leaf.
 * <p>
 * The JUnit thread never touches a reactive component (SI-9): everything runs through a submit
 * bridge that posts to the owning reactor thread and blocks the caller on the resulting future.
 * Blocking is safe because both reactors run on their own threads. {@link #onLauncher(RunnableEx)}
 * targets the launcher's reactor (servlet-side work), which this probe does <b>not</b> own — hence
 * the static {@link EventloopThread#await} bridge rather than an owned loop;
 * {@link #onClient(RunnableEx)} targets the client's, which it does.
 * <p>
 * The client is deliberately <b>not</b> a service: {@code Http3Client} is never bound as a DI key —
 * it is constructed inside {@code create(...)} on its own eventloop and reachable only through this
 * probe — so the service graph never sees it and no exclusion is needed. It is closed by
 * {@link #closeClient()} before {@code launcher.shutdown()}, which also reaps the client loop's
 * daemon thread.
 */
public final class ClientProbe {
	private final Eventloop launcherEventloop;
	private final EventloopThread clientLoop;
	private final Http3Client client;

	private ClientProbe(Eventloop launcherEventloop, EventloopThread clientLoop, Http3Client client) {
		this.launcherEventloop = launcherEventloop;
		this.clientLoop = clientLoop;
		this.client = client;
	}

	/**
	 * Starts the client loop and builds the {@link Http3Client} on it (WI-2: the client must be
	 * constructed on its own reactor thread). A failure of the build — or of the bounded await —
	 * unwinds the already-started loop through {@link EventloopThread#close()}, so no loop is left
	 * parked for the rest of the JVM.
	 */
	public static ClientProbe create(Eventloop launcherEventloop, IDnsClient dnsClient,
		Initializer<TlsClientConfig.Builder> tlsConfig) {
		EventloopThread clientLoop = EventloopThread.create("http3-launcher-test-client");
		try {
			Http3Client client = clientLoop.submit(() ->
				Http3Client.builder(clientLoop.eventloop(), dnsClient)
					.withTlsClientConfig(tlsConfig)
					.build());
			// its socket's selector key would otherwise hold the loop open past closeClient()
			clientLoop.onClose(client::close);
			return new ClientProbe(launcherEventloop, clientLoop, client);
		} catch (RuntimeException | Error e) {
			try {
				clientLoop.close();
			} catch (RuntimeException | Error teardown) {
				// a teardown that also fails must not mask what actually broke the build
				e.addSuppressed(teardown);
			}
			throw e;
		}
	}

	/** Runs a computation on the <b>launcher's</b> reactor thread and returns its result. */
	public <T> T onLauncher(SupplierEx<T> computation) {
		return EventloopThread.await(
			launcherEventloop.submit(AsyncComputation.of(computation)), "the launcher reactor");
	}

	/** Runs an action on the <b>launcher's</b> reactor thread and blocks the caller until it ran. */
	public void onLauncher(RunnableEx action) {
		EventloopThread.await(launcherEventloop.submit(action), "the launcher reactor");
	}

	/** Runs a computation on the <b>client's</b> reactor thread and returns its result. */
	public <T> T onClient(SupplierEx<T> computation) {
		return clientLoop.submit(computation);
	}

	/** Runs an action on the <b>client's</b> reactor thread and blocks the caller until it ran. */
	public void onClient(RunnableEx action) {
		clientLoop.submit(action);
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
		clientLoop.close();
	}
}
