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
import io.activej.common.recycle.Recyclers;
import io.activej.http3.frame.Http3Frame;
import io.activej.http3.frame.Http3FrameReader;
import io.activej.http3.frame.Http3Frames;
import io.activej.http3.frame.SettingsFrame;
import io.activej.quic.tls.QuicSessionTicket;
import org.jetbrains.annotations.Nullable;

/**
 * The HTTP/3 half of a session ticket: the peer's SETTINGS, carried in
 * {@link QuicSessionTicket#applicationSettings()} so that a resumed connection can obey them before
 * the server's own SETTINGS arrive (spec FR-062, FR-063; RFC 9114 §7.2.4.2).
 * <p>
 * Package-private, and shared by the two consumers of one rule: {@link Http3Client} encodes and
 * decodes the blob, {@link Http3Connection} obeys and enforces it. {@code core-quic} never looks
 * inside — an {@code applicationSettings} blob is opaque there by design.
 */
final class Http3RememberedSettings {
	private Http3RememberedSettings() {}

	/** The blob stored in {@link QuicSessionTicket#applicationSettings()}: one encoded SETTINGS frame. */
	static byte[] encode(SettingsFrame settings) {
		ByteBuf buf = ByteBufPool.allocate(Http3Frames.encodedLength(settings));
		try {
			Http3Frames.write(buf, settings);
			return buf.getArray();
		} finally {
			buf.recycle();
		}
	}

	/**
	 * Total: {@code null} for an empty, oversized, malformed, non-SETTINGS or trailing-garbage blob.
	 * Never throws.
	 * <p>
	 * The blob is <b>semi-untrusted</b> — a consumer may have persisted the ticket store and read it
	 * back from somewhere this process does not control — so its declared length is bounded before
	 * anything proportional to it is allocated, unconditionally and never behind {@code Checks}
	 * (SI-1, WI-10). Every "no" answer means the same thing as an absent blob: no remembered
	 * SETTINGS, and so no early data (FR-062).
	 */
	static @Nullable SettingsFrame decode(byte[] blob, long maxControlFrameSize) {
		if (blob.length == 0) return null;
		ByteBuf in = ByteBuf.wrapForReading(blob);
		Http3FrameReader reader = new Http3FrameReader(maxControlFrameSize, Http3Errors.H3_EXCESSIVE_LOAD);
		try {
			Http3Frame frame = reader.feed(in);
			if (frame == null) return null;
			if (!(frame instanceof SettingsFrame settings)) {
				Recyclers.recycle(frame);
				return null;
			}
			return in.canRead() || reader.isMidFrame() ? null : settings;
		} catch (Http3Exception e) {
			return null;
		} finally {
			reader.recycle();
			in.recycle();
		}
	}

	/**
	 * The SETTINGS remembered with {@code ticket}, or {@code null} when it carries none — which is
	 * FR-062's "no remembered SETTINGS, no early data", valid ticket or not.
	 */
	static @Nullable SettingsFrame of(QuicSessionTicket ticket, long maxControlFrameSize) {
		return decode(ticket.applicationSettings(), maxControlFrameSize);
	}

	/** The value {@code frame} carries for {@code identifier}, or {@code defaultValue} if it carries none. */
	static long valueOf(SettingsFrame frame, long identifier, long defaultValue) {
		for (int i = 0; i < frame.identifiers.length; i++) {
			if (frame.identifiers[i] == identifier) return frame.values[i];
		}
		return defaultValue;
	}

	/**
	 * RFC 9114 §7.2.4.2: a server resuming a session may not reduce a SETTINGS value the client could
	 * already have relied on in early data.
	 * <p>
	 * Exactly one identifier is checked, {@code SETTINGS_MAX_FIELD_SECTION_SIZE} (0x06), because it is
	 * the only remembered value early data can rely on in this phase: an early-data field section is
	 * encoded against the QPACK <b>static</b> table, since the dynamic encoder is built only in
	 * {@code onPeerSettingsApplied} — that is, only once the peer's real SETTINGS have arrived, which
	 * is after any early data was sent. RFC 9114 §7.2.4.2 forbids reducing what the client
	 * <i>could have violated</i>, so checking {@code QPACK_MAX_TABLE_CAPACITY} or
	 * {@code QPACK_BLOCKED_STREAMS} too would close connections against conforming servers. The check
	 * widens on the day early data uses the dynamic table.
	 * <p>
	 * An omitted 0x06 is unlimited (RFC 9114 §7.2.4.1), so it is never a reduction in either position.
	 *
	 * @throws Http3Exception {@code H3_SETTINGS_ERROR} on a reduction
	 */
	static void validateNoReduction(@Nullable SettingsFrame remembered, SettingsFrame current) throws Http3Exception {
		if (remembered == null) return;
		long before = valueOf(remembered, SettingsFrame.MAX_FIELD_SECTION_SIZE, Long.MAX_VALUE);
		long after = valueOf(current, SettingsFrame.MAX_FIELD_SECTION_SIZE, Long.MAX_VALUE);
		if (after < before) {
			throw new Http3Exception(Http3Errors.H3_SETTINGS_ERROR,
				"SETTINGS identifier 0x" + Long.toHexString(SettingsFrame.MAX_FIELD_SECTION_SIZE) +
				" was reduced from the remembered " + before + " to " + after +
				" on a resumed connection (RFC 9114 §7.2.4.2)");
		}
	}
}
