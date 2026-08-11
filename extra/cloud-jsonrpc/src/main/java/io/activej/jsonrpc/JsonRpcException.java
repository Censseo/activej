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

package io.activej.jsonrpc;

import io.activej.common.ApplicationSettings;

import java.util.Objects;

/**
 * A checked exception carrying a whole {@link JsonRpcError} — code, message and {@code data} — so that a call
 * which must be completed exceptionally keeps its error triple instead of degrading to a string (FR-017).
 *
 * <h2>Its role is narrow</h2>
 * The envelope decoder <b>never</b> throws this. A malformed envelope is expected traffic on that path, not
 * an exceptional condition, and a batch needs one outcome <i>per element</i> — which no exception can carry;
 * so decoding returns a {@code JsonRpcMalformed} instead (FR-080). This exception exists for the other
 * direction: crossing an API boundary, where a caller holds a failed call and must complete it.
 *
 * <h2>The one-way valve (FR-089)</h2>
 * The {@linkplain #getMessage() message} of <i>this exception</i> may carry diagnostic detail, because it
 * stays local. The {@link JsonRpcError} it carries is what goes on the wire, and its message must remain one
 * of the fixed strings of {@link JsonRpcErrors}. Never funnel a caught exception's message into the error
 * object: {@code JsonUtils.fromJsonBytes} builds its {@code MalformedDataException} as
 * {@code "Unexpected JSON data: " + <the remaining input bytes>}, so doing so echoes a peer's input to that
 * peer's counterparty.
 *
 * <h2>Stackless</h2>
 * Following the {@code HttpException} idiom, {@link #fillInStackTrace()} returns {@code this} and captures
 * nothing. This is a control-flow exception on a hot path; paying for a stack capture per error response is
 * precisely the cost the idiom avoids. Set {@code -DJsonRpcException.withStackTrace=true} (or the fully
 * qualified {@code -Dio.activej.jsonrpc.JsonRpcException.withStackTrace=true}) to get a real trace while
 * debugging. Note that the class is <b>copied, not inherited</b> — this module takes no {@code core-http}
 * dependency (FR-002).
 */
public class JsonRpcException extends Exception {
	/** Resolved once at class-initialisation from {@code JsonRpcException.withStackTrace}; {@code false} by default. */
	public static final boolean WITH_STACK_TRACE =
		ApplicationSettings.getBoolean(JsonRpcException.class, "withStackTrace", false);

	/**
	 * @serial the error object this exception carries. Note that a {@link JsonRpcPayload.Raw} reachable
	 * through it is <b>not</b> serializable — this exception inherits {@code Serializable} from
	 * {@link Throwable} but is never intended to cross a serialization boundary; it crosses an API one
	 */
	private final JsonRpcError error;

	/** Uses the error object's own message as the exception message. */
	public JsonRpcException(JsonRpcError error) {
		super(Objects.requireNonNull(error, "error").message());
		this.error = error;
	}

	/**
	 * Uses a local diagnostic message, leaving the error object's own message — the one that reaches the peer
	 * — untouched.
	 */
	public JsonRpcException(JsonRpcError error, String message) {
		super(message);
		this.error = Objects.requireNonNull(error, "error");
	}

	/**
	 * Uses the error object's own message, and carries {@code cause} for a local caller.
	 *
	 * @param error the error object to render into a response document
	 * @param cause  the underlying failure; its stack trace is printed even when this exception is stackless
	 */
	public JsonRpcException(JsonRpcError error, Throwable cause) {
		super(Objects.requireNonNull(error, "error").message(), cause);
		this.error = error;
	}

	/**
	 * Uses a local diagnostic message and carries {@code cause}.
	 *
	 * @param error   the error object to render into a response document
	 * @param message  local diagnostic detail; it must NOT be copied into the error object (FR-089)
	 * @param cause    the underlying failure
	 */
	public JsonRpcException(JsonRpcError error, String message, Throwable cause) {
		super(message, cause);
		this.error = Objects.requireNonNull(error, "error");
	}

	/** The error object to render into a response document. Never {@code null}. */
	public JsonRpcError getError() {
		return error;
	}

	/**
	 * Captures nothing unless {@link #WITH_STACK_TRACE} is on — the whole point of the stackless idiom.
	 * <p>
	 * Deliberately <b>not</b> {@code synchronized}, unlike {@link Throwable#fillInStackTrace()} and exactly
	 * like {@code HttpException}, whose idiom this copies: taking a monitor to return {@code this} is the
	 * cost the idiom exists to avoid. The guarded branch still enters the synchronized {@code super}
	 * implementation, so nothing races on the trace itself.
	 */
	@Override
	public Throwable fillInStackTrace() {
		return WITH_STACK_TRACE ? super.fillInStackTrace() : this;
	}
}
