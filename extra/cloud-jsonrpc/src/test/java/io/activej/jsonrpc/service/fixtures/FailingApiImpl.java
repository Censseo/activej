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

package io.activej.jsonrpc.service.fixtures;

import io.activej.jsonrpc.JsonRpcError;
import io.activej.jsonrpc.JsonRpcErrors;
import io.activej.jsonrpc.JsonRpcException;
import io.activej.jsonrpc.JsonRpcPayload;
import io.activej.promise.Promise;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

import static java.nio.charset.StandardCharsets.US_ASCII;

/**
 * The implementation behind {@link FailingApi}. Every failure carries a string that must <b>never</b> appear
 * in an outgoing document, so a test can assert the negative by searching the response for it (SC-007).
 */
public final class FailingApiImpl implements FailingApi {
	/**
	 * The secret. It is the exception's message, so any implementation that copied a message, a class name or
	 * a stack frame into the wire would put it there.
	 */
	public static final String SECRET = "db password is hunter2";

	/** The {@code data} member of {@link #APPLICATION_ERROR}, as bytes, so it is re-emitted byte-identically. */
	private static final byte[] DATA = "{\"retryAfter\":30}".getBytes(US_ASCII);

	/**
	 * A deliberate application error: an unreserved code, its own message and a structured {@code data}
	 * member. All three must reach the peer verbatim (FR-047).
	 */
	public static final JsonRpcError APPLICATION_ERROR =
		JsonRpcErrors.of(429, "Too many requests", JsonRpcPayload.raw(DATA, 0, DATA.length));

	/** The rendering {@link #APPLICATION_ERROR} must produce, whichever route carried it. */
	public static final String APPLICATION_ERROR_JSON =
		"{\"code\":429,\"message\":\"Too many requests\",\"data\":{\"retryAfter\":30}}";

	private final List<String> invocations = new ArrayList<>();
	private final List<@Nullable String> arguments = new ArrayList<>();

	@Override
	public Promise<String> thrown() {
		invocations.add("thrown()");
		throw new IllegalStateException(SECRET);
	}

	@Override
	public Promise<String> failedPromise() {
		invocations.add("failedPromise()");
		return Promise.ofException(new IllegalStateException(SECRET));
	}

	@Override
	public Promise<String> failedWithJsonRpc() {
		invocations.add("failedWithJsonRpc()");
		// the local message carries the secret and the wire-bound error object does not — the one-way valve
		return Promise.ofException(new JsonRpcException(APPLICATION_ERROR, SECRET));
	}

	@Override
	public Promise<String> thrownJsonRpc() throws JsonRpcException {
		invocations.add("thrownJsonRpc()");
		throw new JsonRpcException(APPLICATION_ERROR, SECRET);
	}

	@Override
	public Promise<String> nullPromise() {
		invocations.add("nullPromise()");
		return null;
	}

	@Override
	public Promise<String> nullResult() {
		invocations.add("nullResult()");
		return Promise.of(null);
	}

	@Override
	public Promise<String> echo(@Nullable String value) {
		invocations.add("echo(" + value + ')');
		arguments.add(value);
		return Promise.of(value == null ? "<null>" : value);
	}

	@Override
	public Promise<Void> notifyAndFail(@Nullable String value) {
		invocations.add("notifyAndFail(" + value + ')');
		return Promise.ofException(new IllegalStateException(SECRET));
	}

	@Override
	public void notifyAndThrow(@Nullable String value) {
		invocations.add("notifyAndThrow(" + value + ')');
		throw new IllegalStateException(SECRET);
	}

	@Override
	public void notifyAndThrowError(@Nullable String value) {
		invocations.add("notifyAndThrowError(" + value + ')');
		throw new AssertionError(SECRET);
	}

	/** Every invocation so far, in order, rendered as {@code name(args)}. */
	public List<String> invocations() {
		return invocations;
	}

	/** Every {@link #echo} argument so far, {@code null}s included — the point of the fixture. */
	public List<@Nullable String> arguments() {
		return arguments;
	}
}
