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

package io.activej.http3;

import io.activej.http3.Http3Connection.GoAwayDirection;

/**
 * The module-internal seam through which an {@link Http3Connection} and the
 * {@link Http3RequestStream}s it owns report the four events an {@link Http3Server.Inspector} or an
 * {@link Http3Client.Inspector} publishes but neither a server nor a client can see for itself
 * (FR-062).
 * <p>
 * Deliberately <b>not public</b>: a connection is built by the server or the client that owns it, and
 * this is how that owner is told what happened underneath it. The public observability surface is the
 * two {@code Inspector} interfaces, and adding a second one would be two contracts for one thing.
 * <p>
 * Every method defaults to doing nothing, so a connection built without a listener — which is every
 * connection an application assembles by hand — carries no obligation.
 * <p>
 * <b>Never carries</b> a field value, a body byte, a cookie, a credential or key material (FR-063):
 * every parameter here is a number or a direction.
 * <p>
 * <b>Threading</b>: every callback runs on the connection's reactor thread, inside the operation that
 * produced the event. An implementation that blocks blocks the reactor, and one that throws fails that
 * operation — accumulate, never act.
 */
interface Http3EventListener {
	/** A listener that does nothing, so no call site needs a null check. */
	Http3EventListener NONE = new Http3EventListener() {};

	/**
	 * The connection is closing with an RFC 9114 §8.1 / RFC 9204 §6 application error code. Fired before
	 * the streams that error aborts, so the cause is reported ahead of its consequences.
	 */
	default void onConnectionError(long errorCode) {}

	/**
	 * A frame of an unknown type was discarded unread, per RFC 9114 §9's GREASE rule — on the control
	 * stream or on a request stream.
	 *
	 * @param declaredLength the payload length the frame declared, which is what was skipped
	 */
	default void onFrameDiscarded(long frameType, long declaredLength) {}

	/** A GOAWAY this endpoint announced ({@code SENT}) or the peer announced ({@code RECEIVED}). */
	default void onGoAway(GoAwayDirection direction, long id) {}

	/**
	 * A request stream was aborted — by this endpoint or by the peer — with the RFC 9114 §8.1 code the
	 * {@code RESET_STREAM}/{@code STOP_SENDING} carries.
	 */
	default void onStreamReset(long streamId, long errorCode) {}
}
