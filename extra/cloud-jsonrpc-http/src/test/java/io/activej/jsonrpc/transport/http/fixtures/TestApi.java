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

import io.activej.jsonrpc.service.JsonRpcMethod;
import io.activej.jsonrpc.service.JsonRpcNotification;
import io.activej.jsonrpc.service.JsonRpcParam;
import io.activej.jsonrpc.service.JsonRpcService;
import io.activej.promise.Promise;

/**
 * The test service this module's tests and probes dispatch through, modelled on feature 012's own
 * fixtures ({@code UserApi}, {@code PaymentsApi}).
 * <p>
 * Every wire name is <b>explicit</b>: the four {@code @JsonRpcMethod} / {@code @JsonRpcNotification}
 * {@code value()}s put {@code test.add}, {@code test.notify}, {@code test.failDeliberately} and
 * {@code test.failAccidentally} on the wire, so a later rename of any Java method is not a
 * wire-format change (feature 012's wire-name commitment rule, inherited).
 * <p>
 * {@link AddResult} is a {@code record}, so {@code JsonCodecFactory} derives its codec with no
 * registration — which is exactly the shape the feature's own tests want (FR-014's payload is one
 * codec round trip).
 */
@JsonRpcService("test")
public interface TestApi {
	/** The plain success path: two named parameters in, one record out. Wire name {@code test.add}. */
	@JsonRpcMethod("add")
	Promise<AddResult> add(@JsonRpcParam("a") int a, @JsonRpcParam("b") int b);

	/** The notification — never answered, so it drives the empty-response (204) path. Wire name {@code test.notify}. */
	@JsonRpcNotification("notify")
	void notify(@JsonRpcParam("message") String message);

	/**
	 * The asynchronous notification — a {@code Promise<Void>} return, so the transport's send promise
	 * is observable end-to-end through the proxy (US2 acceptance scenario 3; feature 012's F11 — the
	 * notification's promise IS the transport's send promise). Wire name {@code test.notifyAsync}.
	 */
	@JsonRpcNotification("notifyAsync")
	Promise<Void> notifyAsync(@JsonRpcParam("message") String message);

	/** Fails deliberately with a {@code JsonRpcException} carrying the caller-chosen code. Wire name {@code test.failDeliberately}. */
	@JsonRpcMethod("failDeliberately")
	Promise<String> failDeliberately(@JsonRpcParam("code") int code);

	/**
	 * Fails deliberately with a {@code JsonRpcException} carrying code, message <b>and data</b> —
	 * the one failure shape {@link #failDeliberately} never sets (US2 acceptance scenario 2).
	 * Wire name {@code test.failWithData}.
	 */
	@JsonRpcMethod("failWithData")
	Promise<String> failWithData();

	/** Fails accidentally with a plain exception — the peer must see {@code -32603} and nothing else. */
	@JsonRpcMethod("failAccidentally")
	Promise<String> failAccidentally();

	/** The record result of {@link #add}; codec-derived, nothing registered. */
	record AddResult(int sum) {}
}
