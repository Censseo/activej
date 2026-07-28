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

package io.activej.quic.tls;

import io.activej.bytebuf.ByteBuf;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * The immutable outcome of one {@link TlsEngine#consume(EncryptionLevel, ByteBuf)} call
 * (FR-013): CRYPTO bytes to send per encryption level (RFC 9001 §4.1.2), key-installation
 * events in firing order (Handshake before 1-RTT, RFC 9001 §4.9.1), and — once, at completion —
 * the negotiated ALPN and the peer's transport parameters (RFC 9000 §18).
 * <p>
 * Buffer ownership: the {@link ByteBuf} values of {@link #cryptoToSend()} are freshly allocated
 * and <b>caller-owned</b> — the caller MUST recycle every one of them, including on its own
 * failure paths. Everything else is immutable JDK data.
 */
public final class TlsEngineResult {
	private static final TlsEngineResult EMPTY = new TlsEngineResult(Map.of(), List.of(), null, null, false);

	private final Map<EncryptionLevel, ByteBuf> cryptoToSend;
	private final List<KeyInstallation> keysToInstall;
	private final @Nullable String negotiatedAlpn;
	private final @Nullable QuicTransportParameters peerTransportParameters;
	private final boolean handshakeComplete;

	private TlsEngineResult(Map<EncryptionLevel, ByteBuf> cryptoToSend, List<KeyInstallation> keysToInstall,
			@Nullable String negotiatedAlpn, @Nullable QuicTransportParameters peerTransportParameters,
			boolean handshakeComplete) {
		this.cryptoToSend = cryptoToSend instanceof EnumMap<EncryptionLevel, ByteBuf> enumMap
			? Collections.unmodifiableMap(enumMap)
			: Map.copyOf(cryptoToSend);
		this.keysToInstall = List.copyOf(keysToInstall);
		this.negotiatedAlpn = negotiatedAlpn;
		this.peerTransportParameters = peerTransportParameters;
		this.handshakeComplete = handshakeComplete;
	}

	/** A result carrying nothing — no output bytes, no installations, no completion. */
	public static TlsEngineResult empty() {
		return EMPTY;
	}

	/** An in-progress result: output bytes and/or key installations, handshake not complete. */
	static TlsEngineResult of(Map<EncryptionLevel, ByteBuf> cryptoToSend, List<KeyInstallation> keysToInstall) {
		return new TlsEngineResult(cryptoToSend, keysToInstall, null, null, false);
	}

	/** The completing result (FR-013): the negotiated ALPN and peer transport parameters surface here. */
	static TlsEngineResult complete(Map<EncryptionLevel, ByteBuf> cryptoToSend, List<KeyInstallation> keysToInstall,
			String negotiatedAlpn, QuicTransportParameters peerTransportParameters) {
		return new TlsEngineResult(cryptoToSend, keysToInstall, negotiatedAlpn, peerTransportParameters, true);
	}

	/**
	 * CRYPTO bytes to send, per encryption level. The buffers are <b>caller-owned</b> — recycle
	 * them (DI-1). Absent levels have nothing to send.
	 */
	public Map<EncryptionLevel, ByteBuf> cryptoToSend() {
		return cryptoToSend;
	}

	/** Key-installation events in firing order: Handshake level first, 1-RTT after the peer's Finished. */
	public List<KeyInstallation> keysToInstall() {
		return keysToInstall;
	}

	/** The negotiated ALPN protocol ({@code h3}, FR-012), or {@code null} before completion. */
	public @Nullable String negotiatedAlpn() {
		return negotiatedAlpn;
	}

	/** The peer's QUIC transport parameters (RFC 9000 §18), or {@code null} before completion. */
	public @Nullable QuicTransportParameters peerTransportParameters() {
		return peerTransportParameters;
	}

	/** {@code true} exactly once — on the result that completes the handshake. */
	public boolean handshakeComplete() {
		return handshakeComplete;
	}
}
