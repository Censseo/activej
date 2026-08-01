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
 * QPACK (RFC 9204) field-section compression, static-table only: RFC 9204 §4.1.1 prefixed
 * integers, RFC 7541 Appendix B Huffman coding, the 99-entry RFC 9204 Appendix A static table,
 * and the encoder/decoder pair built on them. The dynamic table is out of scope for this feature
 * and every reference to it is rejected.
 *
 * <p><b>This package is synchronous</b> (ADR-016): no {@link io.activej.reactor.Reactor}, no
 * {@code io.activej.promise.Promise}, no {@code checkInReactorThread}, and no
 * {@code io.activej.net.socket.udp.IUdpSocket} — an import of any of those from a source file
 * under this package is a review failure. That is what keeps the RFC-vector, round-trip and
 * adversarial tests here free of an eventloop. The reactive layer in {@link io.activej.http3}
 * drives this codec; this package does not drive itself.
 *
 * <p>{@code QpackIntegers} is deliberately <b>not</b> {@code io.activej.quic.codec.QuicVarInts}:
 * despite the shared word "integer", RFC 9204 §4.1.1's N-bit-prefix continuation encoding is a
 * genuinely different byte layout from the RFC 9000 §16 varint that HTTP/3 frame Type/Length
 * fields reuse verbatim.
 *
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9204">RFC 9204 — QPACK: Field Compression for
 * HTTP/3</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc7541#appendix-B">RFC 7541 Appendix B — Huffman
 * Code</a>
 */
package io.activej.http3.qpack;
