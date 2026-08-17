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

package io.activej.launchers.jsonrpc.fixtures;

import io.activej.jsonrpc.service.JsonRpcMethod;
import io.activej.jsonrpc.service.JsonRpcNotification;
import io.activej.jsonrpc.service.JsonRpcParam;
import io.activej.jsonrpc.service.JsonRpcService;
import io.activej.promise.Promise;

/**
 * Deliberately-broken service types for the launcher's contract-violation tests.
 * <p>
 * These exist so that startup validation is asserted against a compiling interface rather than a string:
 * every member below is real Java that a service author could plausibly write, and each is rejected for a
 * different reason.
 */
public final class BrokenApis {
	private BrokenApis() {}

	/** A type this module's {@code JsonCodecFactory} cannot resolve a codec for — not a record, not a leaf. */
	public static final class Unresolvable {
		public final int x;

		public Unresolvable(int x) {this.x = x;}
	}

	/**
	 * Four faults of three different kinds: a {@code void}-returning call, a notification returning a value,
	 * an unresolvable parameter type, and a duplicate wire name shared by {@code alpha1} and {@code alpha2}.
	 * The launcher must fail startup naming every one of them.
	 */
	@JsonRpcService("broken")
	public interface ManyViolations {
		@JsonRpcMethod("nothing")
		void nothing(@JsonRpcParam("id") long id);

		@JsonRpcNotification("touch")
		Promise<User> touch(@JsonRpcParam("id") long id);

		@JsonRpcMethod("weird")
		Promise<User> weird(@JsonRpcParam("thing") Unresolvable thing);

		@JsonRpcMethod("alpha")
		Promise<User> alpha1(@JsonRpcParam("id") long id);

		@JsonRpcMethod("alpha")
		Promise<User> alpha2(@JsonRpcParam("id") long id);
	}

	/** A valid interface whose {@code user.get} wire name collides with the valid fixture's. */
	@JsonRpcService("user")
	public interface CollidingWireName {
		@JsonRpcMethod("get")
		Promise<User> get(@JsonRpcParam("id") long id);
	}
}
