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

package io.activej.jsonrpc.transport.http.interop;

import io.activej.eventloop.Eventloop;
import io.activej.jsonrpc.service.JsonRpcDispatcher;
import io.activej.jsonrpc.transport.http.JsonRpcServlet;
import io.activej.jsonrpc.transport.http.fixtures.JsonRpcHttpTestServer;
import io.activej.jsonrpc.transport.http.fixtures.TestApi;
import io.activej.jsonrpc.transport.http.fixtures.TestApiImpl;

/**
 * The capture vehicle for the frozen interoperability vectors (T048, FR-060, FR-064): serves the
 * <b>real</b> {@link JsonRpcServlet} over the module's {@link TestApi} service on a real
 * {@code HttpServer} bound to <b>port {@code 0}</b> and prints the address the kernel actually
 * assigned — the {@code :0}-not-{@code getFreePort()} rule of FR-050a/ADR-028, applied to the
 * capture procedure too.
 * <p>
 * This is a {@code main}, <b>not</b> a test: Surefire's {@code *Test.java} include never runs it.
 * It exists so a maintainer can regenerate {@code http-vectors.json} from a real external client
 * (curl, a browser's {@code fetch()}, anything that speaks HTTP/1.1) without writing any Java —
 * see {@code README.md} in this package for the verbatim commands (FR-064).
 * <p>
 * The server serves until the process is killed; the printed line is the only output:
 * <pre>{@code CAPTURE_SERVER <host>:<port>}</pre>
 */
public final class CaptureServer {
	private CaptureServer() {}

	public static void main(String[] args) throws Exception {
		Eventloop eventloop = Eventloop.builder()
			.withCurrentThread()
			.build();
		JsonRpcDispatcher dispatcher = JsonRpcDispatcher.builder(eventloop)
			.withService(TestApi.class, new TestApiImpl())
			.build();
		JsonRpcServlet servlet = JsonRpcServlet.create(eventloop, dispatcher);
		JsonRpcHttpTestServer server = JsonRpcHttpTestServer.builder(eventloop)
			.withServlet(servlet)
			.build();
		server.listen();
		System.out.println("CAPTURE_SERVER " + server.address().getHostString() + ":" + server.port());
		System.out.flush();
		eventloop.run();
	}
}
