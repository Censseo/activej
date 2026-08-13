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
 * Marks an interface as a JSON-RPC service and supplies the wire-name prefix for every
 * {@link JsonRpcMethod} and {@link JsonRpcNotification} it declares.
 * <p>
 * <b>Prefix rule</b>: when {@link #value()} is non-empty, a method's wire name is
 * {@code value() + "." + <method's own wire name>} — for example {@code @JsonRpcService("user")} combined
 * with {@code @JsonRpcMethod("get")} yields the wire name {@code user.get}. When {@link #value()} is left at
 * its default (empty), no prefix is added and the method's own wire name is used verbatim; a method may also
 * supply the full dotted name itself, e.g. {@code @JsonRpcMethod("user.get")} on an unprefixed service.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface JsonRpcService {
	/** The wire-name prefix for every method of this service, or {@code ""} for no prefix. */
	String value() default "";
}
