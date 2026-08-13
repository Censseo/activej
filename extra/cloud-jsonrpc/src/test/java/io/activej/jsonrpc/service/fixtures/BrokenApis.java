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
 * One deliberately broken service type per contract rule, plus one carrying several at once.
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

	// -------------------------------------------------------------------------------------------------
	// Rule 1 — the service type must be an interface (FR-021).
	// -------------------------------------------------------------------------------------------------

	/** A class, not an interface. */
	@JsonRpcService("clazz")
	public static class NotAnInterface {
		@JsonRpcMethod("get")
		public Promise<User> get(@JsonRpcParam("id") long id) {
			return Promise.of(new User(id, "x"));
		}
	}

	/** A {@code record} — the other shape an author might reach for. */
	@JsonRpcService("rec")
	public record NotAnInterfaceRecord(long id) {}

	// -------------------------------------------------------------------------------------------------
	// Rule 2 — every abstract method carries one of the two annotations (FR-022).
	// -------------------------------------------------------------------------------------------------

	/** {@code forgotten} carries no annotation at all — a misspelled import, in practice. */
	@JsonRpcService("unannotated")
	public interface UnannotatedAbstractMethod {
		@JsonRpcMethod("get")
		Promise<User> get(@JsonRpcParam("id") long id);

		Promise<User> forgotten(@JsonRpcParam("id") long id);
	}

	// -------------------------------------------------------------------------------------------------
	// Rule 3 — not both annotations on one method (FR-017).
	// -------------------------------------------------------------------------------------------------

	@JsonRpcService("both")
	public interface BothAnnotations {
		@JsonRpcMethod("get")
		@JsonRpcNotification("get")
		Promise<User> get(@JsonRpcParam("id") long id);
	}

	// -------------------------------------------------------------------------------------------------
	// Rule 4 — no two methods resolve to the same wire name (FR-025).
	// -------------------------------------------------------------------------------------------------

	@JsonRpcService("dup")
	public interface DuplicateWireName {
		@JsonRpcMethod("get")
		Promise<User> getOne(@JsonRpcParam("id") long id);

		@JsonRpcMethod("get")
		Promise<User> getAnother(@JsonRpcParam("id") long id);
	}

	// -------------------------------------------------------------------------------------------------
	// Rule 5 — a notification returns void or Promise<Void> (FR-026).
	// -------------------------------------------------------------------------------------------------

	@JsonRpcService("notif")
	public interface NotificationReturningAValue {
		@JsonRpcNotification("touch")
		Promise<User> touch(@JsonRpcParam("id") long id);
	}

	// -------------------------------------------------------------------------------------------------
	// Rule 6 — a method does not return void (FR-027).
	// -------------------------------------------------------------------------------------------------

	@JsonRpcService("voidmethod")
	public interface VoidReturningMethod {
		@JsonRpcMethod("get")
		void get(@JsonRpcParam("id") long id);
	}

	// -------------------------------------------------------------------------------------------------
	// Rule 7 — no raw Promise, no Promise<?>, no unbound type variable (FR-028).
	// -------------------------------------------------------------------------------------------------

	@SuppressWarnings("rawtypes")
	@JsonRpcService("raw")
	public interface RawPromise {
		@JsonRpcMethod("get")
		Promise get(@JsonRpcParam("id") long id);
	}

	@JsonRpcService("wildcard")
	public interface WildcardPromise {
		@JsonRpcMethod("get")
		Promise<?> get(@JsonRpcParam("id") long id);
	}

	/** Generic and never bound: {@code of(UnboundTypeVariable.class, …)} has nothing to substitute for {@code T}. */
	@JsonRpcService("unbound")
	public interface UnboundTypeVariable<T> {
		@JsonRpcMethod("get")
		Promise<T> get(@JsonRpcParam("id") long id);
	}

	// -------------------------------------------------------------------------------------------------
	// Rule 8 — every parameter type and the result type resolve to a codec (FR-029).
	// -------------------------------------------------------------------------------------------------

	@JsonRpcService("unresolvable")
	public interface UnresolvableParameterType {
		@JsonRpcMethod("get")
		Promise<User> get(@JsonRpcParam("thing") Unresolvable thing);
	}

	@JsonRpcService("unresolvableResult")
	public interface UnresolvableResultType {
		@JsonRpcMethod("get")
		Promise<Unresolvable> get(@JsonRpcParam("id") long id);
	}

	// -------------------------------------------------------------------------------------------------
	// Several at once — SC-005: one exception, every fault named.
	// -------------------------------------------------------------------------------------------------

	/**
	 * Five faults of four different kinds: an unannotated abstract method, a notification returning a value,
	 * a {@code void}-returning method, an unresolvable parameter type, and a duplicate wire name shared by
	 * {@code alpha} and {@code alsoAlpha}.
	 */
	@JsonRpcService("many")
	public interface ManyViolations {
		Promise<User> forgotten(@JsonRpcParam("id") long id);

		@JsonRpcNotification("touch")
		Promise<User> touch(@JsonRpcParam("id") long id);

		@JsonRpcMethod("nothing")
		void nothing(@JsonRpcParam("id") long id);

		@JsonRpcMethod("weird")
		Promise<User> weird(@JsonRpcParam("thing") Unresolvable thing);

		@JsonRpcMethod("alpha")
		Promise<User> alpha(@JsonRpcParam("id") long id);

		@JsonRpcMethod("alpha")
		Promise<User> alsoAlpha(@JsonRpcParam("id") long id);
	}
}
