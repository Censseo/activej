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

/**
 * The QUIC version 1 connection layer: the piece that turns the wire codec of
 * {@link io.activej.quic.codec}, the packet protection of {@link io.activej.quic.crypto} and the
 * TLS 1.3 handshake engines of {@link io.activej.quic.tls} into a live connection over UDP.
 *
 * <p>This package owns what the layers below deliberately do not:
 * <ul>
 *   <li>the connection state machine and handshake orchestration — RFC 9000 §5, §7 and
 *       RFC 9001 §4;</li>
 *   <li>packet number spaces, ACK generation and CRYPTO stream reassembly — RFC 9000 §12.3,
 *       §13.2 and §19.6;</li>
 *   <li>loss detection, probe timeouts and retransmission — RFC 9002 §6;</li>
 *   <li>NewReno congestion control and pacing — RFC 9002 §7;</li>
 *   <li>connection termination: immediate close, draining and idle timeout — RFC 9000 §10;</li>
 *   <li>the anti-amplification limit and address validation — RFC 9000 §8;</li>
 *   <li>the RFC 9001 §6.6 AEAD confidentiality and integrity limits.</li>
 * </ul>
 *
 * <p>Unlike the rest of {@code core-quic}, this package <em>is</em> reactive: every component that
 * holds connection state is bound to a single {@link io.activej.reactor.Reactor} and guards each
 * public method with {@code checkInReactorThread(this)}. Buffer ownership follows the platform
 * rule — a received {@link io.activej.bytebuf.ByteBuf} is owned and must be recycled on every
 * path, including failure paths.
 *
 * <h2>Leniency: what is tolerated, dropped and rejected (SI-7, FR-031a)</h2>
 *
 * <p>Leniency is a decision here, not a default. Every input this layer meets falls into exactly one
 * of three classes, and each class is stated once rather than being inferred from the code:
 *
 * <p><b>Tolerated and ignored</b> — accepted, acted on in no way, never an error:
 * unknown transport parameters (RFC 9000 §18.1); {@code preferred_address} and
 * {@code stateless_reset_token} (the features they configure are out of scope); ECN counts in a
 * received ACK (RFC 9000 §13.4); NEW_CONNECTION_ID, RETIRE_CONNECTION_ID, NEW_TOKEN,
 * PATH_CHALLENGE/PATH_RESPONSE and the flow-control credit frames — a conforming peer sends these
 * unprompted, so rejecting them would break interoperability rather than enforce anything; 0-RTT
 * packets; packets for a level whose keys were already discarded (a late retransmission, RFC 9000
 * §12.3); and packets failing AEAD, which count only toward the RFC 9001 §6.6 integrity limit.
 *
 * <p><b>Silently dropped, creating no state</b> — never answered, never logged at a level a peer can
 * flood (SI-3): a datagram whose envelope will not parse; an unknown destination connection ID that
 * is not a long-header Initial; an Initial in a datagram under the RFC 9000 §14.1 1200-byte minimum;
 * a Version Negotiation or Retry arriving after the handshake has made progress; a Retry whose
 * integrity tag fails, or a second one (RFC 9000 §17.2.5).
 *
 * <p><b>Rejected as a connection error</b> — CONNECTION_CLOSE with the RFC 9000 §20 code named by
 * {@link io.activej.quic.connection.QuicTransportErrors}: a frame type illegal at its encryption
 * level, an empty payload, frames that will not parse past AEAD, an ACK for a packet number never
 * sent, an out-of-range or self-inconsistent transport parameter, a CRYPTO stream that contradicts
 * itself or exceeds its buffer bound, and an application frame arriving with no
 * {@link io.activej.quic.connection.QuicFrameHandler} registered — the peer was told of zero streams
 * and no DATAGRAM support, so it has exceeded a limit it was given.
 *
 * <p><b>Not classified, because it is not checked</b>: the <em>source address</em> of an inbound
 * datagram. A datagram is routed by destination connection ID alone (RFC 9000 §5.2), so one bearing a
 * live connection ID is processed whatever address it arrives from. This is not an amplification
 * path — every reply goes to the address the connection was opened against, never to the sender —
 * and AEAD is what actually rejects an off-path injection. Connection migration (RFC 9000 §9), which
 * is what would make an address change meaningful, is out of scope for this feature.
 *
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9000">RFC 9000 — QUIC: A UDP-Based Multiplexed
 * and Secure Transport</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9001">RFC 9001 — Using TLS to Secure QUIC</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9002">RFC 9002 — QUIC Loss Detection and
 * Congestion Control</a>
 */
package io.activej.quic.connection;
