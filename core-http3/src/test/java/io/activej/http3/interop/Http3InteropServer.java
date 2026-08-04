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

package io.activej.http3.interop;

import io.activej.bytebuf.ByteBuf;
import io.activej.eventloop.Eventloop;
import io.activej.http.AsyncServlet;
import io.activej.http.HttpMethod;
import io.activej.http.HttpResponse;
import io.activej.http.RoutingServlet;
import io.activej.http3.Http3Server;
import io.activej.http3.testutil.Http3TestTls;
import io.activej.quic.tls.TlsServerIdentity;

import java.nio.file.Path;

/**
 * An {@link Http3Server} a <b>foreign</b> HTTP/3 client can be pointed at — curl, Chrome, quic-go —
 * to run the interop checks the in-module suite cannot: every one of those tests is
 * ActiveJ&#8596;ActiveJ, so none of them can catch a place where both sides agree on something the RFC
 * does not say. See {@code README.md} beside this file for the commands.
 *
 * <p>Not a test: it has no {@code @Test} method and Surefire never runs it. It is a {@code main} kept
 * at test scope because it is a diagnostic, not a shipped API — and kept in the repository because
 * this harness is what found both conformance bugs of 2026-08-04 (an over-strict {@code ticket_nonce}
 * bound in {@code core-quic}, and QPACK error scoping in this module).
 *
 * <p>Routes: {@code GET /} returns a fixed body (SC-001), {@code POST /echo} returns the request body
 * verbatim (SC-002, exercised at 2 MiB to cross the QUIC flow-control windows), and {@code GET /page}
 * returns a small HTML document for a browser to render (SC-003).
 *
 * <p>System properties: {@code port} (default 4433), {@code cert} and {@code key} (PEM paths;
 * default the module's dev ECDSA fixture, whose SANs cover {@code localhost} and {@code example.test}).
 */
public final class Http3InteropServer {
	private Http3InteropServer() {}

	public static void main(String[] args) throws Exception {
		int port = Integer.parseInt(System.getProperty("port", "4433"));
		String cert = System.getProperty("cert");
		String key = System.getProperty("key");

		TlsServerIdentity identity = cert == null || key == null ?
			Http3TestTls.devIdentity() :
			TlsServerIdentity.fromPem(Path.of(cert), Path.of(key));

		Eventloop eventloop = Eventloop.builder().withCurrentThread().build();

		AsyncServlet servlet = RoutingServlet.builder(eventloop)
			.with(HttpMethod.GET, "/", request -> HttpResponse.ok200()
				.withPlainText("Hello from ActiveJ over HTTP/3\n")
				.toPromise())
			.with(HttpMethod.POST, "/echo", request -> request.loadBody()
				.map(body -> {
					// loadBody's buffer belongs to the request and is released with it, so the response
					// gets a copy rather than a slice that would outlive its owner.
					ByteBuf copy = ByteBuf.wrapForReading(body.getArray());
					return HttpResponse.ok200().withBody(copy).build();
				}))
			.with(HttpMethod.GET, "/page", request -> HttpResponse.ok200()
				.withHtml("<!doctype html><title>ActiveJ h3</title><h1>ActiveJ over HTTP/3</h1>")
				.toPromise())
			.build();

		Http3Server server = Http3Server.builder(eventloop, servlet)
			.withListenPort(port)
			.withServerIdentity(identity)
			.build();

		server.listen();
		// Parsed by the scripts that drive this; keep the shape.
		System.out.println("READY port=" + port);
		System.out.flush();
		eventloop.run();
	}
}
