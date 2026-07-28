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
import io.activej.common.exception.MalformedDataException;

/**
 * A synchronous, non-reactive TLS 1.3 handshake engine for the QUIC profile (RFC 8446
 * restricted by RFC 9001 §8), one instance per connection attempt (FR-013, spec clarification
 * Q3 — no {@code Reactor}, no {@code Promise}; thread confinement is the caller's contract).
 * <p>
 * Input is the in-order, de-duplicated CRYPTO byte stream of one encryption level per call
 * (RFC 9001 §4.1.2); split or coalesced handshake messages are buffered per level until whole.
 * Output — CRYPTO bytes to send, key installations, the peer's transport parameters, the
 * negotiated ALPN and the completion signal — is returned on {@link TlsEngineResult}.
 * <p>
 * Buffer ownership: {@code cryptoBytes} is <b>owned by this method and recycled on every
 * path</b>, success and failure alike (DI-1, spec §Error Scenarios). The buffers on the
 * returned result are caller-owned.
 * <p>
 * Failures are terminal: after any thrown exception the engine is dead and further
 * {@code consume} calls fail fast. The engine never sends alerts on the wire itself — the
 * connection layer maps {@link TlsAlertException#alertCode()} to a CONNECTION_CLOSE frame of
 * type {@code 0x0100 + code} (RFC 9001 §4.8).
 */
public interface TlsEngine {

	/**
	 * Feeds the next chunk of one level's CRYPTO stream into the state machine.
	 *
	 * @param level which CRYPTO stream {@code cryptoBytes} belongs to (RFC 9001 §4.1.2)
	 * @param cryptoBytes owned by this method — recycled on every path
	 * @return the outcome of every complete message processed; {@link TlsEngineResult#empty()}
	 *         when the bytes complete no handshake message yet
	 * @throws TlsAlertException a handshake failure carrying an RFC 8446 §6 alert code (terminal)
	 * @throws TlsHelloRetryRequestException a HelloRetryRequest was received (terminal; client
	 *         role only — FR-014, HRR is unsupported in this feature)
	 * @throws MalformedDataException a transport-parameter error (RFC 9000 §18 — e.g. a
	 *         duplicate parameter), surfaced unchanged for the connection layer to map to a
	 *         TRANSPORT_PARAMETER_ERROR CONNECTION_CLOSE rather than a TLS alert (terminal)
	 */
	TlsEngineResult consume(EncryptionLevel level, ByteBuf cryptoBytes)
			throws TlsAlertException, TlsHelloRetryRequestException, MalformedDataException;
}
