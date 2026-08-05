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

import io.activej.common.exception.MalformedDataException;

/**
 * A peer behaviour the <b>transport</b> forbids, raised from inside the TLS layer: the connection
 * layer maps it to an RFC 9000 §20.1 {@code PROTOCOL_VIOLATION} (0x0a) CONNECTION_CLOSE.
 * <p>
 * It is deliberately not a {@link TlsAlertException}. An alert says "this handshake is malformed"
 * and travels as CRYPTO_ERROR {@code 0x0100 + alert}; the conditions carried here — a
 * {@code NewSessionTicket} over the configured size bound, more tickets than a connection may
 * deliver, a {@code max_early_data_size} other than {@code 0xffffffff} (RFC 9001 §4.6.1) — are
 * transport-level rules with no alert code that names them. Reporting one under a borrowed alert
 * would misname the failure in every log and every peer's close frame.
 * <p>
 * Extending {@link MalformedDataException} follows the
 * {@code QuicTransportParameters.DuplicateTransportParameterException} precedent: the engine's
 * {@code consume} already declares it, so no signature changes and the connection layer selects on
 * the concrete type.
 * <p>
 * The message names the condition and the setting key, never a ticket byte or any key material
 * (spec FR-050, SI-6).
 */
public final class TlsProtocolViolationException extends MalformedDataException {

	public TlsProtocolViolationException(String message) {
		super(message);
	}
}
