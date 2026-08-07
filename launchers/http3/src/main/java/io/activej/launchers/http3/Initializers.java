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

import io.activej.common.annotation.StaticFactories;
import io.activej.common.initializer.Initializer;
import io.activej.config.Config;
import io.activej.http3.Http3Server;
import io.activej.http3.Http3Settings;

import java.net.InetSocketAddress;

import static io.activej.config.converter.ConfigConverters.ofDuration;
import static io.activej.config.converter.ConfigConverters.ofInetSocketAddress;
import static io.activej.config.converter.ConfigConverters.ofInteger;
import static io.activej.config.converter.ConfigConverters.ofMemSize;

/**
 * {@code Config}→builder bundles for the {@code http3} key table (contracts §4). Keys are read
 * relative to the {@code http3} child — the launcher passes {@code config.getChild("http3")}.
 *
 * <table>
 *     <caption>Configuration keys (defaults are {@code Http3Settings}' own — this feature adds no
 *     runtime limit)</caption>
 *     <tr><th>Key</th><th>Converter</th><th>Default</th></tr>
 *     <tr><td>{@code listenAddresses}</td><td>{@code ofInetSocketAddress()}</td>
 *         <td>{@code localhost:4433}</td></tr>
 *     <tr><td>{@code settings.maxBodySize}</td><td>{@code ofMemSize()}</td>
 *         <td>{@code Http3Settings}' default (100 MB)</td></tr>
 *     <tr><td>{@code settings.maxFieldSectionSize}</td><td>{@code ofMemSize()}</td>
 *         <td>{@code Http3Settings}' default (64 kB)</td></tr>
 *     <tr><td>{@code settings.maxControlFrameSize}</td><td>{@code ofMemSize()}</td>
 *         <td>{@code Http3Settings}' default (16 kB)</td></tr>
 *     <tr><td>{@code settings.maxConcurrentRequestStreams}</td><td>{@code ofInteger()}</td>
 *         <td>{@code Http3Settings}' default (100)</td></tr>
 *     <tr><td>{@code settings.shutdownTimeout}</td><td>{@code ofDuration()}</td>
 *         <td>{@code Http3Settings}' default (1 s)</td></tr>
 * </table>
 *
 * The certificate keys ({@code certificateChain}, {@code privateKey}) are <b>not</b> read here:
 * {@code TlsServerIdentity.fromPem} throws a checked {@code IOException}, an {@code Initializer}
 * cannot, and FR-025's failure contract (name the config key and path, never key material) is the
 * launcher's {@code @Provides server(...)}. The feature-006 capabilities
 * ({@code qpackMaxTableCapacity}, {@code zeroRttEnabled}, {@code datagramsEnabled}) are
 * deliberately <b>not</b> exposed as keys (T038, FR-029): each is a knowing opt-in via
 * {@code getBusinessLogicModule()}.
 *
 * <p>The {@code eventloop.*} row of the contracts §4 table is <b>not</b> read here either: the
 * launcher applies {@code launchers/common}'s {@code ofEventloop} to {@code config.getChild("eventloop")}
 * in its {@code @Provides reactor(...)} ({@code fatalErrorHandler}, {@code idleInterval},
 * {@code threadPriority}), reused unchanged. This class implements exactly the five settings keys
 * above plus {@code listenAddresses}, matching contracts §4 key-for-key; the two certificate keys
 * are implemented by {@link Http3ServerLauncher} instead (FR-025) — see its Javadoc table.
 *
 * <p>This class lives in {@code launchers/http3}, not {@code launchers/common}: the common module
 * is depended on by {@code launchers/{http,fs,rpc}} and must not gain a {@code core-http3} edge
 * (FR-023c).
 */
@StaticFactories(Initializer.class)
public class Initializers {
	private Initializers() {}

	public static Initializer<Http3Server.Builder> ofHttp3Server(Config config) {
		return builder -> builder
			.withListenAddress(config.get(ofInetSocketAddress(), "listenAddresses",
				new InetSocketAddress(Http3ServerLauncher.HOSTNAME, Http3ServerLauncher.PORT)))
			.withSettings(Http3Settings.builder()
				.initialize(ofHttp3Settings(config.getChild("settings")))
				.build());
	}

	public static Initializer<Http3Settings.Builder> ofHttp3Settings(Config config) {
		return builder -> builder
			.setIfNotNull(Http3Settings.Builder::withMaxBodySize, config.get(ofMemSize(), "maxBodySize", null))
			.setIfNotNull(Http3Settings.Builder::withMaxFieldSectionSize, config.get(ofMemSize(), "maxFieldSectionSize", null))
			.setIfNotNull(Http3Settings.Builder::withMaxControlFrameSize, config.get(ofMemSize(), "maxControlFrameSize", null))
			.setIfNotNull(Http3Settings.Builder::withMaxConcurrentRequestStreams,
				config.get(ofInteger(), "maxConcurrentRequestStreams", null))
			.setIfNotNull(Http3Settings.Builder::withShutdownTimeout, config.get(ofDuration(), "shutdownTimeout", null));
	}
}
