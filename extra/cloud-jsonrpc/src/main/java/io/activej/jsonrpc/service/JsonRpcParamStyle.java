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

/**
 * How a {@link JsonRpcClient} proxy encodes a call's {@code params} member.
 */
public enum JsonRpcParamStyle {
	/** {@code params} is a JSON array, arguments in declaration order. The default. */
	POSITIONAL,
	/**
	 * {@code params} is a JSON object keyed by {@link JsonRpcParam#value()}. Refused at
	 * {@code proxy(...)} time for any method with an unannotated parameter.
	 */
	NAMED
}
