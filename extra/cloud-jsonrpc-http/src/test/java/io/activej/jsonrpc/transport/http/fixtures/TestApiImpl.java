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

package io.activej.jsonrpc.transport.http.fixtures;

import io.activej.jsonrpc.JsonRpcErrors;
import io.activej.jsonrpc.JsonRpcException;
import io.activej.jsonrpc.JsonRpcPayload;
import io.activej.promise.Promise;

import static java.nio.charset.StandardCharsets.US_ASCII;

/**
 * {@link TestApi}'s implementation — the two failure shapes of feature 012's README example side by
 * side, so the difference between "the error object IS the contract" and "the peer is told
 * {@code -32603} and nothing else" is the only thing a test varies.
 */
public final class TestApiImpl implements TestApi {
	/**
	 * The {@code data} member of {@link #failWithData()}'s error object, as bytes — rendered
	 * verbatim on the wire, and the exact document a test compares the round-tripped {@code data}
	 * against (US2 scenario 2). Mirrors {@code FailingApiImpl.APPLICATION_ERROR_JSON}.
	 */
	public static final String FAIL_WITH_DATA_JSON = "{\"retryAfter\":30}";

	@Override
	public Promise<AddResult> add(int a, int b) {
		return Promise.of(new AddResult(a + b));
	}

	@Override
	public void notify(String message) {
		// a notification has nowhere to put a result; the dispatcher's totality absorbs anything
		// thrown here, and nothing reaches the wire either way
	}

	@Override
	public Promise<Void> notifyAsync(String message) {
		// a notification's result is never answered regardless (feature 012's F14)
		return Promise.complete();
	}

	@Override
	public Promise<String> failDeliberately(int code) {
		// deliberate: this error object IS the contract and reaches the caller verbatim. The
		// exception's own message stays local — it is never copied into the error object
		return Promise.ofException(new JsonRpcException(
			JsonRpcErrors.of(code, "deliberate failure"), "a deliberate failure carrying its own code"));
	}

	@Override
	public Promise<String> failWithData() {
		// deliberate, with data: code, message and the data member all travel verbatim (FR-072 of
		// feature 012). The exception's own message stays local, as on the no-data sibling
		byte[] data = FAIL_WITH_DATA_JSON.getBytes(US_ASCII);
		return Promise.ofException(new JsonRpcException(
			JsonRpcErrors.of(1001, "deliberate failure with data", JsonRpcPayload.raw(data, 0, data.length)),
			"a deliberate failure carrying its own code, message and data"));
	}

	@Override
	public Promise<String> failAccidentally() {
		// accidental: whatever this says, the peer is told -32603 Internal error and nothing else
		throw new IllegalStateException("an accidental failure; its message must never reach the wire");
	}
}
