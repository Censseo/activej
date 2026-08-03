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

package io.activej.quic.stream;

import io.activej.common.ApplicationSettings;

/**
 * A stream-scoped failure: one stream ended abnormally, or could not be opened, while the connection
 * itself stays usable (RFC 9000 §2.4, §3.5).
 * <p>
 * Checked, and extending {@link Exception} rather than {@code AsyncCloseException}, mirroring
 * {@link io.activej.quic.connection.QuicTransportException}: an aborted stream is an expected
 * protocol outcome, not a programming fault and not a close of the enclosing resource.
 * <p>
 * A <em>transport</em> failure — idle timeout, a peer {@code CONNECTION_CLOSE}, a protocol error —
 * is <b>not</b> wrapped in this type. It surfaces as {@code QuicTransportException} unwrapped, so
 * that the RFC 9000 §20 code it carries stays visible to the layer above.
 * <p>
 * Stack traces are suppressed by default, as {@code HttpError} and {@code RpcException} do: these are
 * control-flow exceptions on a hot path, one per aborted stream. Re-enable with
 * {@code -Dio.activej.quic.stream.QuicStreamException.withStackTrace=true} (or
 * {@code -DQuicStreamException.withStackTrace=true}) when debugging.
 * <p>
 * <b>Security (SI-6)</b>: the message names the offending stream, error code or configuration key.
 * It never carries stream payload bytes, key material, or a configured value that could be a secret.
 *
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9000#section-2.4">RFC 9000 §2.4 — Operations on
 * Streams</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9000#section-3.5">RFC 9000 §3.5 — Solicited State
 * Transitions</a>
 */
public class QuicStreamException extends Exception {
	/**
	 * Off by default, as {@code HttpError} and {@code RpcException} are: these are control-flow
	 * exceptions on a hot path, and capturing a stack for each abort costs more than it explains.
	 * Overridable with {@code -Dio.activej.quic.stream.QuicStreamException.withStackTrace=true}.
	 */
	public static final boolean WITH_STACK_TRACE =
		ApplicationSettings.getBoolean(QuicStreamException.class, "withStackTrace", false);

	/**
	 * @param message names the offending stream, error code or configuration key — never a payload
	 *                byte, a secret, or a configured value (SI-6). Stored and reported verbatim,
	 *                never used as a format string
	 */
	public QuicStreamException(String message) {
		super(message);
	}

	/**
	 * @param message as above (SI-6)
	 * @param cause   never a transport failure: feature 03's {@code QuicTransportException} surfaces
	 *                <b>unwrapped</b>, so its RFC 9000 §20 code stays visible to the layer above (FR-041)
	 */
	public QuicStreamException(String message, Throwable cause) {
		super(message, cause);
	}

	/** Suppressed unless {@link #WITH_STACK_TRACE} is set — see that field for why. */
	@Override
	public final Throwable fillInStackTrace() {
		return WITH_STACK_TRACE ? super.fillInStackTrace() : this;
	}
}
