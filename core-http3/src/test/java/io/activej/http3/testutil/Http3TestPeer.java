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

package io.activej.http3.testutil;

import io.activej.bytebuf.ByteBuf;
import io.activej.bytebuf.ByteBufs;
import io.activej.common.recycle.Recyclers;
import io.activej.csp.consumer.ChannelConsumer;
import io.activej.http3.Http3Connection;
import io.activej.http3.Http3Headers;
import io.activej.http3.Http3Headers.Field;
import io.activej.http3.Http3Settings;
import io.activej.http3.frame.DataFrame;
import io.activej.http3.frame.HeadersFrame;
import io.activej.http3.frame.Http3Frame;
import io.activej.http3.frame.Http3FrameReader;
import io.activej.http3.qpack.QpackDecoder;
import io.activej.http3.qpack.QpackStaticDecoder;
import io.activej.promise.Promise;
import io.activej.promise.Promises;
import io.activej.quic.stream.QuicStream;
import io.activej.reactor.Reactor;
import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * The client half of an HTTP/3 exchange, for testing a server before {@code Http3Client} exists
 * (feature 05 phase 8).
 * <p>
 * It is a real {@link Http3Connection} — the control stream, SETTINGS and the QPACK stream rules are
 * the module's own — with a hand-driven request on top: open a bidirectional stream, write a HEADERS
 * frame and an optional DATA frame, FIN, then read every byte back and decode it. Deliberately not a
 * client implementation: no pooling, no queueing, no timeouts, so a server test cannot pass because
 * two halves of the same abstraction agree with each other.
 * <p>
 * <b>Ownership</b>: every buffer this class allocates is either handed to
 * {@link QuicStream#writer()} (which takes ownership on every path) or recycled here. A
 * {@link Response} holds plain {@code byte[]}, so a test never has to recycle one.
 * <p>
 * Construct it <b>before</b> {@link Http3WirePair#connect()} — it installs itself as the pair's
 * client-side frame handler:
 * <pre>{@code
 * wire = new Http3WirePair(loop);
 * peer = new Http3TestPeer(wire);
 * wire.withServerFactory(...).connect();
 * }</pre>
 */
public final class Http3TestPeer {
	/** Bounds what a test will decode off the wire; the responses here are a few hundred bytes. */
	private static final long MAX_RESPONSE_FRAME_SIZE = 1 << 20;

	private final QpackDecoder decoder;

	private @Nullable Http3Connection h3;

	public Http3TestPeer(Http3WirePair wire) {
		this(wire, Http3Settings.create());
	}

	public Http3TestPeer(Http3WirePair wire, Http3Settings settings) {
		this.decoder = new QpackStaticDecoder(settings.maxFieldSectionSize());
		wire.withClientHandlerFactory(connection -> {
			h3 = Http3Connection.builder(Reactor.getCurrentReactor(), connection)
				.withSettings(settings)
				.build();
			// Not startAndGetStreamManager(): with Http3Settings.datagramsEnabled() the stream manager alone
			// is not the frame handler, and this peer is used with datagrams both off and on.
			return h3.startAndGetFrameHandler();
		});
	}

	/** One decoded HTTP/3 response. */
	public record Response(int status, List<Field> fields, byte[] body) {
		public String bodyString() {
			return new String(body, StandardCharsets.UTF_8);
		}

		public @Nullable String field(String name) {
			for (Field field : fields) {
				if (field.name().equals(name)) return field.value();
			}
			return null;
		}
	}

	public Http3Connection connection() {
		if (h3 == null) throw new IllegalStateException("The peer has no connection yet — call wire.connect() first");
		return h3;
	}

	// ---------------------------------------------------------------- requests

	public Promise<Response> get(String path) {
		return request(Http3TestBytes.requestFields("GET", path), null);
	}

	public Promise<Response> post(String path, byte[] body) {
		List<Field> fields = Http3TestBytes.requestFields("POST", path);
		fields.add(new Field("content-length", Integer.toString(body.length)));
		return request(fields, body);
	}

	/**
	 * Sends one request on a fresh bidirectional stream and reads the whole response back. The promise
	 * fails, unwrapped, with whatever the stream layer reports if the server aborts the stream.
	 */
	public Promise<Response> request(List<Field> fields, byte @Nullable [] body) {
		return open().then(stream -> {
			ChannelConsumer<ByteBuf> writer = stream.writer();
			Promise<Void> written = writer.accept(Http3TestBytes.headersFrame(fields));
			if (body != null) {
				written = written.then(() -> writer.accept(Http3TestBytes.dataFrame(body)));
			}
			return written
				.then(() -> writer.accept(null))
				.then(() -> readResponse(stream));
		});
	}

	/**
	 * Sends only the request head and leaves the stream open — the client that stalls mid-request, which
	 * is what a server's per-request timeout exists for (FR-046a).
	 */
	public Promise<Response> requestWithoutFin(List<Field> fields) {
		return open().then(stream -> stream.writer().accept(Http3TestBytes.headersFrame(fields))
			.then(() -> readResponse(stream)));
	}

	/** Opens a request stream and hands it over raw, for a test that writes something no encoder would. */
	public Promise<QuicStream> open() {
		return connection().streamManager().openBidirectional();
	}

	// ---------------------------------------------------------------- framing

	// ---------------------------------------------------------------- reading

	/** Reads {@code stream} to its FIN, then decodes the frames it carried. */
	public Promise<Response> readResponse(QuicStream stream) {
		ByteBufs received = new ByteBufs();
		return Promises.repeat(() -> stream.reader().get()
				.map(buf -> {
					if (buf == null) return false;
					received.add(buf);
					return true;
				}))
			.map($ -> decodeResponse(received))
			.whenException(e -> received.recycle());
	}

	private Response decodeResponse(ByteBufs received) throws Exception {
		ByteBuf all = received.takeRemaining();
		ByteBufs body = new ByteBufs();
		try {
			Http3FrameReader reader = new Http3FrameReader(MAX_RESPONSE_FRAME_SIZE);
			List<Field> fields = List.of();
			Http3Frame frame;
			while ((frame = reader.feed(all)) != null) {
				if (frame instanceof HeadersFrame headers) {
					fields = Http3Headers.fromQpack(decoder.decode(headers.fieldSection));
				} else if (frame instanceof DataFrame data) {
					body.add(data.data);
				} else {
					Recyclers.recycle(frame);
				}
			}
			ByteBuf bodyBuf = body.takeRemaining();
			try {
				return new Response(statusOf(fields), fields, bodyBuf.getArray());
			} finally {
				bodyBuf.recycle();
			}
		} finally {
			all.recycle();
			body.recycle();
		}
	}

	private static int statusOf(List<Field> fields) {
		for (Field field : fields) {
			if (field.name().equals(":status")) return Integer.parseInt(field.value());
		}
		throw new AssertionError("The response field section carries no :status pseudo-header");
	}
}
