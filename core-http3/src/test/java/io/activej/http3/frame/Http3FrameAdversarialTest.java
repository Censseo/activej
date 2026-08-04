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

package io.activej.http3.frame;

import io.activej.bytebuf.ByteBuf;
import io.activej.bytebuf.ByteBufPool;
import io.activej.http3.Http3Errors;
import io.activej.http3.Http3Exception;
import io.activej.quic.codec.QuicVarInts;
import io.activej.test.rules.ByteBufRule;
import org.junit.ClassRule;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Hand-built hostile frame headers, each producing the exact RFC 9114 error code rather than a
 * crash, a hang, or an unbounded allocation (FR-024, FR-026, SC-010).
 */
public class Http3FrameAdversarialTest {

	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	private static final long MAX_FRAME_SIZE = 16 * 1024;

	@Test
	public void reservedFrameTypesAreRejected() {
		for (long reserved : new long[] {0x02, 0x06, 0x08, 0x09}) {
			ByteBuf buf = ByteBufPool.allocate(8);
			QuicVarInts.write(buf, reserved);
			QuicVarInts.write(buf, 0);

			Http3FrameReader reader = new Http3FrameReader(MAX_FRAME_SIZE);
			Http3Exception e = assertThrows(Http3Exception.class, () -> reader.feed(buf));
			assertEquals(Http3Errors.H3_FRAME_UNEXPECTED, e.errorCode());
			buf.recycle();
		}
	}

	@Test
	public void absurdlyLargeDeclaredLengthIsRejectedBeforeAllocating() {
		// A multi-gigabyte declared length, claimed by a DATA frame header. The reader must reject
		// it against maxFrameSize the instant the Length varint is parsed -- never attempt to
		// allocate or read that much.
		ByteBuf buf = ByteBufPool.allocate(16);
		QuicVarInts.write(buf, DataFrame.TYPE);
		QuicVarInts.write(buf, 4_000_000_000L);

		Http3FrameReader reader = new Http3FrameReader(MAX_FRAME_SIZE);
		Http3Exception e = assertThrows(Http3Exception.class, () -> reader.feed(buf));
		assertEquals(Http3Errors.H3_FRAME_ERROR, e.errorCode());
		buf.recycle();
	}

	@Test
	public void near62BitDeclaredLengthIsRejectedBeforeAllocating() {
		ByteBuf buf = ByteBufPool.allocate(16);
		QuicVarInts.write(buf, SettingsFrame.TYPE);
		QuicVarInts.write(buf, QuicVarInts.MAX_VALUE);

		Http3FrameReader reader = new Http3FrameReader(MAX_FRAME_SIZE);
		Http3Exception e = assertThrows(Http3Exception.class, () -> reader.feed(buf));
		assertEquals(Http3Errors.H3_FRAME_ERROR, e.errorCode());
		buf.recycle();
	}

	@Test
	public void settingsPayloadNotAWholeNumberOfPairsIsRejected() {
		// One complete identifier/value pair followed by a single dangling byte: not enough left
		// to complete a second pair.
		ByteBuf payload = ByteBufPool.allocate(8);
		QuicVarInts.write(payload, 0x01);
		QuicVarInts.write(payload, 0);
		payload.writeByte((byte) 0x3F); // starts a 1-byte varint identifier, but no value follows

		ByteBuf buf = ByteBufPool.allocate(16);
		QuicVarInts.write(buf, SettingsFrame.TYPE);
		QuicVarInts.write(buf, payload.readRemaining());
		buf.put(payload);
		payload.recycle();

		Http3FrameReader reader = new Http3FrameReader(MAX_FRAME_SIZE);
		Http3Exception e = assertThrows(Http3Exception.class, () -> reader.feed(buf));
		assertEquals(Http3Errors.H3_FRAME_ERROR, e.errorCode());
		buf.recycle();
	}

	@Test
	public void settingsWithReservedIdentifierIsRejected() {
		for (long reservedId : new long[] {0x02, 0x03, 0x04, 0x05}) {
			SettingsFrame frame = new SettingsFrame(new long[] {reservedId}, new long[] {0});
			ByteBuf buf = ByteBufPool.allocate(frame.encodedLength());
			Http3Frames.write(buf, frame);

			Http3FrameReader reader = new Http3FrameReader(MAX_FRAME_SIZE);
			Http3Exception e = assertThrows(Http3Exception.class, () -> reader.feed(buf));
			assertEquals(Http3Errors.H3_SETTINGS_ERROR, e.errorCode());
			buf.recycle();
		}
	}

	@Test
	public void settingsWithDuplicatedIdentifierIsRejected() {
		SettingsFrame frame = new SettingsFrame(new long[] {0x01, 0x01}, new long[] {0, 1});
		ByteBuf buf = ByteBufPool.allocate(frame.encodedLength());
		Http3Frames.write(buf, frame);

		Http3FrameReader reader = new Http3FrameReader(MAX_FRAME_SIZE);
		Http3Exception e = assertThrows(Http3Exception.class, () -> reader.feed(buf));
		assertEquals(Http3Errors.H3_SETTINGS_ERROR, e.errorCode());
		buf.recycle();
	}
}
