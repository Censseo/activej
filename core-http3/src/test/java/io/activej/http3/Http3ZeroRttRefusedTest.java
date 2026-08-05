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

package io.activej.http3;

import io.activej.bytebuf.ByteBuf;
import io.activej.http.HttpRequest;
import io.activej.http.HttpResponse;
import io.activej.http3.testutil.Http3ClientFixture;
import io.activej.http3.testutil.Http3TestServer;
import io.activej.http3.testutil.ManualEventloop;
import io.activej.promise.Promise;
import io.activej.quic.tls.InMemoryQuicSessionCache;
import io.activej.quic.tls.QuicSessionCache;
import io.activej.test.rules.ByteBufRule;
import org.jetbrains.annotations.Nullable;
import org.junit.After;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static io.activej.http3.testutil.Http3ClientFixture.HOST;
import static io.activej.http3.testutil.Http3ClientFixture.OTHER_HOST;
import static io.activej.http3.testutil.Http3ClientFixture.url;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Collections.frequency;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * T091 and T092 at the HTTP/3 layer — a <b>refused</b> 0-RTT exchange, seen from the two ends a
 * consumer actually has: the caller's promise and the handler.
 *
 * <h2>How the refusal is staged</h2>
 * {@link Http3TestServer} with {@code withEarlyDataEnabled(false)}: it seals and opens session tickets,
 * so the pre-shared key resumes, and omits {@code early_data} from EncryptedExtensions, which is
 * RFC 8446 §4.2.10's rejection signal and the shape of most deployed servers — they resume sessions
 * and decline 0-RTT. {@code Http3Server} cannot be configured that way, which is why that fixture
 * exists; everything else here is production code, a whole unmodified {@link Http3Client} included.
 *
 * <h2>What is asserted, and against which requirement</h2>
 * <ul>
 *   <li><b>FR-048 (T092)</b> — the session resumes <i>although</i> early data is refused, and the
 *       request still completes with the right response. The two outcomes are read side by side, from
 *       the server ({@code sessionsResumed} against {@code zeroRttAccepted}) and from the client
 *       ({@code zeroRttAttempted} against {@code zeroRttRejected}), so neither can be mistaken for the
 *       other.</li>
 *   <li><b>FR-055 / {@code contracts/zero-rtt.md} point 5 (T091)</b> — every buffer the refused
 *       exchange held is recycled exactly once, on the ordinary path, over repeated refusals, and on a
 *       teardown that lands in the middle of one. {@link ByteBufRule}, with no {@code @IgnoreLeaks}
 *       anywhere in this class (SC-013).</li>
 * </ul>
 *
 * <h2>What is deliberately not asserted</h2>
 * <i>How</i> the request reaches the server after the refusal. Today it rides QUIC loss recovery back
 * out at 1-RTT on the stream it was already on; FR-067 will have {@code Http3Client} discard that
 * stream and re-create the request on a fresh one, which is {@code Http3ZeroRttRejectionTest}'s
 * subject. Both satisfy every assertion here, and that is the point: T093–T097 must be free to change
 * the mechanism and must not be free to change the outcome.
 *
 * @see <a href="https://www.rfc-editor.org/rfc/rfc8446#section-4.2.10">RFC 8446 §4.2.10 — Early Data Indication</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9001#section-4.6.1">RFC 9001 §4.6.1 — 0-RTT</a>
 */
public final class Http3ZeroRttRefusedTest {
	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	/**
	 * One pooled connection, so the exchange after a detour to {@link #OTHER_HOST} evicts the entry for
	 * {@link #HOST} and has to dial it again — which is the redial that offers the stored ticket.
	 */
	private static final Http3Settings ZERO_RTT_ON = Http3Settings.builder()
		.withZeroRttEnabled(true)
		.withMaxConnections(1)
		.build();

	/** Long enough that its QPACK-compressed field section is a buffer worth losing track of. */
	private static final String LONG_PATH = "/" + "abcdefghij".repeat(48);

	private ManualEventloop loop;
	private QuicSessionCache cache;
	private @Nullable Http3ClientFixture fixture;
	private Http3TestServer server;

	private final List<String> served = new ArrayList<>();

	@Before
	public void setUp() {
		loop = new ManualEventloop();
		cache = InMemoryQuicSessionCache.create(8, loop::currentTimeMillis);
		served.clear();
	}

	@After
	public void tearDown() {
		if (fixture != null) fixture.close();
		fixture = null;
		loop.tickUntilQuiet();
		loop.close();
	}

	// ---------------------------------------------------------------- FR-048 and the fallback

	@Test
	public void aRefusedEarlyDataRequestResumesTheSessionAndStillSucceedsExactlyOnce() {
		start();

		exchange(HOST, "/first");
		assertTrue("the server issued a ticket and the client stored it", client().sessionTicketsStored() >= 1);
		assertEquals("nothing to resume on a first connection", 0, client().zeroRttAttempted());

		exchange(OTHER_HOST, "/second");
		assertEquals(1, client().connectionsEvicted());

		exchange(HOST, LONG_PATH);

		assertEquals("the stored ticket was offered on the redial", 1, client().sessionTicketsOffered());
		assertEquals("early data went out with it", 1, client().zeroRttAttempted());
		assertEquals("the server was configured to refuse, and did not", 0, client().zeroRttAccepted());
		assertEquals(1, client().zeroRttRejected());

		// FR-048 from the deciding side: the pre-shared key was accepted, early_data was not echoed, and
		// the handshake completed all the same — resumption and early data are two answers, not one.
		assertEquals("the redial did not resume, so nothing about early data was ever at stake",
			1, server.sessionsResumed());
		assertEquals(0, server.zeroRttAccepted());

		// ... and the caller is none the wiser: the right response, once, from one handler invocation.
		assertEquals(1, frequency(served, LONG_PATH));
		assertEquals(List.of("/first", "/second", LONG_PATH), served);
	}

	/**
	 * The refusal repeated over several redials of the same ticket, each carrying a field section large
	 * enough to be its own allocation. A discard that released one buffer per rejection rather than all
	 * of them surfaces here as a leak, instead of as an intermittent one somewhere else.
	 */
	@Test
	public void repeatedRefusalsLeaveNoBufferBehind() {
		start();

		exchange(HOST, "/first");
		assertTrue(client().sessionTicketsStored() >= 1);

		for (int i = 0; i < 3; i++) {
			exchange(OTHER_HOST, "/other/" + i);
			exchange(HOST, LONG_PATH + "/" + i);
		}

		assertTrue("no redial ever offered the ticket, so nothing was refused", client().zeroRttAttempted() >= 1);
		assertEquals(0, client().zeroRttAccepted());
		assertEquals(client().zeroRttAttempted(), client().zeroRttRejected());
		assertEquals(0, server.zeroRttAccepted());
	}

	/**
	 * A teardown landing in the middle of the refusal: the request has left in a 0-RTT packet, the
	 * refusal has just been read, and the client is closed before anything resolves. Whether the
	 * caller's promise then succeeds or fails is not what is under test — that every buffer the
	 * abandoned exchange held is released exactly once, is.
	 */
	@Test
	public void aTeardownDuringTheRefusalLeavesNoBufferBehind() {
		start();

		exchange(HOST, "/first");
		assertTrue(client().sessionTicketsStored() >= 1);
		exchange(OTHER_HOST, "/second");

		Promise<HttpResponse> refused = client().request(HttpRequest.get(url(HOST, LONG_PATH)).build());
		assertEquals("the redial did not offer the ticket, so this aborts an ordinary dial",
			1, client().zeroRttAttempted());

		// Delivered one queued round at a time rather than driven to a standstill, so the teardown lands
		// where it is meant to: the moment the refusal is known, and before anything has resolved.
		for (int round = 0; round < 8 && client().zeroRttRejected() == 0; round++) {
			fixture.wire().network().deliverDue();
			loop.tickUntilQuiet();
		}
		assertEquals("the client never learned that its early data was refused", 1, client().zeroRttRejected());
		assertFalse("the exchange finished before the teardown, so nothing was abandoned", refused.isComplete());

		client().close();
		fixture.close();
		fixture = null;
		loop.tickUntilQuiet();
		assertTrue("an abandoned exchange must not be left pending forever", refused.isComplete());
	}

	// ---------------------------------------------------------------- harness

	/** A server that resumes sessions and refuses early data, against a client with 0-RTT on. */
	private void start() {
		fixture = new Http3ClientFixture(loop)
			.withServerFactory(socket -> server = new Http3TestServer(socket)
				.withEarlyDataEnabled(false)
				.withHandler((request, context) -> {
					String path = request.getPath();
					served.add(path);
					return HttpResponse.ok200().withBody(path.getBytes(UTF_8)).toPromise();
				})
				.start())
			.withClientSettings(ZERO_RTT_ON)
			.withSessionCache(cache)
			.start();
	}

	private void exchange(String host, String path) {
		HttpResponse response = fixture.await(client().request(HttpRequest.get(url(host, path)).build()));
		ByteBuf body = fixture.await(response.loadBody());
		try {
			assertEquals(path, body.getString(UTF_8));
		} finally {
			body.recycle();
		}
	}

	private Http3Client client() {
		return fixture.client();
	}
}
