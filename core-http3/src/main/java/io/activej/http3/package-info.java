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
 * HTTP/3 (RFC 9114) server and client over the QUIC transport, using the message mapping of
 * {@link io.activej.http3.frame} and the header compression of {@link io.activej.http3.qpack} to
 * serve and issue the platform's own {@link io.activej.http.HttpRequest}/
 * {@link io.activej.http.HttpResponse}, unchanged, over a different wire protocol.
 *
 * <p>This is the module's <b>only reactive</b> package (ADR-016, the same split
 * {@code core-quic} applies between its {@code connection} package and everything below it).
 * Every component here that holds connection or stream state is bound to a single
 * {@link io.activej.reactor.Reactor}, takes it as the first constructor argument — hence the
 * {@code X.builder(reactor, ...)} signature throughout — and guards every public method with
 * {@code checkInReactorThread(this)} as its first statement. {@link io.activej.http3.frame} and
 * {@link io.activej.http3.qpack} take no {@code Reactor}, return no {@code Promise}, and call no
 * such check; an import of {@code Reactor}, {@code Promise} or
 * {@code io.activej.net.socket.udp.IUdpSocket} there is a review failure.
 *
 * <p>Buffer ownership follows the platform rule: a received {@link io.activej.bytebuf.ByteBuf} is
 * owned by the code path that received it and must be recycled on every path, including error,
 * cancellation, reset and close.
 *
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9114">RFC 9114 — HTTP/3</a>
 */
package io.activej.http3;
