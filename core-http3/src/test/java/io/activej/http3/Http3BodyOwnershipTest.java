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
import io.activej.bytebuf.ByteBufPool;
import io.activej.common.MemSize;
import io.activej.csp.consumer.ChannelConsumers;
import io.activej.csp.supplier.ChannelSupplier;
import io.activej.csp.supplier.ChannelSuppliers;
import io.activej.http.HttpRequest;
import io.activej.http.HttpResponse;
import io.activej.http3.Http3RequestStream.State;
import io.activej.http3.testutil.Http3TestTls;
import io.activej.http3.testutil.Http3WirePair;
import io.activej.http3.testutil.ManualEventloop;
import io.activej.promise.Promise;
import io.activej.quic.connection.QuicConnectionSettings;
import io.activej.quic.stream.QuicStream;
import io.activej.quic.stream.QuicStreamException;
import io.activej.quic.stream.QuicStreamResetException;
import io.activej.quic.stream.QuicStreamStopSendingException;
import io.activej.reactor.Reactor;
import io.activej.test.rules.ByteBufRule;
import org.jetbrains.annotations.Nullable;
import org.junit.After;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * T083 / FR-053, FR-057: who owns a body, and what happens to the QUIC stream when its owner walks
 * away.
 * <p>
 * Two contracts are asserted here. First, the <b>single-take</b> one: an {@link HttpRequest} or
 * {@link HttpResponse} this module builds is an ordinary {@code core-http} message, so
 * {@code takeBodyStream()} transfers ownership once and {@code loadBody()} afterwards fails — the point
 * being that HTTP/3 inherits that behaviour rather than reimplementing it. Second, <b>abandonment</b>:
 * a consumer that takes a body and closes it mid-transfer owes the peer an answer, and the answer is a
 * stream reset carrying {@code H3_REQUEST_CANCELLED} (0x010c), after which nothing is left holding a
 * buffer.
 * <p>
 * Both ends are real {@link Http3RequestStream}s over one in-process QUIC connection, so the error code
 * asserted is the code that actually crossed the wire: the abandoning side calls
 * {@code reset}/{@code stopSending}, and the peer's own write or read fails with the {@code STOP_SENDING}
 * or {@code RESET_STREAM} that carried it.
 * <p>
 * The per-stream window is deliberately {@link #WINDOW} bytes against a {@link #BODY}-byte body, so
 * "mid-transfer" is a fact rather than a hope: the sender cannot have finished, it is held at the
 * window waiting for credit that abandonment will never grant.
 */
public final class Http3BodyOwnershipTest {
	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	/** Small enough that a {@link #BODY}-byte body cannot be written before the reader walks away. */
	private static final MemSize WINDOW = MemSize.kilobytes(16);

	private static final int CHUNK = 4096;

	/** Several windows, so a transfer is always still in progress when it is abandoned. */
	private static final int BODY = 8 * WINDOW.toInt();

	private static final String URL = "https://" + Http3TestTls.SERVER_NAME + "/upload";

	private final List<Http3RequestStream> serverStreams = new ArrayList<>();

	private ManualEventloop loop;
	private @Nullable Http3WirePair wire;

	@Before
	public void setUp() {
		loop = new ManualEventloop();
	}

	@After
	public void tearDown() {
		if (wire != null) wire.close();
		loop.tickUntilQuiet();
		loop.close();
	}

	// ---------------------------------------------------------------- the single-take contract

	@Test
	public void takingTheRequestBodyStreamThenLoadingItFails() {
		connect();
		Exchange exchange = start(CHUNK);

		ChannelSupplier<ByteBuf> taken = exchange.request().takeBodyStream();

		IllegalStateException e =
			assertThrows(IllegalStateException.class, () -> exchange.request().loadBody());
		assertTrue("FR-053: the single-take contract of core-http, unchanged: " + e.getMessage(),
			e.getMessage().contains("already consumed"));

		// The body is the taker's now, so the taker is what drains it.
		Promise<Void> drained = taken.streamTo(ChannelConsumers.recycling());
		wire.driveUntil(() -> drained.isComplete() && exchange.requestSent().isComplete());
		assertTrue("the abandoned-nothing body drained: " + drained, drained.isResult());
	}

	@Test
	public void takingTheResponseBodyStreamThenLoadingItFails() {
		connect();
		Exchange exchange = start(0);

		Promise<Void> responseSent = exchange.server().sendResponse(HttpResponse.ok200()
			.withBodyStream(body(CHUNK))
			.build());
		Promise<HttpResponse> response = exchange.client().receiveResponse();
		wire.driveUntil(response::isComplete);
		assertTrue("the response head arrived: " + response, response.isResult());

		HttpResponse received = response.getResult();
		ChannelSupplier<ByteBuf> taken = received.takeBodyStream();

		IllegalStateException e = assertThrows(IllegalStateException.class, received::loadBody);
		assertTrue("the contract is the message's, not the server's: " + e.getMessage(),
			e.getMessage().contains("already consumed"));

		Promise<Void> drained = taken.streamTo(ChannelConsumers.recycling());
		wire.driveUntil(() -> drained.isComplete() && responseSent.isComplete());
		assertTrue("the response body drained: " + drained, drained.isResult());
	}

	// ---------------------------------------------------------------- abandonment (FR-057)

	@Test
	public void abandoningARequestBodyMidTransferCancelsTheStream() {
		connect();
		Exchange exchange = start(BODY);

		abandonAfterOneChunk(exchange.request().takeBodyStream());
		assertFalse("the window holds the sender mid-body, which is what makes this an abandonment",
			exchange.requestSent().isComplete());

		wire.driveUntil(exchange.requestSent()::isComplete);
		assertTrue("the writer of an abandoned body fails: " + exchange.requestSent(),
			exchange.requestSent().isException());
		assertCancelled("what the sender was told", exchange.requestSent().getException());

		assertEquals(State.RESET, exchange.server().state());
		Exception terminal = exchange.server().terminalException();
		assertTrue("FR-057: the abandonment is reported as H3_REQUEST_CANCELLED: " + terminal,
			terminal instanceof Http3Exception);
		assertEquals(Http3Errors.H3_REQUEST_CANCELLED, ((Http3Exception) terminal).errorCode());
	}

	@Test
	public void abandoningAResponseBodyMidTransferCancelsTheStream() {
		connect();
		Exchange exchange = start(0);

		Promise<Void> responseSent = exchange.server().sendResponse(HttpResponse.ok200()
			.withBodyStream(body(BODY))
			.build());
		Promise<HttpResponse> response = exchange.client().receiveResponse();
		wire.driveUntil(response::isComplete);
		assertTrue("the response head arrived: " + response, response.isResult());

		abandonAfterOneChunk(response.getResult().takeBodyStream());
		assertFalse("the window holds the server mid-body, which is what makes this an abandonment",
			responseSent.isComplete());

		wire.driveUntil(responseSent::isComplete);
		assertTrue("the server stops writing a response nobody is reading: " + responseSent,
			responseSent.isException());
		assertCancelled("what the responding server was told", responseSent.getException());

		assertEquals(State.RESET, exchange.client().state());
	}

	@Test
	public void abortingAnAbandonedStreamAgainChangesNothing() {
		connect();
		Exchange exchange = start(BODY);

		abandonAfterOneChunk(exchange.request().takeBodyStream());
		wire.driveUntil(exchange.requestSent()::isComplete);

		Exception terminal = exchange.server().terminalException();
		assertNotNull(terminal);

		// Both QUIC verbs are idempotent and so is this one: a second and a third abort neither re-report
		// nor re-reset, and the first reason is the one that stands.
		exchange.server().abort(Http3Errors.H3_INTERNAL_ERROR, "a second abort");
		exchange.server().abort(Http3Errors.H3_INTERNAL_ERROR, "a third abort");
		wire.driveUntil(() -> exchange.client().isTerminated());

		assertEquals(State.RESET, exchange.server().state());
		assertSame("the first reason stands", terminal, exchange.server().terminalException());
	}

	// ---------------------------------------------------------------- harness

	/** One request stream, both halves of it, and the message the server decoded off it. */
	private record Exchange(
		Http3RequestStream client, Http3RequestStream server, Promise<Void> requestSent, HttpRequest request
	) {}

	/** Opens a request stream and writes a request with a {@code bodySize}-byte streamed body onto it. */
	private Exchange start(int bodySize) {
		Http3RequestStream client = requestStream(wire.openNow(wire.clientStreams().openBidirectional()));
		Promise<Void> requestSent = client.sendRequest(HttpRequest.post(URL)
			.withBodyStream(body(bodySize))
			.build());

		wire.driveUntil(() -> !serverStreams.isEmpty());
		Http3RequestStream server = serverStreams.get(0);

		Promise<HttpRequest> request = server.receiveRequest();
		wire.driveUntil(request::isComplete);
		assertTrue("the request head arrived: " + request, request.isResult());

		return new Exchange(client, server, requestSent, request.getResult());
	}

	/**
	 * Reads one chunk of {@code body} — so the transfer is genuinely under way — recycles it, and closes
	 * the supplier without reading the rest. That is the abandonment FR-057 is about.
	 */
	private void abandonAfterOneChunk(ChannelSupplier<ByteBuf> body) {
		Promise<ByteBuf> first = body.get();
		wire.driveUntil(first::isComplete);
		assertTrue("the transfer had started: " + first, first.isResult());
		first.getResult().recycle();
		body.close();
	}

	private void connect() {
		QuicConnectionSettings quic = QuicConnectionSettings.builder()
			.withInitialMaxStreamDataBidiLocal(WINDOW)
			.withInitialMaxStreamDataBidiRemote(WINDOW)
			.build();
		wire = new Http3WirePair(loop)
			.withServerSettings(quic)
			.withClientSettings(quic)
			.withServerStreamListener(stream -> serverStreams.add(requestStream(stream)))
			.connect();
	}

	private static Http3RequestStream requestStream(QuicStream stream) {
		return Http3RequestStream.builder(Reactor.getCurrentReactor(), stream)
			.withSettings(Http3Settings.create())
			.build();
	}

	/** A lazy body: one chunk per {@code get()}, so nothing of it exists before it is asked for. */
	private static ChannelSupplier<ByteBuf> body(int size) {
		int[] remaining = {size};
		return ChannelSuppliers.ofSupplier(() -> {
			if (remaining[0] == 0) return null;
			int length = Math.min(CHUNK, remaining[0]);
			ByteBuf buf = ByteBufPool.allocate(length);
			buf.put(new byte[length]);
			remaining[0] -= length;
			return buf;
		});
	}

	/**
	 * FR-057, FR-058c: the peer of an abandoned body is told through the stream layer's own exception,
	 * unwrapped, carrying {@code H3_REQUEST_CANCELLED} — {@code STOP_SENDING} for a writer, and
	 * {@code RESET_STREAM} for a reader.
	 */
	private static void assertCancelled(String what, Exception e) {
		assertTrue(what + " is the stream layer's own exception: " + e, e instanceof QuicStreamException);
		assertEquals(what + " carries H3_REQUEST_CANCELLED: " + e,
			Http3Errors.H3_REQUEST_CANCELLED, applicationErrorCode(e));
	}

	/** The application error code a {@code STOP_SENDING} or a {@code RESET_STREAM} carried, or -1. */
	private static long applicationErrorCode(Exception e) {
		if (e instanceof QuicStreamStopSendingException stopSending) return stopSending.applicationErrorCode();
		if (e instanceof QuicStreamResetException reset) return reset.applicationErrorCode();
		return -1;
	}
}
