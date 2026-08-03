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
 * QUIC streams and flow control: ordered, reliable, bidirectional or unidirectional byte streams
 * multiplexed over a single {@link io.activej.quic.connection.QuicConnection}, per RFC 9000 §2–§4
 * (stream types, lifetimes and operation) and §19.4–§19.13 (the stream, flow-control and
 * limit-signalling frames).
 *
 * <p>This package owns:
 * <ul>
 *   <li>stream identifier allocation and role/directionality classification — RFC 9000 §2.1,
 *       §2.3;</li>
 *   <li>the send and receive state machines of an individual stream — RFC 9000 §3.1, §3.2;</li>
 *   <li>out-of-order reassembly and final-size tracking — RFC 9000 §2.2, §4.5;</li>
 *   <li>stream- and connection-level flow control, including credit grants and blocked
 *       signalling — RFC 9000 §4;</li>
 *   <li>stream-count limits in both directions — RFC 9000 §4.6;</li>
 *   <li>abrupt termination in either direction via {@code RESET_STREAM} / {@code STOP_SENDING} —
 *       RFC 9000 §3.5.</li>
 * </ul>
 *
 * <p>Like {@link io.activej.quic.connection}, and unlike {@link io.activej.quic.codec},
 * {@link io.activej.quic.crypto} and {@link io.activej.quic.tls}, this package <em>is</em> reactive
 * (ADR-016, <em>reactive shell over a synchronous protocol core</em>): every component that holds
 * stream state is bound to a single {@link io.activej.reactor.Reactor} and guards each public method
 * with {@code checkInReactorThread(this)}. It is the connection layer's second reactive package
 * rather than a new one competing with it — it is registered with a
 * {@link io.activej.quic.connection.QuicConnection} as a
 * {@link io.activej.quic.connection.QuicFrameHandler} and does not itself touch the wire, the packet
 * number spaces, or loss recovery.
 *
 * <p>Buffer ownership follows the platform rule — a received {@link io.activej.bytebuf.ByteBuf} is
 * owned and must be recycled on every path, including failure paths — and every entry point that
 * hands one to or takes one from a caller states its ownership rule on itself.
 *
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9000">RFC 9000 — QUIC: A UDP-Based Multiplexed
 * and Secure Transport</a>
 */
package io.activej.quic.stream;
