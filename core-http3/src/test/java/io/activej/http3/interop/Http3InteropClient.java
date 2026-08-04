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

import io.activej.dns.IDnsClient;
import io.activej.dns.protocol.DnsQuery;
import io.activej.dns.protocol.DnsResourceRecord;
import io.activej.dns.protocol.DnsResponse;
import io.activej.dns.protocol.DnsTransaction;
import io.activej.eventloop.Eventloop;
import io.activej.http.HttpRequest;
import io.activej.http.HttpResponse;
import io.activej.http3.Http3Client;
import io.activej.promise.Promise;
import io.activej.quic.tls.QuicTls;
import io.activej.quic.tls.TlsClientConfig;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;

/**
 * Drives {@link Http3Client} against a <b>foreign</b> HTTP/3 server — Cloudflare, Caddy (quic-go),
 * nginx — for a GET and a POST (SC-004). The counterpart of {@link Http3InteropServer}; see
 * {@code README.md} beside this file for the commands.
 *
 * <p>Not a test, for the reasons given on {@link Http3InteropServer}. This is the program that found
 * the {@code ticket_nonce} defect: against quic-go the QUIC handshake, ALPN and HTTP/3 SETTINGS all
 * completed and the request then died, which no ActiveJ&#8596;ActiveJ test could have reproduced.
 *
 * <p>System properties: {@code target} (scheme://host:port, default {@code https://localhost:4434}),
 * {@code resolveTo} (the address to use for the target host, default {@code 127.0.0.1} — DNS is
 * stubbed so the tool works against a container or a hosts-file-free environment), {@code getPath}
 * and {@code postPath} (default {@code /get} and {@code /post}), and {@code insecure} (default
 * {@code true}: trust any certificate, since a local reference server usually has a private CA).
 *
 * <p>Exits non-zero if either exchange fails, so it can gate a script.
 */
public final class Http3InteropClient {
	private Http3InteropClient() {}

	public static void main(String[] args) throws Exception {
		String target = System.getProperty("target", "https://localhost:4434");
		String resolveTo = System.getProperty("resolveTo", "127.0.0.1");
		String getPath = System.getProperty("getPath", "/get");
		String postPath = System.getProperty("postPath", "/post");
		boolean insecure = Boolean.parseBoolean(System.getProperty("insecure", "true"));

		Eventloop eventloop = Eventloop.builder().withCurrentThread().build();
		InetAddress fixed = InetAddress.getByName(resolveTo);

		// Stubbed rather than a real resolver: what is under test is HTTP/3 against a foreign peer, and
		// a hostname that resolves differently inside a container would only obscure that.
		IDnsClient dns = new IDnsClient() {
			@Override
			public Promise<DnsResponse> resolve(DnsQuery query) {
				return Promise.of(DnsResponse.of(
					DnsTransaction.of((short) 0, query),
					DnsResourceRecord.of(new InetAddress[]{fixed}, 60)));
			}

			@Override
			public void close() {}
		};

		Http3Client client = Http3Client.builder(eventloop, dns)
			.withTlsEngineFactory(host -> params -> QuicTls.clientEngine(
				insecure ?
					TlsClientConfig.builder(host, params).insecureTrustAll().build() :
					TlsClientConfig.builder(host, params).build()))
			.build();

		byte[] payload = "activej-http3-interop-payload".getBytes(StandardCharsets.UTF_8);

		Promise<Void> run = client.request(HttpRequest.get(target + getPath).build())
			.then(response -> report("GET", response))
			.then($ -> client.request(HttpRequest.post(target + postPath).withBody(payload).build())
				.then(response -> report("POST", response)))
			.whenComplete(($, e) -> {
				if (e != null) System.out.println("INTEROP_FAILED: " + e);
				client.close();
			});

		eventloop.run();
		if (run.isException()) System.exit(1);
	}

	private static Promise<Void> report(String label, HttpResponse response) {
		int code = response.getCode();
		String version = String.valueOf(response.getVersion());
		return response.loadBody()
			.map(body -> {
				String text = body.getString(StandardCharsets.UTF_8);
				System.out.println("=== " + label + " ===");
				System.out.println("status=" + code + " version=" + version + " bodyBytes=" + text.length());
				System.out.println(text.length() > 600 ? text.substring(0, 600) + "..." : text);
				return null;
			});
	}
}
