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

import io.activej.common.MemSize;
import io.activej.dns.IDnsClient;
import io.activej.dns.protocol.DnsQuery;
import io.activej.dns.protocol.DnsResourceRecord;
import io.activej.dns.protocol.DnsResponse;
import io.activej.dns.protocol.DnsTransaction;
import io.activej.eventloop.Eventloop;
import io.activej.http.HttpRequest;
import io.activej.http.HttpResponse;
import io.activej.http3.Http3Client;
import io.activej.http3.Http3Settings;
import io.activej.promise.Promise;
import io.activej.quic.tls.InMemoryQuicSessionCache;
import io.activej.quic.tls.QuicSessionCache;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;

/**
 * Drives {@link Http3Client} against a <b>foreign</b> HTTP/3 server — Cloudflare, Caddy (quic-go),
 * nginx — for a GET and a POST (feature 005 SC-004), and, with {@code -DzeroRtt=true -Drounds=2},
 * for a <b>reconnect that resumes the session</b> the first round established (feature 006 SC-006 /
 * task T143). The counterpart of {@link Http3InteropServer}; see {@code README.md} beside this file
 * for the commands.
 *
 * <p>Not a test, for the reasons given on {@link Http3InteropServer}. This is the program that found
 * the {@code ticket_nonce} defect: against quic-go the QUIC handshake, ALPN and HTTP/3 SETTINGS all
 * completed and the request then died, which no ActiveJ&#8596;ActiveJ test could have reproduced.
 *
 * <h2>Rounds, and why each one is a fresh client</h2>
 * A second request through one {@link Http3Client} reuses the pooled connection, which is the right
 * behaviour and the wrong experiment: resumption is a property of a <i>new</i> QUIC handshake. Each
 * round therefore builds its own client, closes it, and hands the next one the same
 * {@link QuicSessionCache} — the FR-059 seam that exists precisely so a ticket can outlive the client
 * that stored it. Round 2 offers the ticket round 1 was given; the counters below say whether the
 * peer took it.
 *
 * <p>The insecure path goes through {@code withTlsClientConfig}, <b>not</b> through
 * {@code withTlsEngineFactory}: a whole engine factory owns its own {@code TlsClientConfig} and
 * therefore opts out of this client's resumption plumbing, so no ticket would ever be offered or
 * stored and every round would be a full handshake with nothing to say about it.
 *
 * <p>System properties: {@code target} (scheme://host:port, default {@code https://localhost:4434}),
 * {@code resolveTo} (the address to use for the target host, default {@code 127.0.0.1} — DNS is
 * stubbed so the tool works against a container or a hosts-file-free environment), {@code getPath}
 * and {@code postPath} (default {@code /get} and {@code /post}), {@code insecure} (default
 * {@code true}: trust any certificate, since a local reference server usually has a private CA),
 * {@code zeroRtt} (default {@code false}: offer session tickets and 0-RTT early data), {@code rounds}
 * (default 1: how many times to run the pair, each on a fresh client over one shared ticket store),
 * and {@code qpackCapacity} (default 0: the QPACK dynamic-table capacity this client advertises).
 *
 * <p>Exits non-zero if any exchange fails, so it can gate a script.
 */
public final class Http3InteropClient {
	private Http3InteropClient() {}

	public static void main(String[] args) throws Exception {
		String target = System.getProperty("target", "https://localhost:4434");
		String resolveTo = System.getProperty("resolveTo", "127.0.0.1");
		String getPath = System.getProperty("getPath", "/get");
		String postPath = System.getProperty("postPath", "/post");
		boolean insecure = Boolean.parseBoolean(System.getProperty("insecure", "true"));
		boolean zeroRtt = Boolean.parseBoolean(System.getProperty("zeroRtt", "false"));
		int rounds = Integer.parseInt(System.getProperty("rounds", "1"));
		int qpackCapacity = Integer.parseInt(System.getProperty("qpackCapacity", "0"));

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

		// Only the departures from the defaults are set, so with neither flag this is phase 1's client.
		Http3Settings.Builder settingsBuilder = Http3Settings.builder();
		if (zeroRtt) settingsBuilder.withZeroRttEnabled(true);
		if (qpackCapacity > 0) settingsBuilder.withQpackMaxTableCapacity(MemSize.bytes(qpackCapacity));
		Http3Settings settings = settingsBuilder.build();

		// Shared across rounds so a ticket outlives the client that stored it; `take` is single-use, so
		// a round that resumes also consumes what it offered.
		QuicSessionCache sessionCache = InMemoryQuicSessionCache.create(16, eventloop::currentTimeMillis);

		byte[] payload = "activej-http3-interop-payload".getBytes(StandardCharsets.UTF_8);
		boolean[] failed = {false};

		Promise<Void> run = round(1, rounds, eventloop, dns, settings, sessionCache, insecure,
			target, getPath, postPath, payload, failed);

		eventloop.run();
		if (failed[0] || run.isException()) System.exit(1);
	}

	/**
	 * One round: a fresh client, a GET, a POST, the resumption counters, and then the next round —
	 * chained rather than looped because the next handshake must not start until this client's ticket
	 * is in the store.
	 */
	private static Promise<Void> round(
		int round, int rounds, Eventloop eventloop, IDnsClient dns, Http3Settings settings,
		QuicSessionCache sessionCache, boolean insecure, String target, String getPath, String postPath,
		byte[] payload, boolean[] failed
	) {
		Http3Client client = Http3Client.builder(eventloop, dns)
			.withSettings(settings)
			.withSessionCache(sessionCache)
			// Narrower than withTlsEngineFactory on purpose: this client keeps building the config, so the
			// ticket to offer, the store to fill and the early-data switch stay wired. A whole factory here
			// would silently disable resumption — see this class's Javadoc.
			.withTlsClientConfig(config -> {
				if (insecure) config.insecureTrustAll();
			})
			.build();

		return client.request(HttpRequest.get(target + getPath).build())
			.then(response -> report("GET", round, response))
			.then($ -> client.request(HttpRequest.post(target + postPath).withBody(payload).build())
				.then(response -> report("POST", round, response)))
			.whenComplete(($, e) -> {
				if (e != null) {
					failed[0] = true;
					System.out.println("INTEROP_FAILED round=" + round + ": " + e);
				}
				reportSession(round, client);
				client.close();
			})
			// A failed round still reports, and still stops: a second round after a failure would only
			// produce a second copy of the same diagnosis.
			.then(($, e) -> e != null || round >= rounds ?
					Promise.complete() :
					round(round + 1, rounds, eventloop, dns, settings, sessionCache, insecure,
						target, getPath, postPath, payload, failed));
	}

	private static Promise<Void> report(String label, int round, HttpResponse response) {
		int code = response.getCode();
		String version = String.valueOf(response.getVersion());
		return response.loadBody()
			.map(body -> {
				String text = body.getString(StandardCharsets.UTF_8);
				System.out.println("=== " + label + " round=" + round + " ===");
				System.out.println("status=" + code + " version=" + version + " bodyBytes=" + text.length());
				System.out.println(text.length() > 600 ? text.substring(0, 600) + "..." : text);
				return null;
			});
	}

	/**
	 * The FR-062 counters that say what the handshake did, printed per round because they are what
	 * distinguishes "reconnected" from "resumed" and "resumed" from "resumed with early data".
	 * {@code offered} greater than 0 on round 2 means the peer issued a usable ticket on round 1;
	 * {@code accepted} means it took the early data as well.
	 */
	private static void reportSession(int round, Http3Client client) {
		System.out.println("SESSION round=" + round +
			" ticketsStored=" + client.sessionTicketsStored() +
			" ticketsOffered=" + client.sessionTicketsOffered() +
			" zeroRttAttempted=" + client.zeroRttAttempted() +
			" zeroRttAccepted=" + client.zeroRttAccepted() +
			" zeroRttRejected=" + client.zeroRttRejected() +
			" earlyDataRetried=" + client.earlyDataRetried());
	}
}
