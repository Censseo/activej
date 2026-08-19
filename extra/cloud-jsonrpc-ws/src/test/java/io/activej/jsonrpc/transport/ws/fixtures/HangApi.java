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

package io.activej.jsonrpc.transport.ws.fixtures;

import io.activej.jsonrpc.service.JsonRpcMethod;
import io.activej.jsonrpc.service.JsonRpcParam;
import io.activej.jsonrpc.service.JsonRpcService;
import io.activej.promise.Promise;

/**
 * The US3 purge fixture: a call whose <b>implementation never answers</b>, so the caller's promise
 * stays in flight until the connection dies (T013, FR-094). Registered on <b>both</b> dispatchers —
 * the servlet's (what a client calls) and the client's peer handler (what the server calls) — so the
 * same interface produces the three server-initiated <i>and</i> the three client-initiated pending
 * calls of the purge matrix. Wire name {@code hang.request}.
 * <p>
 * Because the impl returns a promise that never completes, neither correlation table is ever drained
 * by an answer: the only way out of the matrix is the connection drop, which is exactly what the
 * purge tests assert. The never-completing promise is deliberately <b>not</b> registered with
 * {@code TestUtils.assertCompleteFn()}, so {@code ActivePromisesRule} stays out of the picture until
 * the drop makes every pending call complete exceptionally.
 */
@JsonRpcService("hang")
public interface HangApi {
	/** The never-answered call. Wire name {@code hang.request}. */
	@JsonRpcMethod("request")
	Promise<String> request(@JsonRpcParam("n") int n);
}
