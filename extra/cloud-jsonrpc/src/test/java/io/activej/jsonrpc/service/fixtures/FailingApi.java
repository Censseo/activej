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

import io.activej.jsonrpc.JsonRpcException;
import io.activej.jsonrpc.service.JsonRpcMethod;
import io.activej.jsonrpc.service.JsonRpcNotification;
import io.activej.jsonrpc.service.JsonRpcParam;
import io.activej.jsonrpc.service.JsonRpcService;
import io.activej.promise.Promise;

/**
 * One method per failure shape of user story 5, so that every row of the Error Scenarios table is asserted
 * against a compiling interface rather than a hand-written document (FR-046…FR-050, SC-007).
 * <p>
 * The two {@code JsonRpcException} methods are deliberately a pair: the exception is <b>checked</b>, so an
 * implementation can only {@code throw} it where the interface declares {@code throws JsonRpcException}, and
 * the two routes must produce the same document (FR-047a). {@link #thrownJsonRpc()} is also the live proof
 * that a {@code throws} clause is not a contract violation — the contract would refuse to build otherwise,
 * and every test in this fixture's suite would fail at {@code setUp}.
 * <p>
 * Nothing here is nullable-annotated on purpose. A {@code null} argument or result is the declared codec's
 * business (FR-046a), so the same {@link #echo} and {@link #nullResult} methods are dispatched twice — once
 * through the default codec factory, which refuses {@code null}, and once through one whose {@code String}
 * codec accepts it.
 */
@JsonRpcService("fail")
public interface FailingApi {
	/** Throws {@code IllegalStateException("db password is hunter2")} before any promise exists (FR-048). */
	@JsonRpcMethod("thrown")
	Promise<String> thrown();

	/** Returns a promise failed with that same exception — the asynchronous route to FR-048. */
	@JsonRpcMethod("failedPromise")
	Promise<String> failedPromise();

	/** Returns a promise failed with a {@link JsonRpcException} carrying code, message and {@code data}. */
	@JsonRpcMethod("failedWithJsonRpc")
	Promise<String> failedWithJsonRpc();

	/**
	 * Throws that same {@link JsonRpcException} directly. The {@code throws} clause is what makes this
	 * compile, and it must be accepted by contract validation rather than rejected (FR-047a).
	 */
	@JsonRpcMethod("thrownJsonRpc")
	Promise<String> thrownJsonRpc() throws JsonRpcException;

	/** Returns {@code null} where a {@code Promise} was declared: a failed invocation, not an NPE (FR-046). */
	@JsonRpcMethod("nullPromise")
	Promise<String> nullPromise();

	/** Completes with a {@code null} result value, handed to the declared result codec unchanged (FR-046a). */
	@JsonRpcMethod("nullResult")
	Promise<String> nullResult();

	/** Records its argument, so a {@code null} one is observable at the far side of decoding (FR-046a). */
	@JsonRpcMethod("echo")
	Promise<String> echo(@JsonRpcParam("value") String value);

	/** A notification whose promise fails: no response element, but nothing swallowed either (FR-049, FR-050). */
	@JsonRpcNotification("notify")
	Promise<Void> notifyAndFail(@JsonRpcParam("value") String value);

	/** A notification that throws synchronously — the other way a notification can fail. */
	@JsonRpcNotification("notifyThrow")
	void notifyAndThrow(@JsonRpcParam("value") String value);
}
