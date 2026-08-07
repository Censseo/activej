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

import io.activej.config.Config;
import io.activej.config.ConfigModule;
import io.activej.eventloop.Eventloop;
import io.activej.eventloop.inspector.ThrottlingController;
import io.activej.http.AsyncServlet;
import io.activej.http.HttpResponse;
import io.activej.http3.Http3Server;
import io.activej.inject.annotation.Inject;
import io.activej.inject.annotation.Provides;
import io.activej.inject.annotation.ProvidesIntoSet;
import io.activej.inject.binding.OptionalDependency;
import io.activej.inject.module.AbstractModule;
import io.activej.inject.module.Module;
import io.activej.launcher.Launcher;
import io.activej.quic.tls.TlsServerIdentity;
import io.activej.reactor.nio.NioReactor;
import io.activej.service.ServiceGraphModule;
import io.activej.service.ServiceGraphModuleSettings;
import io.activej.common.initializer.Initializer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.NoSuchElementException;

import static io.activej.config.Config.ofClassPathProperties;
import static io.activej.config.Config.ofSystemProperties;
import static io.activej.config.converter.ConfigConverters.ofInetSocketAddress;
import static io.activej.config.converter.ConfigConverters.ofPath;
import static io.activej.inject.module.Modules.combine;
import static io.activej.launchers.initializers.Initializers.ofEventloop;

/**
 * Preconfigured HTTP/3 server launcher: an {@link Http3Server} serving an {@link AsyncServlet}
 * over QUIC, wired the ActiveJ way — DI, service graph, {@code Config}.
 * <p>
 * Configuration keys (see {@link Initializers} for the full table):
 * <table>
 *     <caption>Keys (contracts §4 — defaults are {@code Http3Settings}' own; this feature adds no
 *     runtime limit)</caption>
 *     <tr><th>Key</th><th>Converter</th><th>Type</th><th>Default</th></tr>
 *     <tr><td>{@code http3.listenAddresses}</td><td>{@code ofInetSocketAddress()}</td>
 *         <td>{@code InetSocketAddress}</td><td>{@code localhost:4433}</td></tr>
 *     <tr><td>{@code http3.certificateChain}</td><td>{@code ofPath()}</td>
 *         <td>{@code Path}</td><td><b>required</b> — no default identity</td></tr>
 *     <tr><td>{@code http3.privateKey}</td><td>{@code ofPath()}</td>
 *         <td>{@code Path}</td><td><b>required</b> — unencrypted PKCS#8 PEM;
 *         {@code TlsServerIdentity.fromPem} verifies it matches the leaf</td></tr>
 *     <tr><td>{@code http3.settings.maxBodySize}</td><td>{@code ofMemSize()}</td>
 *         <td>{@code MemSize}</td><td>{@code Http3Settings}' default (100 MB)</td></tr>
 *     <tr><td>{@code http3.settings.maxFieldSectionSize}</td><td>{@code ofMemSize()}</td>
 *         <td>{@code MemSize}</td><td>{@code Http3Settings}' default (64 kB)</td></tr>
 *     <tr><td>{@code http3.settings.maxControlFrameSize}</td><td>{@code ofMemSize()}</td>
 *         <td>{@code MemSize}</td><td>{@code Http3Settings}' default (16 kB)</td></tr>
 *     <tr><td>{@code http3.settings.maxConcurrentRequestStreams}</td><td>{@code ofInteger()}</td>
 *         <td>{@code Integer}</td><td>{@code Http3Settings}' default (100)</td></tr>
 *     <tr><td>{@code http3.settings.shutdownTimeout}</td><td>{@code ofDuration()}</td>
 *         <td>{@code Duration}</td><td>{@code Http3Settings}' default (1 s) —
 *         the GOAWAY drain ceiling</td></tr>
 *     <tr><td>{@code eventloop.*}</td><td>{@code launchers/common} {@code ofEventloop}</td>
 *         <td>—</td><td>—</td></tr>
 * </table>
 * <p>
 * Precedence follows the platform convention: programmatic defaults → {@value #PROPERTIES_FILE} on
 * the classpath → {@code -Dconfig.*} system properties. {@code ConfigModule.withEffectiveConfigLogger()}
 * reports what was actually consumed.
 * <p>
 * Startup fails, naming the <b>config key and path</b> — never any part of the key material — when
 * the certificate or private key is absent or unparseable (FR-025).
 *
 * @see Launcher
 */
public abstract class Http3ServerLauncher extends Launcher {
	public static final String HOSTNAME = "localhost";
	public static final int PORT = 4433;
	public static final String PROPERTIES_FILE = "http3-server.properties";

	@Inject
	Http3Server http3Server;

	@Inject
	Config config;

	@Provides
	NioReactor reactor(Config config, OptionalDependency<ThrottlingController> throttlingController) {
		return Eventloop.builder()
			.initialize(ofEventloop(config.getChild("eventloop")))
			.withInspector(throttlingController.orElse(null))
			.build();
	}

	@Provides
	Http3Server server(NioReactor reactor, AsyncServlet rootServlet, Config config) {
		Config http3 = config.getChild("http3");
		TlsServerIdentity identity = loadIdentity(http3);
		return Http3Server.builder(reactor, rootServlet)
			.withServerIdentity(identity)
			.initialize(Initializers.ofHttp3Server(http3))
			.build();
	}

	/**
	 * Loads the TLS identity, wrapping every failure so the message names the <b>config key and the
	 * path</b> — never any part of the key material (FR-025, SI-6). There is no default identity:
	 * a missing key is a required-key failure, and {@code TlsServerIdentity.fromPem}'s own messages
	 * already carry only the path (verified), so the wrapping adds no secret.
	 * <p>
	 * Two failure shapes are wrapped here that are not "missing": a value {@code Paths.get} cannot
	 * parse (an {@code InvalidPathException}, which {@code ConfigConverters.ofPath}'s
	 * {@code SimpleConfigConverter} rethrows as a plain {@code IllegalArgumentException}) and a
	 * signing-probe failure inside {@code TlsServerIdentity}'s constructor (a
	 * {@code SignatureException} that {@code TlsSignatures} wraps in an {@code IllegalStateException}
	 * which {@code fromPem}'s {@code throws} clause does not declare). Both must name the offending
	 * key just as a missing one does.
	 */
	private static TlsServerIdentity loadIdentity(Config http3) {
		Path certificateChain;
		try {
			certificateChain = http3.get(ofPath(), "certificateChain");
		} catch (NoSuchElementException | IllegalArgumentException e) {
			throw new RuntimeException(
				"Required config key http3.certificateChain is not set, or its value is not a valid path", e);
		}
		Path privateKey;
		try {
			privateKey = http3.get(ofPath(), "privateKey");
		} catch (NoSuchElementException | IllegalArgumentException e) {
			throw new RuntimeException(
				"Required config key http3.privateKey is not set, or its value is not a valid path", e);
		}
		try {
			return TlsServerIdentity.fromPem(certificateChain, privateKey);
		} catch (IOException | IllegalArgumentException | IllegalStateException e) {
			throw new RuntimeException(
				"Failed to load the HTTP/3 server identity: " +
					"config key http3.certificateChain=" + certificateChain +
					", config key http3.privateKey=" + privateKey + ": " + e.getMessage(), e);
		}
	}

	@Provides
	Config config() {
		return Config.create()
			.with("http3.listenAddresses", Config.ofValue(ofInetSocketAddress(), new InetSocketAddress(HOSTNAME, PORT)))
			.overrideWith(ofClassPathProperties(PROPERTIES_FILE, true))
			.overrideWith(ofSystemProperties("config"));
	}

	/**
	 * Registers the {@link Http3ServerServiceAdapter} so the service graph starts the server
	 * ({@code listen()}) and stops it ({@code close()}) — see the adapter's Javadoc: its
	 * {@code stop()} waits for the GOAWAY drain itself, bounded by the drain ceiling.
	 */
	@ProvidesIntoSet
	Initializer<ServiceGraphModuleSettings> serviceGraphInitializer() {
		return settings -> settings.with(Http3Server.class, Http3ServerServiceAdapter.create());
	}

	@Override
	protected final Module getModule() {
		return combine(
			ServiceGraphModule.create(),
			ConfigModule.builder()
				.withEffectiveConfigLogger()
				.build(),
			getBusinessLogicModule()
		);
	}

	/**
	 * Override this method to supply your launcher business logic.
	 */
	protected Module getBusinessLogicModule() {
		return Module.empty();
	}

	/**
	 * Logs the address this server is available at — read from {@code Config}, the only source
	 * there is: {@code Http3Server} exposes no bound-address accessor (research D11).
	 * <p>
	 * This must happen in {@code onStart()}, <b>not</b> in {@code run()}: {@code ConfigModule}
	 * wraps every {@code Config} in a {@code ProtectedConfig} that refuses reads once the
	 * launcher's {@code @OnStart} stage completes — which is exactly when {@code run()} executes.
	 * {@code onStart()} runs before that stage completes, so the read is still "application
	 * start-up time" there. {@code HttpServerLauncher} avoids the problem with a runtime accessor
	 * ({@code getHttpAddresses()}); this launcher has none.
	 */
	@Override
	protected void onStart() throws Exception {
		if (logger.isInfoEnabled()) {
			InetSocketAddress address = config.get(ofInetSocketAddress(), "http3.listenAddresses",
				new InetSocketAddress(HOSTNAME, PORT));
			logger.info("HTTP/3 Server is now available at https://{}", address);
		}
	}

	@Override
	protected void run() throws Exception {
		awaitShutdown();
	}

	public static void main(String[] args) throws Exception {

		Launcher launcher = new Http3ServerLauncher() {
			@Override
			protected Module getBusinessLogicModule() {
				return new AbstractModule() {
					@Provides
					public AsyncServlet servlet(Config config) {
						String message = config.get("message", "Hello, world!");
						return request -> HttpResponse.ok200()
							.withPlainText(message)
							.toPromise();
					}
				};
			}
		};

		launcher.launch(args);
	}
}
