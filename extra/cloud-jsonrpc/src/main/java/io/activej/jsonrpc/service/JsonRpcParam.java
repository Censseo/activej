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
 * Names a {@link JsonRpcMethod} or {@link JsonRpcNotification} parameter for JSON-RPC's named-{@code params}
 * calling convention.
 * <p>
 * {@link #value()} is <b>required</b>, not defaulted: this build carries no {@code -parameters} compiler
 * flag, so a parameter's reflective name is a synthetic {@code arg0}, {@code arg1}, … with no relationship to
 * the source. Inventing a fallback onto that synthetic name would make an unrelated compiler flag load-bearing
 * for the wire contract. A method with any unannotated parameter is positional-only — see
 * {@link JsonRpcParamStyle}.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
public @interface JsonRpcParam {
	/** This parameter's name in named {@code params}. Required — there is no reflective fallback. */
	String value();
}
