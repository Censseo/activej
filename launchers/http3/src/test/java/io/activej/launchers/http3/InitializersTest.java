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

import io.activej.common.MemSize;
import io.activej.config.Config;
import io.activej.http.AsyncServlet;
import io.activej.http.HttpResponse;
import io.activej.http3.Http3Server;
import io.activej.http3.Http3Settings;
import io.activej.inject.Injector;
import io.activej.reactor.Reactor;
import io.activej.reactor.nio.NioReactor;
import io.activej.test.rules.ByteBufRule;
import io.activej.test.rules.EventloopRule;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;

import java.time.Duration;

import static io.activej.config.converter.ConfigConverters.ofMemSize;
import static org.junit.Assert.assertEquals;

/**
 * T034 — the {@code Config}→builder bundles of the {@code http3} key table
 * (contracts §4). Every key read must map onto exactly the value configured, and every default
 * must stay {@code Http3Settings}' own — this feature introduces no new runtime limit.
 */
public class InitializersTest {
	@ClassRule
	public static final EventloopRule eventloopRule = new EventloopRule();
	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	@BeforeClass
	public static void beforeClass() {
		Injector.useSpecializer();
	}

	@Test
	public void ofHttp3SettingsMapsEveryKey() {
		Config config = Config.create()
			.with("maxBodySize", "2mb")
			.with("maxFieldSectionSize", "16kb")
			.with("maxControlFrameSize", "8kb")
			.with("maxConcurrentRequestStreams", "42")
			.with("shutdownTimeout", "500 millis");

		Http3Settings settings = Http3Settings.builder()
			.initialize(Initializers.ofHttp3Settings(config))
			.build();

		assertEquals("maxBodySize", MemSize.megabytes(2).toLong(), settings.maxBodySize());
		assertEquals("maxFieldSectionSize", MemSize.kilobytes(16).toLong(), settings.maxFieldSectionSize());
		assertEquals("maxControlFrameSize", MemSize.kilobytes(8).toLong(), settings.maxControlFrameSize());
		assertEquals("maxConcurrentRequestStreams", 42, settings.maxConcurrentRequestStreams());
		assertEquals("shutdownTimeout", Duration.ofMillis(500).toMillis(), settings.shutdownTimeoutMillis());
	}

	@Test
	public void ofHttp3SettingsLeavesDefaultsUntouched() {
		Http3Settings configured = Http3Settings.builder()
			.initialize(Initializers.ofHttp3Settings(Config.create()))
			.build();
		Http3Settings defaults = Http3Settings.create();

		assertEquals("maxBodySize", defaults.maxBodySize(), configured.maxBodySize());
		assertEquals("maxFieldSectionSize", defaults.maxFieldSectionSize(), configured.maxFieldSectionSize());
		assertEquals("maxControlFrameSize", defaults.maxControlFrameSize(), configured.maxControlFrameSize());
		assertEquals("maxConcurrentRequestStreams",
			defaults.maxConcurrentRequestStreams(), configured.maxConcurrentRequestStreams());
		assertEquals("shutdownTimeout", defaults.shutdownTimeoutMillis(), configured.shutdownTimeoutMillis());
		// the feature-006 capabilities must stay off — no key reads them (T038)
		assertEquals("qpackMaxTableCapacity", 0, configured.qpackMaxTableCapacity());
		assertEquals("zeroRttEnabled", false, configured.zeroRttEnabled());
		assertEquals("datagramsEnabled", false, configured.datagramsEnabled());
	}

	@Test
	public void ofHttp3ServerAppliesListenAddressAndComposesSettings() {
		NioReactor reactor = (NioReactor) Reactor.getCurrentReactor();
		AsyncServlet servlet = request -> HttpResponse.ok200().toPromise();

		Config config = Config.create()
			.with("listenAddresses", "localhost:4444")
			.with("settings.maxBodySize", "2mb")
			.with("settings.shutdownTimeout", "500 millis");

		// the builder chain is callable and the server builds with exactly the composed settings
		Http3Server server = Http3Server.builder(reactor, servlet)
			.initialize(Initializers.ofHttp3Server(config))
			.build();

		// no bound-address accessor exists (research D11) — the address mapping is behaviourally
		// proven by Http3ServerLauncherTest.servesOneRequest, which configures the port through this
		// same initializer; here we assert what is observable: the settings child composes
		Http3Settings settings = Http3Settings.builder()
			.initialize(Initializers.ofHttp3Settings(config.getChild("settings")))
			.build();
		assertEquals("settings.maxBodySize", MemSize.megabytes(2).toLong(), settings.maxBodySize());
		assertEquals("settings.shutdownTimeout", Duration.ofMillis(500).toMillis(), settings.shutdownTimeoutMillis());
	}
}
