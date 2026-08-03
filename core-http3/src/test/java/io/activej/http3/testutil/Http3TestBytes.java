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
import io.activej.bytebuf.ByteBufPool;
import io.activej.bytebuf.ByteBufs;
import io.activej.http3.Http3Headers;
import io.activej.http3.Http3Headers.Field;
import io.activej.http3.frame.CancelPushFrame;
import io.activej.http3.frame.DataFrame;
import io.activej.http3.frame.GoAwayFrame;
import io.activej.http3.frame.HeadersFrame;
import io.activej.http3.frame.Http3Frame;
import io.activej.http3.frame.Http3Frames;
import io.activej.http3.frame.MaxPushIdFrame;
import io.activej.http3.qpack.QpackEncoder;
import io.activej.http3.qpack.QpackStaticEncoder;
import io.activej.promise.Promise;
import io.activej.promise.Promises;
import io.activej.quic.codec.QuicVarInts;
import io.activej.quic.stream.QuicStream;

import java.util.ArrayList;
import java.util.List;

/**
 * Hand-built HTTP/3 wire bytes, plus the one read loop every connection-setup test needs.
 * <p>
 * <b>Why by hand.</b> Most of what {@code Http3Connection} must reject is something the module's own
 * value types refuse to construct — {@code SettingsFrame} rejects a duplicated or reserved identifier
 * at <i>decode</i> time, so a test that wants the peer to send one cannot go through
 * {@code Http3Frames.write}. These helpers write the varints directly, which is also what keeps the
 * assertion about the bytes rather than about the encoder.
 * <p>
 * <b>Ownership.</b> Every factory returns an owned {@link ByteBuf} — hand it to
 * {@code QuicStream.writer().accept(…)}, which takes ownership on every path, or recycle it.
 * {@link #collect} adds the buffers it reads to the caller's {@link ByteBufs}, which the caller
 * recycles.
 */
public final class Http3TestBytes {
	/** Stateless with a dynamic-table capacity of 0 (RFC 9204 §5), so one instance serves every test. */
	private static final QpackEncoder ENCODER = new QpackStaticEncoder();

	private Http3TestBytes() {}

	/** The RFC 9114 §6.2 unidirectional stream header: the stream-type varint and nothing else. */
	public static ByteBuf streamHeader(long streamType) {
		ByteBuf buf = ByteBufPool.allocate(QuicVarInts.encodedLength(streamType));
		QuicVarInts.write(buf, streamType);
		return buf;
	}

	/**
	 * A SETTINGS frame written identifier-by-identifier, so a test can emit a duplicate, a reserved
	 * identifier or a GREASE identifier that {@code SettingsFrame} itself would refuse.
	 */
	public static ByteBuf settingsFrame(long[] identifiers, long[] values) {
		if (identifiers.length != values.length) {
			throw new IllegalArgumentException("identifiers and values must be the same length");
		}
		int payloadLength = 0;
		for (int i = 0; i < identifiers.length; i++) {
			payloadLength += QuicVarInts.encodedLength(identifiers[i]) + QuicVarInts.encodedLength(values[i]);
		}
		ByteBuf buf = ByteBufPool.allocate(payloadLength + 16);
		QuicVarInts.write(buf, 0x04);
		QuicVarInts.write(buf, payloadLength);
		for (int i = 0; i < identifiers.length; i++) {
			QuicVarInts.write(buf, identifiers[i]);
			QuicVarInts.write(buf, values[i]);
		}
		return buf;
	}

	/**
	 * A GOAWAY frame carrying {@code id} (RFC 9114 §7.2.6). Owned by the caller.
	 * <p>
	 * Through the module's own encoder rather than raw varints: nothing about a GOAWAY is something
	 * {@code GoAwayFrame} would refuse to construct, and what the tests here assert is the connection's
	 * reaction to the identifier, not the shape of the frame that carried it.
	 */
	public static ByteBuf goAwayFrame(long id) {
		GoAwayFrame frame = new GoAwayFrame(id);
		ByteBuf buf = ByteBufPool.allocate(Http3Frames.encodedLength(frame));
		Http3Frames.write(buf, frame);
		return buf;
	}

	/**
	 * A MAX_PUSH_ID frame carrying {@code pushId} (RFC 9114 §7.2.7). Owned by the caller.
	 * <p>
	 * Through the module's own encoder, like {@link #goAwayFrame}: what a push test asserts is the
	 * connection's reaction to the identifier and to the <i>direction</i> the frame travelled in, not the
	 * shape of the frame that carried it.
	 */
	public static ByteBuf maxPushIdFrame(long pushId) {
		return encode(new MaxPushIdFrame(pushId));
	}

	/** A CANCEL_PUSH frame naming {@code pushId} (RFC 9114 §7.2.3). Owned by the caller. */
	public static ByteBuf cancelPushFrame(long pushId) {
		return encode(new CancelPushFrame(pushId));
	}

	/**
	 * A PUSH_PROMISE frame (RFC 9114 §7.2.5) naming {@code pushId}, with an empty field section.
	 * <p>
	 * Written by hand because this module has no PUSH_PROMISE type at all — it never pushes and never
	 * grants a push id (FR-040), so the frame exists here only as something a peer can send at us. The
	 * field section is left empty on purpose: the rejection must happen on the frame <i>type</i>, before
	 * anything looks at what it carries.
	 */
	public static ByteBuf pushPromiseFrame(long pushId) {
		int payloadLength = QuicVarInts.encodedLength(pushId);
		ByteBuf buf = ByteBufPool.allocate(payloadLength + 16);
		QuicVarInts.write(buf, 0x05);
		QuicVarInts.write(buf, payloadLength);
		QuicVarInts.write(buf, pushId);
		return buf;
	}

	private static ByteBuf encode(Http3Frame frame) {
		ByteBuf buf = ByteBufPool.allocate(Http3Frames.encodedLength(frame));
		Http3Frames.write(buf, frame);
		return buf;
	}

	/** An arbitrary {@code Type · Length · Payload} frame — used for the frames a codec would not emit. */
	public static ByteBuf frame(long type, byte[] payload) {
		ByteBuf buf = ByteBufPool.allocate(payload.length + 16);
		QuicVarInts.write(buf, type);
		QuicVarInts.write(buf, payload.length);
		buf.put(payload);
		return buf;
	}

	/**
	 * A HEADERS frame carrying {@code fields}, QPACK-encoded with the static table. Owned by the caller.
	 * <p>
	 * The one helper here that goes through a real encoder rather than raw varints: a field section a
	 * test writes by hand would assert against this module's decoder rather than against RFC 9204.
	 */
	public static ByteBuf headersFrame(List<Field> fields) {
		HeadersFrame frame = new HeadersFrame(ENCODER.encode(Http3Headers.toQpack(fields)));
		try {
			ByteBuf buf = ByteBufPool.allocate(Http3Frames.encodedLength(frame));
			Http3Frames.write(buf, frame);
			return buf;
		} finally {
			frame.recycle();
		}
	}

	/** A DATA frame carrying {@code payload}. Owned by the caller. */
	public static ByteBuf dataFrame(byte[] payload) {
		DataFrame frame = new DataFrame(ByteBuf.wrapForReading(payload));
		ByteBuf buf = ByteBufPool.allocate(Http3Frames.encodedLength(frame));
		Http3Frames.write(buf, frame);
		return buf;
	}

	/**
	 * A DATA frame header claiming {@code declaredLength} bytes, followed by only {@code sent} of them
	 * — the frame a peer sends when it wants a receiver to reserve for a body it has no intention of
	 * delivering (T110). Owned by the caller.
	 */
	public static ByteBuf overDeclaredDataFrame(long declaredLength, byte[] sent) {
		return overDeclaredFrame(DataFrame.TYPE, declaredLength, sent);
	}

	/**
	 * The same lie told with a HEADERS frame (T116): a field section claiming {@code declaredLength}
	 * bytes, of which only {@code sent} follow. A field section cannot be delivered in instalments — QPACK
	 * needs it whole — so what stands between this and an allocation of {@code declaredLength} is the
	 * bound the reader checks the declared length against. Owned by the caller.
	 */
	public static ByteBuf overDeclaredHeadersFrame(long declaredLength, byte[] sent) {
		return overDeclaredFrame(HeadersFrame.TYPE, declaredLength, sent);
	}

	private static ByteBuf overDeclaredFrame(long type, long declaredLength, byte[] sent) {
		ByteBuf buf = ByteBufPool.allocate(
			QuicVarInts.encodedLength(type) + QuicVarInts.encodedLength(declaredLength) + sent.length);
		QuicVarInts.write(buf, type);
		QuicVarInts.write(buf, declaredLength);
		buf.put(sent);
		return buf;
	}

	/** The four request pseudo-headers of RFC 9114 §4.3.1, in order, as a list a test may extend. */
	public static List<Field> requestFields(String method, String path) {
		List<Field> fields = new ArrayList<>();
		fields.add(new Field(":method", method));
		fields.add(new Field(":scheme", "https"));
		fields.add(new Field(":authority", Http3TestTls.SERVER_NAME));
		fields.add(new Field(":path", path));
		return fields;
	}

	/** The literal bytes given, as an owned buffer. */
	public static ByteBuf bytes(int... unsignedBytes) {
		ByteBuf buf = ByteBufPool.allocate(Math.max(1, unsignedBytes.length));
		for (int b : unsignedBytes) {
			buf.put((byte) b);
		}
		return buf;
	}

	/** {@code left} followed by {@code right}; both are consumed and recycled. */
	public static ByteBuf concat(ByteBuf left, ByteBuf right) {
		ByteBuf buf = ByteBufPool.allocate(left.readRemaining() + right.readRemaining());
		buf.put(left);
		buf.put(right);
		left.recycle();
		right.recycle();
		return buf;
	}

	/**
	 * Reads every byte {@code stream} ever delivers into {@code into}, which the caller recycles.
	 * <p>
	 * A control stream is never FINed by a healthy peer, so {@code toCollector} would never resolve —
	 * this accumulates instead, and the test drives until enough bytes have landed.
	 */
	public static Promise<Void> collect(QuicStream stream, ByteBufs into) {
		return Promises.repeat(() -> stream.reader().get()
			.map(buf -> {
				if (buf == null) return false;
				into.add(buf);
				return true;
			}));
	}
}
