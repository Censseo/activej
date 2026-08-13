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
 * Marks an interface method as a JSON-RPC call that expects a response, declared on a
 * {@link JsonRpcService}-annotated interface. The method must return {@code Promise<T>} (or, on the
 * server-only implementation side, a synchronous {@code T}).
 * <p>
 * <b>Wire-name commitment</b>: {@link #value()} defaults to {@code ""}, which falls back to the Java
 * method's own name. Relying on that fallback puts the method's <em>Java identifier</em> on the wire — a
 * later rename of the method is then a wire-format change with no compile error anywhere, since nothing
 * ties the two together. Give every method an explicit {@link #value()} in any interface whose wire name
 * must stay stable.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface JsonRpcMethod {
	/**
	 * The method's own wire name (combined with the service's prefix, if any), or {@code ""} to fall back to
	 * the method's name.
	 */
	String value() default "";
}
