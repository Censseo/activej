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
 */
public final class JsonRpcClientModule extends AbstractModule {
	@Provides
	JsonRpcHttpClientTransport transport(NioReactor reactor, IHttpClient httpClient, Config config) {
		String url = config.getChild("jsonrpc").get("client.url", "http://localhost:8080/");
		return JsonRpcHttpClientTransport.create(reactor, httpClient, url);
	}

	@Provides
	JsonRpcClient client(NioReactor reactor, JsonRpcHttpClientTransport transport) {
		return JsonRpcClient.builder(reactor, transport).build();
	}

	@ProvidesIntoSet
	Initializer<ServiceGraphModuleSettings> serviceGraphSettings() {
		// ADR-025: the adapter registers only when this module is present — i.e. where a client is bound
		return settings -> settings.with(JsonRpcClient.class, JsonRpcClientServiceAdapter.create());
	}
}
