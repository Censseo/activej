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
import io.activej.common.MemSize;
import io.activej.eventloop.Eventloop;
import io.activej.http.AsyncServlet;
import io.activej.http.HttpHeaders;
import io.activej.http.HttpMethod;
import io.activej.http.HttpResponse;
import io.activej.http.RoutingServlet;
import io.activej.http3.Http3Connection.EarlyDataRefusal;
import io.activej.http3.Http3Connection.GoAwayDirection;
import io.activej.http3.Http3Connection.QpackBlockedExit;
import io.activej.http3.Http3Connection.QpackTable;
import io.activej.http3.Http3Server;
import io.activej.http3.Http3Settings;
import io.activej.http3.testutil.Http3TestTls;
import io.activej.quic.tls.TlsServerIdentity;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.Locale;

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
 * verbatim (SC-002, exercised at 2 MiB to cross the QUIC flow-control windows), {@code GET /page}
 * returns a small HTML document for a browser to render (SC-003 of feature 005), and
 * {@code GET /qpack} returns a response carrying several repeated, indexable header fields — the
 * route feature 006's SC-003 wants, since a dynamic-table hit on the <i>response</i> side needs a
 * field section with something worth indexing in it.
 *
 * <h2>Profiles</h2>
 * The three capabilities of feature 006 ship off by default, so the phase-1 profile has to stay
 * reachable unchanged: {@link Profile#BASELINE} is byte-for-byte what this program served before,
 * and every other profile is one deliberate departure from it. Select one with {@code -Dprofile=…};
 * the individual properties below override whatever the profile set, so a one-off combination needs
 * no new profile.
 *
 * <p>System properties:
 * <ul>
 *     <li>{@code profile} — {@code baseline} (default), {@code qpack}, {@code zerortt} or
 *         {@code all}; see {@link Profile}</li>
 *     <li>{@code port} — default 4433</li>
 *     <li>{@code cert}, {@code key} — PEM paths; default the module's dev ECDSA fixture, whose SANs
 *         cover {@code localhost} and {@code example.test}</li>
 *     <li>{@code qpackCapacity} — the QPACK dynamic-table capacity advertised in bytes; 0 disables
 *         the table. Default 0 on {@code baseline}/{@code zerortt}, 4096 on {@code qpack}/{@code all}</li>
 *     <li>{@code qpackBlockedStreams} — {@code SETTINGS_QPACK_BLOCKED_STREAMS}; ignored while the
 *         capacity is 0, which advertises 0 for both. Default 16</li>
 *     <li>{@code zeroRtt} — accept 0-RTT early data. Default false on {@code baseline}/{@code qpack},
 *         true on {@code zerortt}/{@code all}</li>
 *     <li>{@code verbose} — print the per-event diagnostic lines below. Default true</li>
 * </ul>
 *
 * <h2>What it prints</h2>
 * A foreign client tells you very little about what the two QPACK tables and the ticket path
 * actually did, so this server says so itself, one line per event, through the {@link Http3Server.Inspector}
 * seam FR-062 exists for. The prefixes are stable and greppable:
 * <pre>
 * READY     the listen line the driving scripts parse — extended with the profile, never reshaped
 * REQ       a request head decoded, servlet about to run
 * RESP      a response fully written, with this server's running totals
 * QPACK     insertions, evictions, per-section encode hit rate, and blocked-stream transitions
 * SESSION   session tickets issued, and handshakes that resumed from one
 * ZERORTT   early data refused, and by which of the four defences
 * H3ERR     a stream reset, a connection error, a GOAWAY or a discarded frame
 * </pre>
 * {@code QPACK} lines are the ones that answer "did the dynamic table actually get used" without a
 * packet capture: {@code table=DECODER} insertions are the <b>peer's</b> encoder filling the table
 * this server decodes with, and {@code dynamicRefs} on a {@code QPACK encoded} line is how many field
 * lines of a response this server emitted as a dynamic reference rather than a literal.
 *
 * <p>Every callback runs on the reactor thread, inside the operation that produced the event — which
 * is exactly what {@link Http3Server.Inspector} warns about, and why this printing belongs in a
 * diagnostic {@code main} and nowhere near production code.
 */
public final class Http3InteropServer {
	private Http3InteropServer() {}

	/**
	 * The four configurations this harness serves. Each names the success criterion it exists for, so
	 * a run recorded in the interop table can be reproduced from the profile name alone.
	 */
	public enum Profile {
		/**
		 * Phase 1: QPACK static table only, no session tickets, no datagrams — feature 005's SC-001 to
		 * SC-003, and the profile every earlier interop result in {@code /docs/http/spec.md} was taken
		 * against. The SETTINGS frame it sends is byte-for-byte phase 1's (feature 006 SC-011).
		 */
		BASELINE(0, false),
		/**
		 * A 4 KB QPACK dynamic table with the default blocked-stream permission: feature 006's SC-003,
		 * where a foreign client's second request on one connection should reference entries its first
		 * inserted, and this server's own responses should come back with a non-zero {@code dynamicRefs}.
		 */
		QPACK(4096, false),
		/**
		 * 0-RTT session resumption accepted, with the sealing keys {@link Http3Server#listen()} generates
		 * from a fresh {@code SecureRandom}: feature 006's SC-006, where a foreign client reconnecting
		 * with a ticket this server issued should send its request in a 0-RTT packet.
		 * <p>
		 * The early-data policy stays the default — RFC 9110 §9.2.1 safe methods only — so a {@code POST}
		 * in early data is answered {@code 425 (Too Early)} without the servlet running, which is the
		 * behaviour a foreign client should be observed against rather than one relaxed for the test.
		 */
		ZERO_RTT(0, true),
		/**
		 * Both at once, for the case a browser exercises in one page load. Nothing here couples them —
		 * they are independent slices — but a peer that drives both at once is the one configuration
		 * neither slice's own interop case covers.
		 */
		ALL(4096, true);

		private final int qpackCapacity;
		private final boolean zeroRtt;

		Profile(int qpackCapacity, boolean zeroRtt) {
			this.qpackCapacity = qpackCapacity;
			this.zeroRtt = zeroRtt;
		}

		/** What {@code -Dprofile=} takes and the {@code READY} line reports: the name, unpunctuated. */
		String option() {
			return name().toLowerCase(Locale.ROOT).replace("_", "");
		}

		static Profile parse(String raw) {
			String normalized = raw.trim().toLowerCase(Locale.ROOT).replace("-", "").replace("_", "");
			for (Profile profile : values()) {
				if (profile.option().equals(normalized)) return profile;
			}
			throw new IllegalArgumentException("Unknown -Dprofile=" + raw +
				" — one of baseline, qpack, zerortt, all");
		}
	}

	public static void main(String[] args) throws Exception {
		Profile profile = Profile.parse(System.getProperty("profile", "baseline"));

		int port = Integer.parseInt(System.getProperty("port", "4433"));
		String cert = System.getProperty("cert");
		String key = System.getProperty("key");
		int qpackCapacity = Integer.parseInt(
			System.getProperty("qpackCapacity", String.valueOf(profile.qpackCapacity)));
		int qpackBlockedStreams = Integer.parseInt(System.getProperty("qpackBlockedStreams", "16"));
		boolean zeroRtt = Boolean.parseBoolean(
			System.getProperty("zeroRtt", String.valueOf(profile.zeroRtt)));
		boolean verbose = Boolean.parseBoolean(System.getProperty("verbose", "true"));

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
			// The fields are identical on every response on purpose: an encoder with a dynamic table
			// should insert them once and reference them from the second response on, so the second
			// request's `QPACK encoded` line is where dynamicRefs becomes non-zero.
			.with(HttpMethod.GET, "/qpack", request -> HttpResponse.ok200()
				.withHeader(HttpHeaders.of("x-activej-interop"), "qpack-dynamic-table-probe")
				.withHeader(HttpHeaders.CACHE_CONTROL, "no-store, max-age=0")
				.withHeader(HttpHeaders.VARY, "accept-encoding, accept-language")
				.withPlainText("Hello from ActiveJ over HTTP/3, with something worth indexing\n")
				.toPromise())
			.build();

		// Only the departures from the defaults are set, so BASELINE builds the value phase 1 built.
		Http3Settings.Builder settings = Http3Settings.builder();
		if (qpackCapacity > 0) {
			settings.withQpackMaxTableCapacity(MemSize.bytes(qpackCapacity))
				.withQpackBlockedStreams(qpackBlockedStreams);
		}
		if (zeroRtt) settings.withZeroRttEnabled(true);

		// The ticket-sealing keys are deliberately left to listen(), which generates a real set from a
		// fresh SecureRandom on the QuicConnection.sessionTicketKeyRotation/-Lifetime grid. A set pinned
		// here would only matter for sharing one across several servers in a process.
		Http3Server.Builder builder = Http3Server.builder(eventloop, servlet)
			.withListenPort(port)
			.withServerIdentity(identity)
			.withSettings(settings.build());
		if (verbose) builder.withInspector(new PrintingInspector());
		Http3Server server = builder.build();

		server.listen();
		// Parsed by the scripts that drive this; keep the shape. Everything after `port=` is additive.
		System.out.println("READY port=" + port +
			" profile=" + profile.option() +
			" qpackCapacity=" + qpackCapacity +
			" qpackBlockedStreams=" + (qpackCapacity > 0 ? qpackBlockedStreams : 0) +
			" zeroRtt=" + zeroRtt);
		System.out.flush();
		eventloop.run();
	}

	/**
	 * Prints one line per event, so a human driving curl or Chrome from another terminal can see what
	 * the two QPACK tables and the ticket path did without a packet capture or a qlog.
	 * <p>
	 * It accumulates nothing and decides nothing — {@link Http3Server.Inspector}'s contract is that a
	 * callback runs on the reactor thread inside the operation that produced the event, so an
	 * implementation that blocks blocks the reactor. {@code System.out} is a compromise this program can
	 * afford and a server cannot.
	 */
	private static final class PrintingInspector implements Http3Server.Inspector {
		@Override
		public <T extends Http3Server.Inspector> @Nullable T lookup(Class<T> type) {
			return type.isInstance(this) ? type.cast(this) : null;
		}

		@Override
		public void onRequestStarted(Http3Server server, long streamId, HttpMethod method) {
			System.out.println("REQ  stream=" + streamId + " method=" + method);
		}

		@Override
		public void onRequestCompleted(
			Http3Server server, long streamId, int statusCode, long requestBodyBytes, long responseBodyBytes
		) {
			System.out.println("RESP stream=" + streamId + " status=" + statusCode +
				" reqBody=" + requestBodyBytes + " respBody=" + responseBodyBytes +
				" served=" + server.requestsServed());
		}

		@Override
		public void onStreamReset(Http3Server server, long streamId, long errorCode) {
			System.out.println("H3ERR reset stream=" + streamId + " code=" + hex(errorCode));
		}

		@Override
		public void onConnectionError(Http3Server server, long errorCode) {
			System.out.println("H3ERR connection code=" + hex(errorCode));
		}

		@Override
		public void onFrameDiscarded(Http3Server server, long frameType, long declaredLength) {
			System.out.println("H3ERR discarded type=" + hex(frameType) + " length=" + declaredLength);
		}

		@Override
		public void onGoAway(Http3Server server, GoAwayDirection direction, long id) {
			System.out.println("H3ERR goaway direction=" + direction + " id=" + id);
		}

		@Override
		public void onQpackInsertions(Http3Server server, QpackTable table, int insertions, int tableBytes) {
			// table=DECODER is the peer's encoder inserting into the table this server decodes with —
			// the proof that a foreign client is using the dynamic table at all.
			System.out.println("QPACK insert table=" + table + " n=" + insertions + " tableBytes=" + tableBytes);
		}

		@Override
		public void onQpackEvictions(Http3Server server, QpackTable table, int evictions, int tableBytes) {
			System.out.println("QPACK evict table=" + table + " n=" + evictions + " tableBytes=" + tableBytes);
		}

		@Override
		public void onQpackFieldSectionEncoded(
			Http3Server server, long streamId, int fieldLines, int dynamicReferences
		) {
			System.out.println("QPACK encoded stream=" + streamId +
				" fields=" + fieldLines + " dynamicRefs=" + dynamicReferences);
		}

		@Override
		public void onQpackStreamBlocked(Http3Server server, long streamId, int blockedStreams, long heldBytes) {
			System.out.println("QPACK blocked stream=" + streamId +
				" blockedStreams=" + blockedStreams + " heldBytes=" + heldBytes);
		}

		@Override
		public void onQpackStreamUnblocked(
			Http3Server server, long streamId, QpackBlockedExit exit, long blockedMillis, int blockedStreams
		) {
			System.out.println("QPACK unblocked stream=" + streamId + " exit=" + exit +
				" blockedMillis=" + blockedMillis + " blockedStreams=" + blockedStreams);
		}

		@Override
		public void onQpackBlockedSectionRefused(
			Http3Server server, long streamId, int blockedStreams, long heldBytes
		) {
			System.out.println("QPACK refused stream=" + streamId +
				" blockedStreams=" + blockedStreams + " heldBytes=" + heldBytes);
		}

		@Override
		public void onSessionTicketsIssued(Http3Server server, int tickets, long ticketsIssued) {
			System.out.println("SESSION tickets=" + tickets + " issued=" + ticketsIssued);
		}

		@Override
		public void onSessionResumed(
			Http3Server server, boolean earlyDataAccepted, long sessionsResumed, long zeroRttAccepted
		) {
			System.out.println("SESSION resumed earlyDataAccepted=" + earlyDataAccepted +
				" resumed=" + sessionsResumed + " zeroRttAccepted=" + zeroRttAccepted);
		}

		@Override
		public void onEarlyDataRefused(Http3Server server, EarlyDataRefusal reason, long refusals) {
			System.out.println("ZERORTT refused reason=" + reason + " total=" + refusals);
		}

		private static String hex(long code) {
			return "0x" + Long.toHexString(code);
		}
	}
}
