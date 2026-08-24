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

import io.activej.jsonrpc.service.JsonRpcMethod;
import io.activej.jsonrpc.service.JsonRpcNotification;
import io.activej.jsonrpc.service.JsonRpcParam;
import io.activej.jsonrpc.service.JsonRpcService;
import io.activej.promise.Promise;

/**
 * A service whose every invocation stays in flight until the test releases it — the only way to hold N
 * invocations concurrently on a single-threaded reactor, and therefore the only way to observe a
 * concurrency bound at all.
 * <p>
 * Both shapes are here deliberately: an in-flight bound counts a <b>notification</b>'s invocation exactly as
 * it counts a request's, and the two differ in what a rejection may emit — an answer for one, nothing at all
 * for the other. A notification therefore declares {@code Promise<Void>} rather than {@code void}, which
 * contract rule 5 permits and which is what makes it pendable in the first place.
 *
 * @see SlowApiImpl
 */
@JsonRpcService("slow")
public interface SlowApi {
	/** Completes with {@code tag}, once {@link SlowApiImpl} is told to. */
	@JsonRpcMethod("call")
	Promise<String> call(@JsonRpcParam("tag") String tag);

	/** As {@link #call}, with nowhere to put the answer — the notification half of the same bound. */
	@JsonRpcNotification("notify")
	Promise<Void> notifySlowly(@JsonRpcParam("tag") String tag);
}
