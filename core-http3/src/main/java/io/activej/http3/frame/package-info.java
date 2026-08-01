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
 * The RFC 9114 §7.2 HTTP/3 frame codec: the frame type hierarchy, the resumable
 * {@code Http3FrameReader}, and the synchronous request-stream frame-sequence validator.
 *
 * <p><b>This package is synchronous</b> (ADR-016): no {@link io.activej.reactor.Reactor}, no
 * {@code io.activej.promise.Promise}, no {@code checkInReactorThread}, and no
 * {@code io.activej.net.socket.udp.IUdpSocket} — an import of any of those from a source file
 * under this package is a review failure. That is what keeps the RFC-vector, round-trip and
 * adversarial tests here free of an eventloop. The reactive layer in {@link io.activej.http3}
 * drives this codec; this package does not drive itself.
 *
 * <p>Every frame value knows its own exact wire size and how to write itself
 * ({@code encodedLength()} / {@code writeTo(ByteBuf)} — the self-sizing wire value pattern
 * shared with {@code core-quic}'s codec). A mismatch between the two is silent corruption, which
 * is exactly what the round-trip tests exist to catch.
 *
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9114#section-7.2">RFC 9114 §7.2 — HTTP/3
 * Frames</a>
 */
package io.activej.http3.frame;
