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

/**
 * A TLS 1.3 handshake failure carrying an RFC 8446 §6 alert description code
 * (one of the {@link TlsAlerts} constants).
 * <p>
 * The message names the failing protocol element (message, extension, field) —
 * never key material, shared secrets, traffic secrets, AEAD keys/IVs or session tickets.
 * The engine never sends alerts on the wire itself; the connection layer maps
 * {@link #alertCode()} to a CONNECTION_CLOSE frame of type {@code 0x0100 + code} (RFC 9001 §4.8).
 */
public class TlsAlertException extends Exception {
	private final int alertCode;

	/**
	 * @param alertCode an RFC 8446 §6 alert description code (a {@link TlsAlerts} constant)
	 * @param message names the failing protocol element — never key material, shared secrets,
	 *        traffic secrets, AEAD keys/IVs or session tickets (FR-016, SI-6)
	 */
	public TlsAlertException(int alertCode, String message) {
		super(message);
		this.alertCode = alertCode;
	}

	/** RFC 8446 §6 alert description code (a {@link TlsAlerts} constant). */
	public int alertCode() {
		return alertCode;
	}

	@Override
	public String toString() {
		return "TlsAlertException[" + TlsAlerts.name(alertCode) + "(" + alertCode + "): " + getMessage() + "]";
	}
}
