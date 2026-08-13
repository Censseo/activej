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

package io.activej.jsonrpc.service;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks an interface method as a JSON-RPC notification — a call with no response and no correlation id,
 * declared on a {@link JsonRpcService}-annotated interface. The method must return {@code void} (or,
 * server-side, {@code Promise<Void>}); the wire-name prefix rule is the same as {@link JsonRpcMethod}'s, and
 * so is its <b>wire-name commitment</b> — an empty {@link #value()} puts the Java method's own name on the
 * wire, where a later rename is a wire-format change with no compile error anywhere.
 * <p>
 * <b>Deliberate name collision</b>: this annotation shares its simple name {@code JsonRpcNotification} with
 * the unrelated envelope record {@code io.activej.jsonrpc.JsonRpcNotification} (the decoded wire message with
 * no {@code id}). The collision is intentional rather than an oversight — each name is the obvious one in its
 * own vocabulary (a method-level marker here, a message shape there) and the two are never used from the same
 * import scope except by the dispatcher and the client's invocation handler, which are the only two places
 * that translate between "this interface method is a notification" and "this wire message is a notification".
 * Every other consumer needs at most one of the two and can import it unqualified.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface JsonRpcNotification {
	/**
	 * The method's own wire name (combined with the service's prefix, if any), or {@code ""} to fall back to
	 * the method's name.
	 */
	String value() default "";
}
