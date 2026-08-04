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

/**
 * Writes {@link Http3Frame}s (RFC 9114 §7.2). A thin dispatcher onto each frame's own
 * {@code writeTo}/{@code encodedLength} — mirrors {@code core-quic}'s {@code QuicFrames} shape.
 * <p>
 * There is no {@code read} here, unlike {@code QuicFrames}: an HTTP/3 frame arrives on a QUIC
 * <i>stream</i>, not bounded within a single packet payload, so decoding must be resumable across
 * arbitrary buffer boundaries — that is {@link Http3FrameReader}'s job, not a synchronous
 * whole-buffer read.
 */
public final class Http3Frames {
	private Http3Frames() {
	}

	public static void write(ByteBuf out, Http3Frame frame) {
		frame.writeTo(out);
	}

	public static int encodedLength(Http3Frame frame) {
		return frame.encodedLength();
	}
}
