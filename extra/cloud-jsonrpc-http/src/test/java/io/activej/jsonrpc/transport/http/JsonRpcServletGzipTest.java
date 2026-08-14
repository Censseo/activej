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

package io.activej.jsonrpc.transport.http;

import io.activej.eventloop.Eventloop;
import io.activej.jsonrpc.service.JsonRpcDispatcher;
import io.activej.jsonrpc.transport.http.fixtures.JsonRpcHttpTestServer;
import io.activej.jsonrpc.transport.http.fixtures.TestApi;
import io.activej.jsonrpc.transport.http.fixtures.TestApiImpl;
import io.activej.reactor.Reactor;
import io.activej.test.rules.ActivePromisesRule;
import io.activej.test.rules.ByteBufRule;
import io.activej.test.rules.EventloopRule;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Random;
import java.util.zip.GZIPOutputStream;

import static io.activej.jsonrpc.transport.http.fixtures.JsonRpcHttpRawExchange.exchange;
import static io.activej.jsonrpc.transport.http.fixtures.JsonRpcHttpRawExchange.exchangeHead;
import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * FR-020b (T029): {@code HttpMessage.loadBody(int)} decodes {@code gzip} <b>before</b> accumulating,
 * so the servlet's bound applies to the <b>decompressed</b> stream — a compressed body cannot
 * expand past it. This feature adds no compression code; it asserts core-http's behaviour through
 * the real servlet with its default bound, and the two-tier composition with the T030 check:
 * <ul>
 *     <li>case 1 — a gzip body whose <b>decompressed</b> size exceeds the bound (compressed length
 *     well under it, so the up-front {@code 413} cannot fire first) is refused mid-stream, after
 *     decompression, by the <b>connection tier's</b> hardcoded {@code 400} + close — the R2 path
 *     ({@code decodeGzip} runs before the bound check, {@code HttpMessage.java:329-331});</li>
 *     <li>case 2 — a gzip body whose <b>compressed</b> length itself exceeds the bound is refused
 *     up front by the servlet's {@code 413}: the row-3 check sees the declared, i.e. compressed,
 *     {@code Content-Length}.</li>
 * </ul>
 * Both cases are green-by-construction (plan decision D8) — they pin core-http's own behaviour —
 * except that case 2's {@code 413} branch is dormant until T030. The gzip streams are complete
 * files ({@code GZIPOutputStream} close writes the trailer), so a refusal is for size, never for a
 * truncated stream.
 */
public final class JsonRpcServletGzipTest {
	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	@ClassRule
	public static final EventloopRule eventloopRule = new EventloopRule();

	@ClassRule
	public static final ActivePromisesRule activePromisesRule = new ActivePromisesRule();

	/** The connection tier's hardcoded mid-stream refusal (probe R2's exact wire form). */
	private static final String CONNECTION_TIER_400 =
		"HTTP/1.1 400 Bad Request\r\nConnection: close\r\nContent-Length: 0\r\n\r\n";

	private Eventloop eventloop;

	@Before
	public void setUp() {
		eventloop = Reactor.getCurrentReactor();
	}

	/**
	 * A gzip of 2,000,000 identical bytes compresses to ~2 KB — three orders of magnitude under the
	 * 1 MB default bound — so the declared (compressed) length passes the row-3 check and the
	 * refusal must happen mid-stream after decompression: {@code loadBody} decodes gzip before
	 * accumulating, the decoded chunks cross 1 MB, the {@code MalformedHttpException} reaches
	 * {@code onMalformedHttpException} first, and the connection answers its hardcoded
	 * {@code 400} + close. {@code !contains("200")} and {@code !contains("-32700")}: a regression in
	 * which gzip arrived undecoded would answer {@code 200} carrying {@code -32700} and must fail
	 * this test.
	 */
	@Test
	public void aGzipBodyDecodingPastTheBoundIsRefusedByTheConnectionTier() throws Exception {
		byte[] compressed = gzip("x".repeat(2_000_000).getBytes(US_ASCII));
		assertTrue("the compressed body must stay under the default bound or the up-front 413 answers instead",
			compressed.length < 1_048_576);

		String head = "POST / HTTP/1.1\r\n" +
			"Host: localhost\r\n" +
			"Content-Type: application/json\r\n" +
			"Content-Encoding: gzip\r\n" +
			"Content-Length: " + compressed.length + "\r\n" +
			"Connection: close\r\n" +
			"\r\n";
		byte[] request = concat(head.getBytes(US_ASCII), compressed);

		String response = exchange(eventloop, listen(), request);

		assertTrue("the decompressed crossing takes the connection-tier 400 path:\n" + response,
			response.startsWith(CONNECTION_TIER_400));
		assertFalse("a regression that left gzip undecoded would answer 200: " + response, response.contains("200"));
		assertFalse("an undecoded body would dispatch and answer -32700: " + response, response.contains("-32700"));
	}

	/**
	 * A gzip of 2,000,000 incompressible bytes (a fixed-seed {@link Random} — deterministic, no
	 * flakiness) compresses to slightly <b>more</b> than its input: the <b>compressed</b> length
	 * exceeds the 1 MB bound. The row-3 check sees the declared (compressed) length and refuses up
	 * front — {@code 413} — without reading a single body byte, which is the two-tier composition:
	 * the up-front check is on the declared length, the decompressed bound is the streaming tier's.
	 */
	@Test
	public void aGzipBodyWhoseCompressedLengthExceedsTheBoundIs413UpFront() throws Exception {
		byte[] incompressible = new byte[2_000_000];
		new Random(42).nextBytes(incompressible);
		byte[] compressed = gzip(incompressible);
		assertTrue("2 MB of fixed-seed random data must compress to over the default 1 MB bound",
			compressed.length > 1_048_576);

		String head = exchangeHead(eventloop, listen(),
			"POST / HTTP/1.1\r\n" +
				"Host: localhost\r\n" +
				"Content-Type: application/json\r\n" +
				"Content-Encoding: gzip\r\n" +
				"Content-Length: " + compressed.length + "\r\n" +
				"Connection: close\r\n" +
				"\r\n");

		assertTrue("the declared (compressed) length is refused up front: " + head,
			head.startsWith("HTTP/1.1 413 Payload Too Large"));
	}

	/** Builds a server over the real servlet with the default bound and listens on {@code :0}. */
	private JsonRpcHttpTestServer listen() throws IOException {
		JsonRpcDispatcher dispatcher = JsonRpcDispatcher.builder(eventloop)
			.withService(TestApi.class, new TestApiImpl())
			.build();
		JsonRpcHttpTestServer server = JsonRpcHttpTestServer.builder(eventloop)
			.withServlet(JsonRpcServlet.create(eventloop, dispatcher))
			.build();
		server.listen();
		return server;
	}

	/** A complete gzip file — {@code GZIPOutputStream} close writes the trailer (FR-020b's fixture). */
	private static byte[] gzip(byte[] data) throws IOException {
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		try (GZIPOutputStream gz = new GZIPOutputStream(baos)) {
			gz.write(data);
		}
		return baos.toByteArray();
	}

	private static byte[] concat(byte[] first, byte[] second) {
		byte[] result = new byte[first.length + second.length];
		System.arraycopy(first, 0, result, 0, first.length);
		System.arraycopy(second, 0, result, first.length, second.length);
		return result;
	}
}
