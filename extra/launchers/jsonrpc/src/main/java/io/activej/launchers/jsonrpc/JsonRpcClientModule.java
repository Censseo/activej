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

import io.activej.common.initializer.Initializer;
import io.activej.config.Config;
import io.activej.http.IHttpClient;
import io.activej.inject.annotation.Provides;
import io.activej.inject.annotation.ProvidesIntoSet;
import io.activej.inject.module.AbstractModule;
import io.activej.jsonrpc.service.JsonRpcClient;
import io.activej.jsonrpc.transport.http.JsonRpcHttpClientTransport;
import io.activej.reactor.nio.NioReactor;
import io.activej.service.ServiceGraphModuleSettings;

import static io.activej.config.converter.ConfigConverters.ofDuration;

/**
 * Client-side wiring, <b>separate</b> from {@link JsonRpcModule} (FR-010a): a server-only application
 * never acquires a client binding, and the client's {@link JsonRpcClientServiceAdapter} registers only
 * where a client actually exists (FR-060).
 * <p>
 * Lives in this launcher module because it is the only place permitted to depend on both the transport
 * and the boot stack — neither {@code cloud-jsonrpc} nor {@code cloud-jsonrpc-http} may gain a
 * {@code boot} edge (FR-060).
 * <p>
 * The target endpoint is configured with {@code jsonrpc.client.url} (default
 * {@code http://localhost:8080/}, matching the launcher's server default).
 * <p>
 * {@code jsonrpc.callTimeout} is this module's <b>second</b> key (feature 019, FR-021). Feature 014 reserved
 * it as a fail-closed non-key because no per-instance seam existed; {@link JsonRpcClient.Builder} now has
 * one, so the reservation is spent and the key is read here. Absent, {@link JsonRpcClient#CALL_TIMEOUT}
 * stands (30 s); {@code 0 seconds} disables the deadline; a negative value fails wiring naming the setting.
 * ⚠ It is a {@code Duration}, so it needs the space and a long-form unit — {@code 250 millis} parses,
 * {@code 250ms} does not.
 */
public final class JsonRpcClientModule extends AbstractModule {
	@Provides
	JsonRpcHttpClientTransport transport(NioReactor reactor, IHttpClient httpClient, Config config) {
		String url = config.getChild("jsonrpc").get("client.url", "http://localhost:8080/");
		return JsonRpcHttpClientTransport.create(reactor, httpClient, url);
	}

	@Provides
	JsonRpcClient client(NioReactor reactor, JsonRpcHttpClientTransport transport, Config config) {
		// FR-021: the ApplicationSettings value is the default; a configured key overrides it per client,
		// exactly as jsonrpc.maxBodySize overrides JsonRpcLimits.MAX_BODY_SIZE on the server side
		return JsonRpcClient.builder(reactor, transport)
			.withCallTimeout(config.getChild("jsonrpc").get(ofDuration(), "callTimeout", JsonRpcClient.CALL_TIMEOUT))
			.build();
	}

	@ProvidesIntoSet
	Initializer<ServiceGraphModuleSettings> serviceGraphSettings() {
		// ADR-025: the adapter registers only when this module is present — i.e. where a client is bound
		return settings -> settings.with(JsonRpcClient.class, JsonRpcClientServiceAdapter.create());
	}
}
