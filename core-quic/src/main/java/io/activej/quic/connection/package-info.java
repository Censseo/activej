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
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9000">RFC 9000 — QUIC: A UDP-Based Multiplexed
 * and Secure Transport</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9001">RFC 9001 — Using TLS to Secure QUIC</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9002">RFC 9002 — QUIC Loss Detection and
 * Congestion Control</a>
 */
package io.activej.quic.connection;
